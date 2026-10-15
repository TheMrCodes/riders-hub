package at.themrcodes.ridershub.api

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class MotionRecordingTest {
    private fun batch() = MotionBatch("11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333",
        "2030-01-01T00:00:00Z", 1000, listOf("accelerometer", "gyroscope"))

    @Test fun fiveSecondsKeepsEverySampleAndCopiesReusableSensorValues() {
        val batch = batch()
        val values = floatArrayOf(1f, 2f, 3f)
        repeat(250) { index ->
            assertTrue(batch.add("accelerometer", 1000L + index * 20_000_000L, values, 3))
            assertTrue(batch.add("gyroscope", 1001L + index * 20_000_000L, values, 3))
        }
        values[0] = 99f
        val first = requireNotNull(batch.drain())
        assertEquals(500, first.getJSONArray("samples").length())
        assertEquals(1.0, first.getJSONArray("samples").getJSONObject(0).getDouble("x"), 0.0)
        assertEquals(0L, first.getJSONArray("samples").getJSONObject(0).getLong("elapsed_ns"))
        assertEquals("android_device", first.getString("coordinate_system"))
        assertNull(batch.drain())
        batch.add("gyroscope", 5_000_001_001L, values, 2)
        val second = requireNotNull(batch.drain())
        assertEquals(2L, second.getLong("sequence"))
        assertEquals(first.getString("stream_id"), second.getString("stream_id"))
        assertNotEquals(first.getString("batch_id"), second.getString("batch_id"))
        assertEquals(1, second.getJSONArray("samples").length())
    }
    @Test fun fasterHardwareIsCappedPerSensorAcrossBatchBoundaries() {
        val batch = batch()
        repeat(1000) { index ->
            batch.add("accelerometer", 1000L + index * 1_000_000L, floatArrayOf(1f, 2f, 3f), 3)
            batch.add("gyroscope", 1000L + index * 1_000_000L, floatArrayOf(1f, 2f, 3f), 3)
        }
        val samples = batch.drain()!!.getJSONArray("samples")
        assertEquals(100, samples.length())
        assertFalse(batch.add("accelerometer", 990_001_000L, floatArrayOf(1f, 2f, 3f), 3))
        assertTrue(batch.add("accelerometer", 1_000_001_000L, floatArrayOf(1f, 2f, 3f), 3))
        assertEquals(1, batch.drain()!!.getJSONArray("samples").length())
    }
    @Test fun invalidOrRegressingEventsAreRejectedWithoutInventingSamples() {
        val batch = batch()
        assertFalse(batch.add("unknown", 1000, floatArrayOf(0f, 0f, 0f), 3))
        assertFalse(batch.add("accelerometer", 999, floatArrayOf(0f, 0f, 0f), 3))
        assertFalse(batch.add("accelerometer", 1000, floatArrayOf(Float.NaN, 0f, 0f), 3))
        assertTrue(batch.add("accelerometer", 1000, floatArrayOf(0f, 0f, 0f), 3))
        assertFalse(batch.add("accelerometer", 1000, floatArrayOf(0f, 0f, 0f), 3))
        assertEquals(1, batch.size)
    }
    @Test fun recordingJournalSurvivesReopenAndNeverCrossesServerScopes() {
        val app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("motion_recordings.db")
        val first = MotionStore(app)
        val batch = batch().apply { add("accelerometer", 1000, floatArrayOf(0f, 0f, 0f), 3) }.drain()!!
        val id = batch.getString("batch_id")
        first.approve("scope-a", true)
        first.record("scope-a", id, batch.toString()); first.close()
        MotionStore(app).use { reopened ->
            assertTrue(reopened.approved("scope-a"))
            assertFalse(reopened.approved("scope-b"))
            assertTrue(reopened.pending("scope-b").isEmpty())
            assertEquals(batch.toString(), reopened.pending("scope-a").single().payload)
            reopened.mark(id, "acknowledged")
            assertTrue(reopened.pending("scope-a").isEmpty())
            reopened.readableDatabase.rawQuery("SELECT payload FROM batch WHERE id=?", arrayOf(id)).use {
                assertTrue(it.moveToFirst()); assertEquals(batch.toString(), it.getString(0))
            }
        }
        app.deleteDatabase("motion_recordings.db")
    }
    @Test fun notificationStopBeforeSensorStartupStillRevokesMotionConsent() {
        val app = RuntimeEnvironment.getApplication()
        val protection = object : ApiCredentialProtection {
            override fun encrypt(value: String, purpose: String) = value
            override fun decrypt(value: String, purpose: String) = value
        }
        val api = ApiStore(app, protection)
        api.configureOidc("https://api.example.test", "rhd_" + "A".repeat(43))
        val caps = JSONObject("""{"contract_version":1,"server_id":"synthetic-server",
            "capabilities":{"motion":{"schema_version":1,"max_batch_samples":4096,"max_upload_bytes":1048576}}}""")
        api.saveCapabilities(requireNotNull(api.connection()).destinationId, caps, parseCapabilities(caps))
        val scope = requireNotNull(api.connection()).deliveryScope
        val journal = MotionStore.get(app)
        journal.approve(scope, true)
        val controller = Robolectric.buildService(RideMotionService::class.java).create()
        val service = controller.get()
        RideMotionService::class.java.getDeclaredField("store").apply { isAccessible = true }.set(service, api)
        val handler = RideMotionService::class.java.getDeclaredField("handler")
            .apply { isAccessible = true }.get(service) as Handler
        val worker = shadowOf(handler.looper)
        worker.pause()
        try {
            service.onStartCommand(Intent().setAction(RideMotionService.ACTION_STOP), 0, 1)
            worker.idle()
            assertFalse(journal.approved(scope))
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally { controller.destroy(); worker.idle(); api.clearConfiguration() }
    }
    @Test fun graceExpiryStopsSensorsReleasesWakeLockAndRetainsThePartialBatchWithoutUploading() {
        val gate = RemoteTrackingGate.shared
        gate.connected("synthetic-segment")
        val window = gate.observe("synthetic-segment", "synthetic-ride", "synthetic-log")!!
        val controller = Robolectric.buildService(RideMotionService::class.java).create()
        val service = controller.get()
        fun set(name: String, value: Any) {
            RideMotionService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
        val connection = ApiConnection("https://api.example.test", "synthetic", "destination", "synthetic-server")
        val recording = batch().apply { add("accelerometer", 1000, floatArrayOf(1f, 2f, 3f), 3) }
        val wake = service.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "synthetic:motion").apply { acquire(30_000) }
        set("window", window); set("connection", connection); set("batch", recording); set("wakeLock", wake)
        val field = RideMotionService::class.java.getDeclaredField("handler").apply { isAccessible = true }
        val handler = field.get(service) as Handler
        val worker = shadowOf(handler.looper); worker.pause()
        try {
            gate.disconnected("synthetic-segment")
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(5))
            handler.post { service.onSensorChanged(org.robolectric.shadow.api.Shadow.newInstanceOf(android.hardware.SensorEvent::class.java)) }
            worker.idle()
            assertFalse(wake.isHeld)
            val journal = MotionStore.get(service)
            val pending = journal.pending(connection.deliveryScope)
            assertEquals(1, pending.size)
            assertEquals(1, JSONObject(pending.single().payload).getJSONArray("samples").length())
            // No credential lookup or transport is allowed without a remote connection.
            assertTrue(MotionDelivery.deliver(service))
            assertEquals(pending, journal.pending(connection.deliveryScope))
        } finally { controller.destroy(); worker.idle(); gate.endRide("synthetic-ride") }
    }

    @Test fun motionOnlyDiscoveryIsIndependentAndRejectsUnknownVersions() {
        val json = JSONObject("""{"contract_version":1,"server_id":"synthetic-server","capabilities":{
            "motion":{"schema_version":1,"max_batch_samples":4096,"max_upload_bytes":1048576}}}""")
        assertNotNull(parseCapabilities(json).motion)
        assertNull(parseCapabilities(json).realtime)
        assertEquals("https://api.example.test/prefix/v1/motion/batches", apiUrls("https://api.example.test/prefix").motionBatches().toString())
        json.getJSONObject("capabilities").put("metrics", JSONObject().put("schema_version", 1))
        json.getJSONObject("capabilities").getJSONObject("motion").put("schema_version", 2)
        assertNull(parseCapabilities(json).motion)
        assertNotNull(parseCapabilities(json).metrics)
    }
    @Test fun foregroundPromotionIsImmediateAndSensorWorkUsesAnotherLooper() {
        val controller = Robolectric.buildService(RideMotionService::class.java).create()
        val service = controller.get()
        val field = RideMotionService::class.java.getDeclaredField("handler").apply { isAccessible = true }
        val handler = field.get(service) as Handler
        val worker = shadowOf(handler.looper)
        worker.pause()
        try {
            assertNotSame(Looper.getMainLooper(), handler.looper)
            service.onStartCommand(Intent(), 0, 1)
            val notification = shadowOf(service).lastForegroundNotification
            assertNotNull(notification)
            assertEquals(RideMotionService.ACTION_STOP, shadowOf(notification.actions.single().actionIntent).savedIntent.action)
        } finally { controller.destroy(); worker.idle() }
    }
}

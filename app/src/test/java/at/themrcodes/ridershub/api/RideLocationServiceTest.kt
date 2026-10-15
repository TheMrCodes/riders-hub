package at.themrcodes.ridershub.api

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class RideLocationServiceTest {
    @Test fun normalGpsStopDoesNotDelayReactivationForThirtySeconds() {
        val controller = Robolectric.buildService(RideLocationService::class.java).create()
        val service = controller.get()
        val handler = RideLocationService::class.java.getDeclaredField("handler")
            .apply { isAccessible = true }.get(service) as Handler
        val worker = shadowOf(handler.looper)
        worker.pause()
        try {
            handler.post {
                RideLocationService::class.java.getDeclaredMethod("stopLocation", String::class.java, java.lang.Long.TYPE)
                    .apply { isAccessible = true }.invoke(service, "Remote disconnected", 0L)
            }
            worker.idle()
            val next = RideLocationService::class.java.getDeclaredField("nextStartAt")
                .apply { isAccessible = true }.getLong(null)
            assertTrue(next <= android.os.SystemClock.elapsedRealtime())
        } finally { controller.destroy(); worker.idle() }
    }
    @Test fun startupPromotesImmediatelyButPersistenceAndStopRunOnWorker() {
        val controller = Robolectric.buildService(RideLocationService::class.java).create()
        val service = controller.get()
        val field = RideLocationService::class.java.getDeclaredField("handler").apply { isAccessible = true }
        val handler = field.get(service) as Handler
        val background = shadowOf(handler.looper)
        background.pause()
        try {
            assertNotSame(Looper.getMainLooper(), handler.looper)
            val preferences = service.getSharedPreferences("ride_location", Context.MODE_PRIVATE)
            preferences.edit().putString("active_log", "synthetic-ride").commit()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 1))
            assertNotNull(shadowOf(service).lastForegroundNotification)
            // Stop is queued too: no preference/database processing may run on this caller.
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(
                Intent().setAction("at.themrcodes.ridershub.api.STOP_LOCATION"), 0, 2))
            assertNull(preferences.getString("suppressed_ride", null))
            // Cancel queued startup, then exercise stop alone against a retained log path.
            handler.removeCallbacksAndMessages(null)
            service.onStartCommand(Intent().setAction("at.themrcodes.ridershub.api.STOP_LOCATION"), 0, 3)
            background.idle()
            assertEquals("synthetic-ride", preferences.getString("suppressed_ride", null))
            assertNull(preferences.getString("active_log", null))
        } finally {
            controller.destroy()
            background.idle()
        }
    }
}

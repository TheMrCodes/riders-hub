package at.themrcodes.ridershub.charger

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class ChargerRecoveryTest {
    @Test fun missingConnectionCallbackTimesOutAndStaleCallbacksCannotReviveIt() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val device = app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("02:00:00:00:00:01")
        val store = ChargerStore(app)
        val token = store.beginAppearance()
        val session = ShellyChargerSession(app, device.address, store, true, Handler(Looper.getMainLooper())) { }
        try {
            session.start()
            val old = shadowOf(device).bluetoothGatts.single()
            val callback = shadowOf(old).gattCallback
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertTrue(shadowOf(old).isClosed)
            assertFalse(store.snapshot().gattConnected)
            assertTrue(session.userDecision(true, token).isFailure)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(2, shadowOf(device).bluetoothGatts.size)
            callback.onConnectionStateChange(old, 0, BluetoothProfile.STATE_CONNECTED)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(store.snapshot().gattConnected)
            val current = shadowOf(device).bluetoothGatts.last()
            shadowOf(current).gattCallback.onConnectionStateChange(current, 8, BluetoothProfile.STATE_DISCONNECTED)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(current).isClosed)
            session.stop("synthetic_test")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
            assertEquals(2, shadowOf(device).bluetoothGatts.size)
        } finally { session.stop("synthetic_test") }
    }

    @Test fun verifiedOffDisconnectEndsMonitoringInsteadOfRetrying() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val device = app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("02:00:00:00:00:02")
        val store = ChargerStore(app)
        store.setAssociation(7, "Synthetic charger")
        store.beginAppearance()
        var ended = 0
        val session = ShellyChargerSession(app, device.address, store, false, Handler(Looper.getMainLooper()),
            onMonitoringStopped = { ended++ }) { }
        try {
            session.start()
            ShellyChargerSession::class.java.getDeclaredMethod("parseStatus", org.json.JSONObject::class.java)
                .apply { isAccessible = true }.invoke(session, org.json.JSONObject().put("output", false).put("apower", 0))
            val gatt = shadowOf(device).bluetoothGatts.single()
            shadowOf(gatt).gattCallback.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_DISCONNECTED)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
            assertEquals(1, ended)
            assertEquals(1, shadowOf(device).bluetoothGatts.size)
            assertTrue(shadowOf(gatt).isClosed)
            assertEquals(ChargerPhase.ABSENT, store.snapshot().phase)
            assertNull(store.snapshot().error)
            assertNull(store.snapshot().appearanceToken)
        } finally { session.stop("synthetic_test") }
    }

    @Test fun anUncertainOnCommandCannotReuseAnOlderOffReadback() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val device = app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("02:00:00:00:00:04")
        val store = ChargerStore(app)
        val token = store.beginAppearance()
        var ended = 0
        val session = ShellyChargerSession(app, device.address, store, false, Handler(Looper.getMainLooper()),
            onMonitoringStopped = { ended++ }) { }
        try {
            session.start()
            ShellyChargerSession::class.java.getDeclaredMethod("parseStatus", org.json.JSONObject::class.java)
                .apply { isAccessible = true }.invoke(session, org.json.JSONObject().put("output", false).put("apower", 0))
            ShellyChargerSession::class.java.getDeclaredField("verifiedDevice")
                .apply { isAccessible = true }.setBoolean(session, true)
            // Missing RPC characteristic simulates failure after the ON intent was accepted.
            session.userDecision(true, token)
            assertEquals(0, ended)
            assertEquals(ChargerPhase.CONNECTING, store.snapshot().phase)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(2, shadowOf(device).bluetoothGatts.size)
        } finally { session.stop("synthetic_test") }
    }

    @Test fun unreachableChargerHasABoundedRecoveryWindow() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val device = app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("02:00:00:00:00:03")
        val store = ChargerStore(app)
        store.beginAppearance()
        var ended = 0
        val session = ShellyChargerSession(app, device.address, store, true, Handler(Looper.getMainLooper()),
            onMonitoringStopped = { ended++ }) { }
        try {
            session.start()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(149))
            assertEquals(0, ended)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(1, ended)
            assertTrue(store.snapshot().status.contains("Output state unknown"))
            assertNull(store.snapshot().error)
            val attempts = shadowOf(device).bluetoothGatts.size
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
            assertEquals(attempts, shadowOf(device).bluetoothGatts.size)
            assertEquals(1, ended)
        } finally { session.stop("synthetic_test") }
    }

    @Test fun aPausedMonitorRequiresFreshPresenceInsteadOfACompanionRebind() {
        val app = RuntimeEnvironment.getApplication()
        val preferences = app.getSharedPreferences("charger_monitor", Application.MODE_PRIVATE)
        preferences.edit().putInt("association_id", 7).apply()
        val controller = Robolectric.buildService(ChargerMonitorService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(
                android.content.Intent().setAction(ChargerMonitorService.ACTION_STOP_MONITOR), 0, 1))
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertFalse(ChargerMonitorService.canResume(preferences, 7, false))
            assertFalse(ChargerMonitorService.canResume(preferences, -1, false))
            assertTrue(ChargerMonitorService.canResume(preferences, 8, false))
            assertTrue(ChargerMonitorService.canResume(preferences, 7, true))
            assertTrue(ChargerMonitorService.canResume(preferences, 7, false))
        } finally { controller.destroy() }
    }

    @Test fun retryDelayIsCappedAndResetsAfterValidConnection() {
        val policy = ChargerReconnectPolicy()
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L),
            List(7) { policy.nextDelayMs() })
        policy.connected()
        assertEquals(2000L, policy.nextDelayMs())
    }

    @Test fun ongoingNotificationStopsMonitoringWithoutSendingRelayCommand() {
        val controller = Robolectric.buildService(ChargerMonitorService::class.java).create()
        try {
            val notification = controller.get().notification()
            assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
            val action = notification.actions.single()
            assertEquals("Stop monitoring", action.title.toString())
            assertEquals(ChargerMonitorService.ACTION_STOP_MONITOR, shadowOf(action.actionIntent).savedIntent.action)
            assertTrue(action.actionIntent.isImmutable)
        } finally { controller.destroy() }
    }
}

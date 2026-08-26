package at.themrcodes.ridershub

import android.Manifest
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

abstract class BackfireCompanionServiceBase : CompanionDeviceService() {
    private lateinit var state: AppStateStore
    private val handler = Handler(Looper.getMainLooper())
    private var session: BleTelemetrySession? = null
    private var presentAddress: String? = null
    private var blePresent = false
    private var bluetoothConnected = false
    private var gattActive = false
    private val limiterCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != LimiterControl.ACTION_SET_CHILD_LIMITER ||
                !intent.hasExtra(LimiterControl.EXTRA_ENABLED)
            ) return
            val enabled = intent.getBooleanExtra(LimiterControl.EXTRA_ENABLED, false)
            val result = session?.setChildLimiter(enabled)
                ?: Result.failure(IllegalStateException("Remote telemetry is not connected"))
            result.onFailure { error ->
                state.setLimiterCommandStatus(error.message ?: "Limiter command failed")
            }
        }
    }
    private val stopIfAbsent = Runnable {
        if (!blePresent && !bluetoothConnected && !gattActive) {
            stopSession("all_companion_transports_absent")
        }
    }

    override fun onCreate() {
        super.onCreate()
        state = AppStateStore(this)
        state.setServiceActive(true)
        registerReceiver(
            limiterCommandReceiver,
            IntentFilter(LimiterControl.ACTION_SET_CHILD_LIMITER),
            RECEIVER_NOT_EXPORTED,
        )
        resumeFromSystemBinding()
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopIfAbsent)
        stopSession("companion_service_destroyed")
        state.setPresence(false, "Companion monitor stopped; waiting for Android to restart it")
        state.setServiceActive(false)
        unregisterReceiver(limiterCommandReceiver)
        super.onDestroy()
    }

    protected fun associationForId(associationId: Int): BackfireAssociation? {
        val systemAssociation = getSystemService(CompanionDeviceManager::class.java)
            .myAssociations
            .firstOrNull { it.id == associationId }
            ?.let(::validatedAssociation)
        if (systemAssociation != null) return systemAssociation

        val snapshot = state.snapshot()
        val rawAddress = snapshot.address ?: return null
        val address = runCatching { canonicalBluetoothAddress(rawAddress) }.getOrElse { error ->
            reportAssociationError(error)
            return null
        }
        return BackfireAssociation(
            associationId = associationId,
            address = address,
            displayName = snapshot.name,
        )
    }

    protected fun validatedAssociation(info: AssociationInfo): BackfireAssociation? =
        runCatching { info.toBackfireAssociation() }
            .onFailure(::reportAssociationError)
            .getOrNull()

    protected fun handleBleAppeared(association: BackfireAssociation, source: String) {
        blePresent = true
        handleAvailable(
            association.address,
            association.displayName,
            source,
            association.associationId,
        )
    }

    protected fun handleBleDisappeared(association: BackfireAssociation, source: String) {
        blePresent = false
        handleTransportLost(association.address, source)
    }

    protected fun handleBluetoothConnected(association: BackfireAssociation, source: String) {
        bluetoothConnected = true
        handleAvailable(
            association.address,
            association.displayName,
            source,
            association.associationId,
        )
    }

    protected fun handleBluetoothDisconnected(association: BackfireAssociation, source: String) {
        bluetoothConnected = false
        handleTransportLost(association.address, source)
    }

    private fun reportAssociationError(error: Throwable) {
        state.setError(error.message ?: "Companion association has an invalid Bluetooth address")
    }

    private fun handleAvailable(address: String, name: String?, source: String, associationId: Int) {
        handler.removeCallbacks(stopIfAbsent)
        state.setAssociation(address, name, associationId)
        state.setPresence(true, "$source for association $associationId")
        state.clearError()
        if (presentAddress == address && session != null) return
        stopSession("different_companion_appeared")
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            state.setError("Bluetooth permission was revoked; open the app to restore it")
            return
        }
        presentAddress = address
        session = try {
            BleTelemetrySession(this, address, name, state) { connected ->
                handler.post {
                    gattActive = connected
                    if (connected) {
                        handler.removeCallbacks(stopIfAbsent)
                        state.setPresence(true, "BLE GATT link active")
                    } else {
                        scheduleStopIfFullyAbsent()
                    }
                }
            }.also { it.start() }
        } catch (error: Exception) {
            state.setError("Could not create telemetry session: ${error.message ?: error.javaClass.simpleName}")
            presentAddress = null
            null
        }
    }

    /**
     * Android can rebind an already-present companion after a package update or process restart
     * without replaying the earlier appearance event. Only the system can create this bound
     * service, so the binding itself is sufficient to resume the app's preferred association.
     */
    private fun resumeFromSystemBinding() {
        val associations = getSystemService(CompanionDeviceManager::class.java).myAssociations
        val preferredAssociationId = state.snapshot().associationId
        val association = associations.firstOrNull { it.id == preferredAssociationId }
            ?: associations.singleOrNull()
            ?: return
        val rawAddress = association.deviceMacAddress?.toString() ?: return
        val address = runCatching { canonicalBluetoothAddress(rawAddress) }.getOrElse { error ->
            state.setError(error.message ?: "Companion association has an invalid Bluetooth address")
            return
        }
        handleAvailable(
            address = address,
            name = association.displayName?.toString(),
            source = "Companion service rebound while association is present",
            associationId = association.id,
        )
    }

    private fun handleTransportLost(address: String, source: String) {
        val stillAvailable = blePresent || bluetoothConnected || gattActive
        state.setPresence(
            stillAvailable,
            if (stillAvailable) "$source; another transport remains active" else "$address is no longer present",
        )
        scheduleStopIfFullyAbsent()
    }

    private fun scheduleStopIfFullyAbsent() {
        handler.removeCallbacks(stopIfAbsent)
        if (!blePresent && !bluetoothConnected && !gattActive) {
            handler.postDelayed(stopIfAbsent, TRANSPORT_SETTLE_MS)
        }
    }

    private fun stopSession(reason: String) {
        session?.stop(reason)
        session = null
        presentAddress = null
        gattActive = false
        if (!blePresent && !bluetoothConnected) {
            state.setPresence(false, "Remote absent; ride kept open for two-minute reconnect grace")
        }
    }

    companion object {
        private const val TRANSPORT_SETTLE_MS = 12_000L
    }
}

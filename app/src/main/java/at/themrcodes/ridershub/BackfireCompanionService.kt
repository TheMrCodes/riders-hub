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
import at.themrcodes.ridershub.charger.ChargerControl
import at.themrcodes.ridershub.charger.ChargerStore
import at.themrcodes.ridershub.charger.ShellyChargerSession

abstract class BackfireCompanionServiceBase : CompanionDeviceService() {
    private lateinit var state: AppStateStore
    private lateinit var roles: CompanionRoleStore
    private lateinit var chargerState: ChargerStore
    private val handler = Handler(Looper.getMainLooper())
    private var session: BleTelemetrySession? = null
    private var presentAddress: String? = null
    private var blePresent = false
    private var bluetoothConnected = false
    private var gattActive = false
    private var chargerSession: ShellyChargerSession? = null
    private var chargerAddress: String? = null
    private var chargerPresent = false
    private var chargerGattActive = false
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
    private val chargerCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action !in setOf(ChargerControl.ACTION_START, ChargerControl.ACTION_STOP) ||
                !intent.getBooleanExtra(ChargerControl.EXTRA_CONFIRMED, false)
            ) return
            val token = intent.getStringExtra(ChargerControl.EXTRA_APPEARANCE_TOKEN).orEmpty()
            val start = intent.action == ChargerControl.ACTION_START
            val result = chargerSession?.userDecision(start, token)
                ?: Result.failure(IllegalStateException("Charger RPC is not connected"))
            result.onFailure { error ->
                chargerState.update(
                    at.themrcodes.ridershub.charger.ChargerPhase.ERROR,
                    error.message ?: "Charger command failed",
                    error = error.message ?: "Charger command failed",
                )
            }
        }
    }
    private val stopIfAbsent = Runnable {
        if (!blePresent && !bluetoothConnected && !gattActive) {
            stopSession("all_companion_transports_absent")
        }
    }
    private val stopChargerIfAbsent = Runnable {
        if (!chargerPresent && !chargerGattActive) stopChargerSession("charger_absent")
    }

    override fun onCreate() {
        super.onCreate()
        state = AppStateStore(this)
        roles = CompanionRoleStore(this).also { it.migrateBoardAssociation(state.snapshot().associationId) }
        chargerState = ChargerStore(this)
        state.setServiceActive(true)
        registerReceiver(
            limiterCommandReceiver,
            IntentFilter(LimiterControl.ACTION_SET_CHILD_LIMITER),
            RECEIVER_NOT_EXPORTED,
        )
        registerReceiver(
            chargerCommandReceiver,
            IntentFilter().apply {
                addAction(ChargerControl.ACTION_START)
                addAction(ChargerControl.ACTION_STOP)
            },
            RECEIVER_NOT_EXPORTED,
        )
        resumeFromSystemBinding()
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopIfAbsent)
        handler.removeCallbacks(stopChargerIfAbsent)
        stopChargerSession("companion_service_destroyed")
        stopSession("companion_service_destroyed")
        state.setPresence(false, "Companion monitor stopped; waiting for Android to restart it")
        state.setServiceActive(false)
        unregisterReceiver(limiterCommandReceiver)
        unregisterReceiver(chargerCommandReceiver)
        super.onDestroy()
    }

    protected fun associationForId(associationId: Int): BackfireAssociation? {
        val systemAssociation = getSystemService(CompanionDeviceManager::class.java)
            .myAssociations
            .firstOrNull { it.id == associationId }
            ?.let(::validatedAssociation)
        if (systemAssociation != null) return systemAssociation

        // Only the legacy board path has enough persisted identity for a safe fallback.
        if (roles.roleFor(associationId) != CompanionRole.BOARD_REMOTE) return null

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
        if (roleFor(association) == CompanionRole.CHARGER_PLUG) {
            handleChargerAvailable(association, fresh = true)
            return
        }
        if (roleFor(association) != CompanionRole.BOARD_REMOTE) return
        blePresent = true
        handleAvailable(
            association.address,
            association.displayName,
            source,
            association.associationId,
        )
    }

    protected fun handleBleDisappeared(association: BackfireAssociation, source: String) {
        if (roleFor(association) == CompanionRole.CHARGER_PLUG) {
            chargerPresent = false
            handler.removeCallbacks(stopChargerIfAbsent)
            if (!chargerGattActive) handler.postDelayed(stopChargerIfAbsent, TRANSPORT_SETTLE_MS)
            return
        }
        if (roleFor(association) != CompanionRole.BOARD_REMOTE) return
        blePresent = false
        handleTransportLost(association.address, source)
    }

    protected fun handleBluetoothConnected(association: BackfireAssociation, source: String) {
        if (roleFor(association) == CompanionRole.CHARGER_PLUG) {
            handleChargerAvailable(association, fresh = true)
            return
        }
        if (roleFor(association) != CompanionRole.BOARD_REMOTE) return
        bluetoothConnected = true
        handleAvailable(
            association.address,
            association.displayName,
            source,
            association.associationId,
        )
    }

    protected fun handleBluetoothDisconnected(association: BackfireAssociation, source: String) {
        if (roleFor(association) == CompanionRole.CHARGER_PLUG) {
            chargerPresent = false
            if (!chargerGattActive) handler.postDelayed(stopChargerIfAbsent, TRANSPORT_SETTLE_MS)
            return
        }
        if (roleFor(association) != CompanionRole.BOARD_REMOTE) return
        bluetoothConnected = false
        handleTransportLost(association.address, source)
    }

    private fun reportAssociationError(error: Throwable) {
        state.setError(error.message ?: "Companion association has an invalid Bluetooth address")
    }

    private fun roleFor(association: BackfireAssociation): CompanionRole? {
        val selectedIds = CompanionRole.entries.mapNotNull { role ->
            roles.associationId(role)?.let { role to it }
        }.toMap()
        val associations = getSystemService(CompanionDeviceManager::class.java)
            .myAssociations
            .mapNotNull { runCatching { it.toBackfireAssociation() }.getOrNull() }
        return resolveCompanionRole(association, selectedIds, associations)
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
            BleTelemetrySession(
                context = this,
                deviceAddress = address,
                deviceName = name,
                state = state,
                onGattActiveChanged = { connected ->
                    handler.post {
                        gattActive = connected
                        if (connected) {
                            handler.removeCallbacks(stopIfAbsent)
                            state.setPresence(true, "BLE GATT link active")
                        } else {
                            scheduleStopIfFullyAbsent()
                        }
                    }
                },
                onTelemetryInterrupted = { interrupted, reason ->
                    handler.post { handleTelemetryInterrupted(interrupted, reason) }
                },
            ).also { it.start() }
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
        association?.let { resumeBoard(it) }
        val chargerAssociationId = roles.associationId(CompanionRole.CHARGER_PLUG)
        associations.firstOrNull { it.id == chargerAssociationId }
            ?.let(::resumeCharger)
    }

    private fun resumeBoard(association: AssociationInfo) {
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

    /**
     * A service rebind does not replay an earlier BLE-appeared callback. Probe only the selected
     * charger and let a successful GATT connection provide authoritative reachability evidence.
     */
    private fun resumeCharger(info: AssociationInfo) {
        val association = validatedAssociation(info) ?: return
        handleChargerAvailable(association, fresh = false, presenceObserved = false)
    }

    private fun handleChargerAvailable(
        association: BackfireAssociation,
        fresh: Boolean,
        presenceObserved: Boolean = true,
    ) {
        handler.removeCallbacks(stopChargerIfAbsent)
        if (presenceObserved) chargerPresent = true
        chargerState.setAssociation(association.associationId, association.displayName)
        if (chargerAddress == association.address && chargerSession != null) return
        stopChargerSession("different_charger_appeared")
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            chargerState.update(at.themrcodes.ridershub.charger.ChargerPhase.ERROR, "Bluetooth permission was revoked", error = "Bluetooth permission was revoked")
            return
        }
        chargerState.beginAppearance(presenceObserved)
        chargerAddress = association.address
        chargerSession = runCatching {
            ShellyChargerSession(this, association.address, chargerState, fresh) { active ->
                handler.post {
                    chargerGattActive = active
                    if (!active && !chargerPresent) handler.postDelayed(stopChargerIfAbsent, TRANSPORT_SETTLE_MS)
                }
            }.also { it.start() }
        }.getOrElse { error ->
            chargerState.update(at.themrcodes.ridershub.charger.ChargerPhase.ERROR, "Could not connect to charger", error = error.message ?: "Charger connection failed")
            chargerAddress = null
            null
        }
    }

    private fun stopChargerSession(reason: String) {
        chargerSession?.stop(reason)
        chargerSession = null
        chargerAddress = null
        chargerGattActive = false
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

    private fun handleTelemetryInterrupted(
        interrupted: BleTelemetrySession,
        reason: String,
    ) {
        if (session !== interrupted) return
        val address = presentAddress ?: state.snapshot().address
        val name = state.snapshot().name
        val retry = interrupted.hasUsableBoardTelemetry
        stopSession(reason)
        if (retry && address != null) {
            handler.postDelayed(
                {
                    if (session == null) {
                        handleAvailable(
                            address = address,
                            name = name,
                            source = "Telemetry recovery",
                            associationId = state.snapshot().associationId ?: return@postDelayed,
                        )
                    }
                },
                TELEMETRY_RECOVERY_DELAY_MS,
            )
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
        private const val TELEMETRY_RECOVERY_DELAY_MS = 2_000L
    }
}

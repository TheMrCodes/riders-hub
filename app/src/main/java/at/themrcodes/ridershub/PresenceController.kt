package at.themrcodes.ridershub

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import java.util.regex.Pattern
import at.themrcodes.ridershub.charger.ChargerStore

data class BackfireAssociation(
    val associationId: Int,
    val address: String,
    val displayName: String?,
)

class PresenceController(private val context: Context) {
    private val manager = context.getSystemService(CompanionDeviceManager::class.java)
    private val state = AppStateStore(context)
    private val roles = CompanionRoleStore(context)
    private val chargerState = ChargerStore(context)

    fun existingAssociations(): List<BackfireAssociation> =
        manager.myAssociations.mapNotNull { it.toBackfireAssociation() }

    fun startAssociation(
        role: CompanionRole,
        launchChooser: (IntentSender) -> Unit,
        onCreated: (BackfireAssociation) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile(when (role) {
                CompanionRole.BOARD_REMOTE -> "^BF_.*"
                CompanionRole.CHARGER_PLUG -> "(?i)^shellyplusplugs-.*"
            }))
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(chooserLauncher: IntentSender) {
                launchChooser(chooserLauncher)
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                associationInfo.toBackfireAssociation()?.also { roles.set(role, it.associationId) }?.let(onCreated)
                    ?: onFailure("Android created an association without a BLE address")
            }

            override fun onFailure(error: CharSequence?) {
                onFailure(error?.toString() ?: "Companion-device association failed")
            }
        }
        manager.associate(request, context.mainExecutor, callback)
    }

    fun recoverAssociationAfterChooser(role: CompanionRole, previousIds: Set<Int>): BackfireAssociation? {
        val association = existingAssociations().singleOrNull { it.associationId !in previousIds } ?: return null
        roles.set(role, association.associationId)
        return association
    }

    fun ensureObserving(association: BackfireAssociation, role: CompanionRole): Result<Unit> = runCatching {
        val detail = if (Build.VERSION.SDK_INT >= API_36) {
            PresenceObservationApi36.start(manager, association.associationId)
            "Android 16 is watching association ${association.associationId}"
        } else {
            val refreshed = startLegacyPresenceObservation(manager, association.address)
            if (refreshed) "Android 14/15 refreshed presence observation"
            else "Android 14/15 is watching ${association.address}"
        }
        if (role == CompanionRole.BOARD_REMOTE) {
            state.setObserving(true, detail)
            clearStalePresenceDetail(association)
        } else chargerState.setAssociation(association.associationId, association.displayName)
    }.recoverCatching { error ->
        if (error is IllegalStateException && error.message.orEmpty().contains("already", ignoreCase = true)) {
            if (role == CompanionRole.BOARD_REMOTE) {
                state.setObserving(true, "Android presence observation was already registered")
                clearStalePresenceDetail(association)
            } else chargerState.setAssociation(association.associationId, association.displayName)
        } else {
            if (role == CompanionRole.BOARD_REMOTE) {
                state.setObserving(false, error.message ?: error.javaClass.simpleName)
            } else {
                chargerState.update(
                    at.themrcodes.ridershub.charger.ChargerPhase.ERROR,
                    "Could not watch charger presence",
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
            throw error
        }
    }

    fun association(role: CompanionRole): BackfireAssociation? {
        roles.migrateBoardAssociation(state.snapshot().associationId)
        val id = roles.associationId(role) ?: return null
        return existingAssociations().firstOrNull { it.associationId == id }
    }

    private fun clearStalePresenceDetail(association: BackfireAssociation) {
        val snapshot = state.snapshot()
        if (!snapshot.present && (
                snapshot.presenceDetail.contains("legacy_presence_callback") ||
                    snapshot.presenceDetail.contains("Waiting for Android 16 presence event")
                )
        ) {
            val platform = if (Build.VERSION.SDK_INT >= API_36) {
                "Android 16 association ${association.associationId}"
            } else {
                "Android 14/15 address ${association.address}"
            }
            state.setPresence(
                false,
                "Waiting for $platform presence event",
            )
        }
    }

    companion object {
        internal const val API_36 = 36
    }
}

@Suppress("DEPRECATION")
private fun startLegacyPresenceObservation(
    manager: CompanionDeviceManager,
    address: String,
): Boolean = try {
    manager.startObservingDevicePresence(address)
    false
} catch (error: IllegalStateException) {
    if (!error.message.orEmpty().contains("already", ignoreCase = true)) throw error
    runCatching { manager.stopObservingDevicePresence(address) }
    manager.startObservingDevicePresence(address)
    true
}

internal fun presenceRegistrationModeForSdk(sdkInt: Int): PresenceRegistrationMode =
    if (sdkInt >= PresenceController.API_36) {
        PresenceRegistrationMode.ASSOCIATION_ID
    } else {
        PresenceRegistrationMode.BLE_ADDRESS
    }

internal enum class PresenceRegistrationMode {
    BLE_ADDRESS,
    ASSOCIATION_ID,
}

internal fun AssociationInfo.toBackfireAssociation(): BackfireAssociation? {
    val address = deviceMacAddress?.toString() ?: return null
    return BackfireAssociation(
        associationId = id,
        address = canonicalBluetoothAddress(address),
        displayName = displayName?.toString(),
    )
}

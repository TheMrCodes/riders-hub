package at.themrcodes.ridershub

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import java.util.regex.Pattern

data class BackfireAssociation(
    val associationId: Int,
    val address: String,
    val displayName: String?,
)

class PresenceController(private val context: Context) {
    private val manager = context.getSystemService(CompanionDeviceManager::class.java)
    private val state = AppStateStore(context)

    fun existingAssociations(): List<BackfireAssociation> =
        manager.myAssociations.mapNotNull { it.toBackfireAssociation() }

    fun startAssociation(
        launchChooser: (IntentSender) -> Unit,
        onCreated: (BackfireAssociation) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("^BF_.*"))
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
                associationInfo.toBackfireAssociation()?.let(onCreated)
                    ?: onFailure("Android created an association without a BLE address")
            }

            override fun onFailure(error: CharSequence?) {
                onFailure(error?.toString() ?: "Companion-device association failed")
            }
        }
        manager.associate(request, context.mainExecutor, callback)
    }

    fun recoverAssociationAfterChooser(): BackfireAssociation? {
        val association = existingAssociations().lastOrNull() ?: return null
        state.setAssociation(association.address, association.displayName, association.associationId)
        return association
    }

    fun ensureObserving(association: BackfireAssociation): Result<Unit> = runCatching {
        val detail = if (Build.VERSION.SDK_INT >= API_36) {
            PresenceObservationApi36.start(manager, association.associationId)
            "Android 16 is watching association ${association.associationId}"
        } else {
            startLegacyPresenceObservation(manager, association.address)
            "Android 14/15 is watching ${association.address}"
        }
        state.setObserving(
            true,
            detail,
        )
        clearStalePresenceDetail(association)
    }.recoverCatching { error ->
        if (error is IllegalStateException && error.message.orEmpty().contains("already", ignoreCase = true)) {
            state.setObserving(true, "Android presence observation was already registered")
            clearStalePresenceDetail(association)
        } else {
            state.setObserving(false, error.message ?: error.javaClass.simpleName)
            throw error
        }
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
) {
    manager.startObservingDevicePresence(address)
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

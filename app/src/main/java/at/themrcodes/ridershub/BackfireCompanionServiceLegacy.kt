package at.themrcodes.ridershub

import android.companion.AssociationInfo

/** Android 14/15 companion callbacks. This class contains no API 36 references. */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class BackfireCompanionServiceLegacy : BackfireCompanionServiceBase() {
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        validatedAssociation(associationInfo)?.let { association ->
            handleBleAppeared(association, "BLE presence appeared (Android 14/15)")
        }
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        validatedAssociation(associationInfo)?.let { association ->
            handleBleDisappeared(association, "BLE presence disappeared (Android 14/15)")
        }
    }
}

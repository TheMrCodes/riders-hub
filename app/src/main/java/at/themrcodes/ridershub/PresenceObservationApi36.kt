package at.themrcodes.ridershub

import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import androidx.annotation.RequiresApi

/** API 36 registration isolated from classes that Android 14/15 must load. */
@RequiresApi(36)
internal object PresenceObservationApi36 {
    fun start(manager: CompanionDeviceManager, associationId: Int) {
        val request = ObservingDevicePresenceRequest.Builder()
            .setAssociationId(associationId)
            .build()
        manager.startObservingDevicePresence(request)
    }
}

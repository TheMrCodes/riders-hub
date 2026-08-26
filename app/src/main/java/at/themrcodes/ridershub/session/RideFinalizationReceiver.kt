package at.themrcodes.ridershub.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RideFinalizationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == RideStore.ACTION_FINALIZE_RIDE) {
            RideStore.get(context).finalizeExpired()
        }
    }
}

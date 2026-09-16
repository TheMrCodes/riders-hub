package at.themrcodes.ridershub.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RideFinalizationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == RideStore.ACTION_FINALIZE_RIDE) {
            val pending = goAsync()
            Thread({
                try { RideStore.get(context).finalizeExpired() }
                finally { pending.finish() }
            }, "riders-hub-ride-finalizer").start()
        }
    }
}

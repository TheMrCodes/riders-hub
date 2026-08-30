package at.themrcodes.ridershub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import at.themrcodes.ridershub.wear.shared.WearConnectionStatus
import at.themrcodes.ridershub.wear.shared.WearTelemetryState
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable

/** ADB-only phone-to-watch Data Layer smoke test; never compiled into release APKs. */
class WearDataLayerTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val dataClient = Wearable.getDataClient(context.applicationContext)
        val clear = intent.getBooleanExtra(EXTRA_CLEAR, false)
        val task: Task<*> = if (clear) {
            dataClient.deleteDataItems(TELEMETRY_URI, DataClient.FILTER_LITERAL)
        } else {
            val state = WearTelemetryState(
                connection = WearConnectionStatus.LIVE,
                updatedAtEpochMs = System.currentTimeMillis(),
                speedKmh = 18.5,
                boardBatteryPercent = 67,
                tripKm = 2.5,
                estimatedRangeKm = 9.5,
                mode = "TEST",
            )
            val request = PutDataRequest.create(WearTelemetryState.DATA_PATH)
                .setData(state.encode())
                .setUrgent()
            dataClient.putDataItem(request)
        }

        task.addOnSuccessListener {
            Log.i(TAG, if (clear) "Data Layer test item cleared" else "Data Layer test item published")
        }.addOnFailureListener { error ->
            Log.e(TAG, "Data Layer test operation failed", error)
        }.addOnCompleteListener {
            pendingResult.finish()
        }
    }

    private companion object {
        const val TAG = "RidersHubWearTest"
        const val EXTRA_CLEAR = "clear"
        val TELEMETRY_URI: Uri = Uri.parse("wear://*${WearTelemetryState.DATA_PATH}")
    }
}

package at.themrcodes.ridershub.wear

import at.themrcodes.ridershub.wear.shared.WearTelemetryState
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

class WearTelemetryListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.dataItem.uri.path != WearTelemetryState.DATA_PATH) return@forEach
            val telemetry = if (event.type == DataEvent.TYPE_DELETED) {
                null
            } else {
                decodeWearTelemetry(event.dataItem.data) ?: return@forEach
            }
            WearTelemetryRepository.accept(this, telemetry)
        }
    }
}

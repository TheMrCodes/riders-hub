package at.themrcodes.ridershub

import android.companion.DevicePresenceEvent
import androidx.annotation.RequiresApi

/** Android 16+ companion callbacks, isolated so API 34/35 never load API 36 types. */
@RequiresApi(36)
class BackfireCompanionService : BackfireCompanionServiceBase() {
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        val association = associationForId(event.associationId) ?: return
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED ->
                handleBleAppeared(association, "BLE appeared")

            DevicePresenceEvent.EVENT_BLE_DISAPPEARED ->
                handleBleDisappeared(association, "BLE disappeared")

            DevicePresenceEvent.EVENT_BT_CONNECTED ->
                handleBluetoothConnected(association, "Bluetooth connected")

            DevicePresenceEvent.EVENT_BT_DISCONNECTED ->
                handleBluetoothDisconnected(association, "Bluetooth disconnected")
        }
    }
}

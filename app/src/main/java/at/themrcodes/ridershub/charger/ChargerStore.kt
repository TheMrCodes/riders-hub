package at.themrcodes.ridershub.charger

import android.content.Context
import java.util.UUID

class ChargerStore(context: Context) {
    private val preferences = context.getSharedPreferences("charger_state", Context.MODE_PRIVATE)

    /** Stores chooser identity locally. The Bluetooth address is deliberately not persisted here. */
    fun setAssociation(associationId: Int, displayName: String?) = edit {
        putBoolean("paired", true)
        putInt("association_id", associationId)
        displayName?.trim()?.takeIf(String::isNotEmpty)?.let {
            putString("associated_device_name", it)
        } ?: remove("associated_device_name")
    }

    fun beginAppearance(presenceObserved: Boolean = true): String {
        val token = UUID.randomUUID().toString()
        edit {
            putBoolean("present", presenceObserved)
            putBoolean("gatt_connected", false)
            putBoolean("rpc_verified", false)
            putString("phase", ChargerPhase.CONNECTING.name)
            putString(
                "status",
                if (presenceObserved) "Charger appeared; verifying Shelly RPC"
                else "Checking the associated charger over BLE",
            )
            putString("appearance_token", token)
            remove("error")
            remove("output_on"); remove("power_w"); remove("peak_w")
            remove("active_s"); remove("completion_s")
        }
        return token
    }

    fun setAbsent(detail: String = "Charger is unplugged") = edit {
        putBoolean("present", false)
        putBoolean("gatt_connected", false)
        putBoolean("rpc_verified", false)
        putString("phase", if (preferences.getBoolean("paired", false)) ChargerPhase.ABSENT.name else ChargerPhase.UNPAIRED.name)
        putString("status", detail)
        remove("error")
        remove("appearance_token")
        remove("output_on")
        remove("power_w")
    }

    fun resetRuntime() = edit {
        putBoolean("present", false)
        putBoolean("gatt_connected", false)
        putBoolean("rpc_verified", false)
        putString("phase", if (preferences.getBoolean("paired", false)) ChargerPhase.ABSENT.name else ChargerPhase.UNPAIRED.name)
        putString("status", "Waiting for a fresh charger appearance")
        remove("appearance_token")
        remove("output_on")
        remove("power_w")
        remove("error")
    }

    fun setGattConnected(connected: Boolean) = edit {
        putBoolean("gatt_connected", connected)
        if (!connected) {
            putBoolean("rpc_verified", false)
            remove("output_on")
            remove("power_w")
        }
    }

    fun setRpcVerified(verified: Boolean) = edit {
        putBoolean("rpc_verified", verified)
        if (!verified) {
            remove("output_on")
            remove("power_w")
        }
    }

    fun update(
        phase: ChargerPhase,
        status: String,
        outputOn: Boolean? = null,
        powerWatts: Double? = null,
        peakWatts: Double? = null,
        activeSeconds: Long? = null,
        completionSeconds: Long? = null,
        error: String? = null,
    ) = edit {
        putString("phase", phase.name); putString("status", status)
        if (outputOn != null) putBoolean("output_on", outputOn)
        if (powerWatts != null && powerWatts.isFinite()) putFloat("power_w", powerWatts.toFloat())
        if (peakWatts != null && peakWatts.isFinite()) putFloat("peak_w", peakWatts.toFloat())
        if (activeSeconds != null) putLong("active_s", activeSeconds)
        if (completionSeconds != null) putLong("completion_s", completionSeconds)
        if (error == null) remove("error") else putString("error", error)
    }

    fun snapshot(): ChargerSnapshot {
        val paired = preferences.getBoolean("paired", false)
        return ChargerSnapshot(
            paired = paired,
            associationId = preferences.takeIf { it.contains("association_id") }
                ?.getInt("association_id", 0),
            associatedDeviceName = preferences.getString("associated_device_name", null),
            present = preferences.getBoolean("present", false),
            gattConnected = preferences.getBoolean("gatt_connected", false),
            rpcVerified = preferences.getBoolean("rpc_verified", false),
            phase = runCatching { ChargerPhase.valueOf(preferences.getString("phase", null).orEmpty()) }
                .getOrElse { if (paired) ChargerPhase.ABSENT else ChargerPhase.UNPAIRED },
            status = preferences.getString("status", if (paired) "Waiting for charger" else "No charger paired").orEmpty(),
            outputOn = preferences.takeIf { it.contains("output_on") }?.getBoolean("output_on", false),
            powerWatts = preferences.takeIf { it.contains("power_w") }?.getFloat("power_w", 0f)?.toDouble(),
            peakWatts = preferences.takeIf { it.contains("peak_w") }?.getFloat("peak_w", 0f)?.toDouble(),
            activeSeconds = preferences.getLong("active_s", 0),
            completionSeconds = preferences.getLong("completion_s", 0),
            appearanceToken = preferences.getString("appearance_token", null),
            error = preferences.getString("error", null),
        )
    }

    private inline fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        preferences.edit().apply(block).putLong("updated_at_ms", System.currentTimeMillis()).apply()
    }
}

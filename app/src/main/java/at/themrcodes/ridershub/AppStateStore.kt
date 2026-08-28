package at.themrcodes.ridershub

import android.content.Context
import android.os.SystemClock
import at.themrcodes.ridershub.protocol.TelemetryFrame
import java.time.Instant
import java.util.Locale

class AppStateStore(context: Context) {
    private val preferences = context.getSharedPreferences("monitor_state", Context.MODE_PRIVATE)
    private val wearPublisher = WearTelemetryPublisher(context.applicationContext)
    private var lastFramePersistElapsedMs = 0L

    fun setAssociation(address: String, name: String?, associationId: Int?) {
        val previousAddress = preferences.getString(KEY_ADDRESS, null)
        preferences.edit()
            .putString(KEY_ADDRESS, address)
            .putString(KEY_NAME, name ?: "Supported remote")
            .apply {
                if (associationId != null) putInt(KEY_ASSOCIATION_ID, associationId)
                if (previousAddress != null && !previousAddress.equals(address, ignoreCase = true)) {
                    remove(KEY_BOARD_BATTERY_PERCENT)
                    remove(KEY_BOARD_BATTERY_CONFIRMED)
                    remove(KEY_ODOMETER_KM)
                }
            }
            .putString(KEY_LAST_EVENT_AT, Instant.now().toString())
            .apply()
        publishWear(force = true)
    }

    fun setObserving(observing: Boolean, detail: String) {
        preferences.edit()
            .putBoolean(KEY_OBSERVING, observing)
            .putString(KEY_MONITOR_DETAIL, detail)
            .putString(KEY_LAST_EVENT_AT, Instant.now().toString())
            .apply()
        publishWear(force = true)
    }

    /**
     * Runtime BLE state is meaningful only while this application process and its companion
     * service are alive. Association and observation registration remain persisted separately.
     */
    fun resetRuntimeState() {
        preferences.edit()
            .putBoolean(KEY_SERVICE_ACTIVE, false)
            .putBoolean(KEY_PRESENT, false)
            .putString(KEY_PRESENCE_DETAIL, "Waiting for a current Android presence event")
            .putString(KEY_CONNECTION, "Idle")
            .putLong(KEY_FRAME_COUNT, 0)
            .putBoolean(KEY_LIMITER_CONTROL_AVAILABLE, false)
            .remove(KEY_LAST_FRAME)
            .remove(KEY_SPEED_KMH)
            .remove(KEY_PACK_VOLTAGE_V)
            .remove(KEY_MODE)
            .remove(KEY_TRIP_KM)
            .remove(KEY_LOAD_RAW)
            .remove(KEY_CRC_VALID)
            .remove(KEY_CHILD_LIMITER_ACTIVE)
            .remove(KEY_LIMITER_COMMAND_STATUS)
            .apply {
                if (!preferences.getBoolean(KEY_BOARD_BATTERY_CONFIRMED, false)) {
                    remove(KEY_BOARD_BATTERY_PERCENT)
                }
            }
            .putString(KEY_LAST_EVENT_AT, Instant.now().toString())
            .apply()
        publishWear(force = true)
    }

    fun setServiceActive(active: Boolean) {
        preferences.edit()
            .putBoolean(KEY_SERVICE_ACTIVE, active)
            .putString(KEY_LAST_EVENT_AT, Instant.now().toString())
            .apply()
        publishWear(force = true)
    }

    fun setPresence(present: Boolean, detail: String) {
        preferences.edit()
            .putBoolean(KEY_PRESENT, present)
            .putString(KEY_PRESENCE_DETAIL, detail)
            .putString(KEY_LAST_EVENT_AT, Instant.now().toString())
            .apply()
        publishWear(force = true)
    }

    fun setConnection(state: String) {
        preferences.edit()
            .putString(KEY_CONNECTION, state)
            .putString(KEY_LAST_EVENT_AT, Instant.now().toString())
            .apply()
        publishWear(force = true)
    }

    fun setLatestLog(path: String) {
        preferences.edit()
            .putString(KEY_LATEST_LOG, path)
            .putLong(KEY_FRAME_COUNT, 0)
            .remove(KEY_LAST_FRAME)
            .remove(KEY_SPEED_KMH)
            .remove(KEY_PACK_VOLTAGE_V)
            .remove(KEY_MODE)
            .remove(KEY_TRIP_KM)
            .remove(KEY_LOAD_RAW)
            .remove(KEY_CRC_VALID)
            .remove(KEY_CHILD_LIMITER_ACTIVE)
            .putString(KEY_LAST_EVENT_AT, Instant.now().toString())
            .apply()
        publishWear(force = true)
    }

    fun setFrame(frameCount: Long, frame: TelemetryFrame) {
        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed - lastFramePersistElapsedMs < FRAME_PERSIST_INTERVAL_MS) return
        lastFramePersistElapsedMs = elapsed
        val boardTelemetryValid = isPlausibleBoardTelemetry(
            batteryPercent = frame.boardBatteryPercent,
            packVoltageV = frame.packVoltageV,
            crcValid = frame.crcValid,
        )
        val summary = "${frame.mode}, ${"%.2f".format(Locale.US, frame.speedKmh)} km/h, " +
            if (boardTelemetryValid) {
                "${frame.boardBatteryPercent}%, CRC ok"
            } else {
                "board unavailable, CRC ${if (frame.crcValid) "ok" else "BAD"}"
            }
        preferences.edit()
            .putLong(KEY_FRAME_COUNT, frameCount)
            .putString(KEY_LAST_FRAME, summary)
            .putFloat(KEY_SPEED_KMH, frame.speedKmh.toFloat())
            .putString(KEY_MODE, frame.mode)
            .putBoolean(KEY_CRC_VALID, frame.crcValid)
            .putBoolean(
                KEY_CHILD_LIMITER_ACTIVE,
                at.themrcodes.ridershub.protocol.BackfireProtocol.isChildLimiterActive(frame.modeCode),
            )
            .apply {
                if (boardTelemetryValid) {
                    putInt(KEY_BOARD_BATTERY_PERCENT, frame.boardBatteryPercent)
                    putBoolean(KEY_BOARD_BATTERY_CONFIRMED, true)
                    putFloat(KEY_PACK_VOLTAGE_V, frame.packVoltageV.toFloat())
                    putFloat(KEY_TRIP_KM, frame.tripKm.toFloat())
                    putFloat(KEY_ODOMETER_KM, frame.odometerKm.toFloat())
                    putInt(KEY_LOAD_RAW, frame.loadRaw)
                } else {
                    remove(KEY_PACK_VOLTAGE_V)
                    remove(KEY_TRIP_KM)
                    remove(KEY_LOAD_RAW)
                }
            }
            .putString(KEY_LAST_EVENT_AT, Instant.now().toString())
            .apply()
        publishWear(force = false)
    }

    fun setLimiterControlAvailable(available: Boolean) {
        preferences.edit()
            .putBoolean(KEY_LIMITER_CONTROL_AVAILABLE, available)
            .putString(KEY_LAST_EVENT_AT, Instant.now().toString())
            .apply()
    }

    fun setLimiterCommandStatus(status: String?) {
        preferences.edit()
            .apply {
                if (status == null) remove(KEY_LIMITER_COMMAND_STATUS)
                else putString(KEY_LIMITER_COMMAND_STATUS, status)
            }
            .putString(KEY_LAST_EVENT_AT, Instant.now().toString())
            .apply()
    }

    fun setError(message: String) {
        preferences.edit()
            .putString(KEY_ERROR, message)
            .putString(KEY_LAST_EVENT_AT, Instant.now().toString())
            .apply()
    }

    fun clearError() {
        preferences.edit().remove(KEY_ERROR).apply()
    }

    fun snapshot(): AppSnapshot = AppSnapshot(
        address = preferences.getString(KEY_ADDRESS, null),
        name = preferences.getString(KEY_NAME, null),
        associationId = preferences.takeIf { it.contains(KEY_ASSOCIATION_ID) }
            ?.getInt(KEY_ASSOCIATION_ID, -1),
        observing = preferences.getBoolean(KEY_OBSERVING, false),
        monitorDetail = preferences.getString(KEY_MONITOR_DETAIL, "Not armed") ?: "Not armed",
        serviceActive = preferences.getBoolean(KEY_SERVICE_ACTIVE, false),
        present = preferences.getBoolean(KEY_PRESENT, false),
        presenceDetail = preferences.getString(KEY_PRESENCE_DETAIL, "Not detected")
            ?: "Not detected",
        connection = preferences.getString(KEY_CONNECTION, "Idle") ?: "Idle",
        latestLog = preferences.getString(KEY_LATEST_LOG, null),
        frameCount = preferences.getLong(KEY_FRAME_COUNT, 0),
        lastFrame = preferences.getString(KEY_LAST_FRAME, null),
        speedKmh = preferences.optionalFloat(KEY_SPEED_KMH),
        boardBatteryPercent = preferences.optionalInt(KEY_BOARD_BATTERY_PERCENT)
            ?.takeIf { preferences.getBoolean(KEY_BOARD_BATTERY_CONFIRMED, false) },
        packVoltageV = preferences.optionalFloat(KEY_PACK_VOLTAGE_V),
        mode = preferences.getString(KEY_MODE, null),
        tripKm = preferences.optionalFloat(KEY_TRIP_KM),
        odometerKm = preferences.optionalFloat(KEY_ODOMETER_KM),
        loadRaw = preferences.optionalInt(KEY_LOAD_RAW),
        crcValid = if (preferences.contains(KEY_CRC_VALID)) {
            preferences.getBoolean(KEY_CRC_VALID, false)
        } else null,
        childLimiterActive = if (preferences.contains(KEY_CHILD_LIMITER_ACTIVE)) {
            preferences.getBoolean(KEY_CHILD_LIMITER_ACTIVE, false)
        } else null,
        limiterControlAvailable = preferences.getBoolean(KEY_LIMITER_CONTROL_AVAILABLE, false),
        limiterCommandStatus = preferences.getString(KEY_LIMITER_COMMAND_STATUS, null),
        error = preferences.getString(KEY_ERROR, null),
        lastEventAt = preferences.getString(KEY_LAST_EVENT_AT, null),
    )

    private fun publishWear(force: Boolean) {
        wearPublisher.publish(snapshot(), force)
    }

    companion object {
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        private const val KEY_ASSOCIATION_ID = "association_id"
        private const val KEY_OBSERVING = "observing"
        private const val KEY_MONITOR_DETAIL = "monitor_detail"
        private const val KEY_SERVICE_ACTIVE = "service_active"
        private const val KEY_PRESENT = "present"
        private const val KEY_PRESENCE_DETAIL = "presence_detail"
        private const val KEY_CONNECTION = "connection"
        private const val KEY_LATEST_LOG = "latest_log"
        private const val KEY_FRAME_COUNT = "frame_count"
        private const val KEY_LAST_FRAME = "last_frame"
        private const val KEY_SPEED_KMH = "speed_kmh"
        private const val KEY_BOARD_BATTERY_PERCENT = "board_battery_percent"
        private const val KEY_BOARD_BATTERY_CONFIRMED = "board_battery_confirmed"
        private const val KEY_PACK_VOLTAGE_V = "pack_voltage_v"
        private const val KEY_MODE = "mode"
        private const val KEY_TRIP_KM = "trip_km"
        private const val KEY_ODOMETER_KM = "odometer_km"
        private const val KEY_LOAD_RAW = "load_raw"
        private const val KEY_CRC_VALID = "crc_valid"
        private const val KEY_CHILD_LIMITER_ACTIVE = "child_limiter_active"
        private const val KEY_LIMITER_CONTROL_AVAILABLE = "limiter_control_available"
        private const val KEY_LIMITER_COMMAND_STATUS = "limiter_command_status"
        private const val KEY_ERROR = "error"
        private const val KEY_LAST_EVENT_AT = "last_event_at"
        private const val FRAME_PERSIST_INTERVAL_MS = 1_000L
    }
}

data class AppSnapshot(
    val address: String?,
    val name: String?,
    val associationId: Int?,
    val observing: Boolean,
    val monitorDetail: String,
    val serviceActive: Boolean,
    val present: Boolean,
    val presenceDetail: String,
    val connection: String,
    val latestLog: String?,
    val frameCount: Long,
    val lastFrame: String?,
    val speedKmh: Float?,
    val boardBatteryPercent: Int?,
    val packVoltageV: Float?,
    val mode: String?,
    val tripKm: Float?,
    val odometerKm: Float?,
    val loadRaw: Int?,
    val crcValid: Boolean?,
    val childLimiterActive: Boolean?,
    val limiterControlAvailable: Boolean,
    val limiterCommandStatus: String?,
    val error: String?,
    val lastEventAt: String?,
)

private fun android.content.SharedPreferences.optionalFloat(key: String): Float? =
    if (contains(key)) getFloat(key, 0f) else null

private fun android.content.SharedPreferences.optionalInt(key: String): Int? =
    if (contains(key)) getInt(key, 0) else null

internal fun isPlausibleBoardTelemetry(
    batteryPercent: Int,
    packVoltageV: Double,
    crcValid: Boolean,
): Boolean = crcValid && batteryPercent in 0..100 && packVoltageV in 10.0..70.0

package at.themrcodes.ridershub.session

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import at.themrcodes.ridershub.log.JsonlSessionLog
import at.themrcodes.ridershub.protocol.TelemetryFrame
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

class RideStore private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var active: StoredRide? = preferences.getString(KEY_ACTIVE, null)
        ?.let(::storedRideFromJson)
        ?.also { recovered ->
            if (recovered.segmentOpen) {
                recovered.segmentOpen = false
                recovered.pendingFinalizeAtMs =
                    (recovered.lastFrameEpochMs ?: recovered.startedAtEpochMs) + SESSION_GRACE_MS
            }
        }
    private var history: MutableList<RideSummary> = readSummaryArray(KEY_HISTORY)
    private var calibrationPoints: MutableList<CalibrationPoint> = readCalibrationPoints()
    private var lastPersistElapsedMs = 0L

    fun openSegment(deviceAddress: String, deviceName: String?): RideSegment = synchronized(LOCK) {
        val now = System.currentTimeMillis()
        finalizeExpiredLocked(now)
        val current = active
        val resumable = SessionContinuity.shouldResume(
            activeDeviceAddress = current?.deviceAddress,
            arrivingDeviceAddress = deviceAddress,
            lastActivityEpochMs = current?.let { it.lastFrameEpochMs ?: it.startedAtEpochMs },
            nowEpochMs = now,
            graceMs = SESSION_GRACE_MS,
        )

        val stored = if (resumable) {
            requireNotNull(current)
        } else {
            if (current != null) finalizeLocked("new_session_after_gap", now)
            newRide(deviceAddress, deviceName, now).also { active = it }
        }
        stored.pendingFinalizeAtMs = null
        stored.segmentOpen = true
        if (resumable) {
            stored.summary = stored.summary.copy(
                segmentCount = stored.summary.segmentCount + 1,
                active = true,
                endedAt = null,
            )
        }
        cancelFinalizationAlarm()
        persistLocked(force = true)

        RideSegment(
            sessionId = stored.summary.id,
            deviceAddress = deviceAddress,
            deviceName = deviceName ?: stored.deviceName,
            logFile = stored.summary.logFile,
            initialSequence = stored.lastSequence,
            segmentNumber = stored.summary.segmentCount,
            newSession = !resumable,
        )
    }

    fun recordFrame(frame: TelemetryFrame, wallTime: Instant = Instant.now()) = synchronized(LOCK) {
        val stored = active ?: return@synchronized
        val now = wallTime.toEpochMilli()
        var summary = stored.summary.copy(
            frameCount = stored.summary.frameCount + 1,
            crcErrorCount = stored.summary.crcErrorCount + if (frame.crcValid) 0 else 1,
            lastFrameAt = wallTime.toString(),
        )

        if (frame.crcValid) {
            var distance = summary.distanceKm
            var movingSeconds = summary.movingSeconds
            val previousAt = stored.lastFrameEpochMs
            val previousSpeed = stored.lastSpeedKmh
            if (previousAt != null && previousSpeed != null) {
                val elapsedMs = now - previousAt
                if (elapsedMs in 1..MAX_DISTANCE_INTEGRATION_GAP_MS) {
                    val elapsedSeconds = elapsedMs / 1000.0
                    distance += ((previousSpeed + frame.speedKmh) / 2.0) * elapsedSeconds / 3600.0
                    if (max(previousSpeed, frame.speedKmh) >= MOVING_SPEED_KMH) {
                        movingSeconds += elapsedSeconds
                    }
                }
            }

            val battery = frame.boardBatteryPercent.takeIf { it in 0..100 && frame.packVoltageV > 10.0 }
            val voltage = frame.packVoltageV.takeIf { it in 10.0..70.0 }
            val restingVoltage = voltage.takeIf { frame.speedKmh <= RESTING_SPEED_KMH }
            summary = summary.copy(
                distanceKm = distance,
                movingSeconds = movingSeconds,
                maxSpeedKmh = max(summary.maxSpeedKmh, frame.speedKmh),
                boardBatteryStart = summary.boardBatteryStart ?: battery,
                boardBatteryEnd = battery ?: summary.boardBatteryEnd,
                boardBatteryMin = minNullable(summary.boardBatteryMin, battery),
                packVoltageStart = summary.packVoltageStart ?: voltage,
                packVoltageEnd = voltage ?: summary.packVoltageEnd,
                packVoltageMin = minNullable(summary.packVoltageMin, voltage),
                packVoltageMax = maxNullable(summary.packVoltageMax, voltage),
                restingVoltageStart = summary.restingVoltageStart ?: restingVoltage,
                restingVoltageEnd = restingVoltage ?: summary.restingVoltageEnd,
                odometerStartKm = summary.odometerStartKm ?: frame.odometerKm,
                odometerEndKm = frame.odometerKm,
                boardFrameCount = summary.boardFrameCount + 1,
                modes = summary.modes + frame.mode,
            )
            stored.lastSpeedKmh = frame.speedKmh
            if (battery != null && restingVoltage != null) {
                maybeAddCalibrationPointLocked(wallTime, battery, restingVoltage, stored)
            }
        }

        stored.summary = summary
        stored.lastFrameEpochMs = now
        persistLocked(force = false)
    }

    fun endSegment(reason: String, lastSequence: Long) = synchronized(LOCK) {
        val stored = active ?: return@synchronized
        stored.lastSequence = max(stored.lastSequence, lastSequence)
        stored.segmentOpen = false
        stored.pendingFinalizeAtMs = System.currentTimeMillis() + SESSION_GRACE_MS
        persistLocked(force = true)
        scheduleFinalizationAlarm(stored.pendingFinalizeAtMs!!)
    }

    fun finalizeExpired(nowEpochMs: Long = System.currentTimeMillis()): RideSummary? = synchronized(LOCK) {
        finalizeExpiredLocked(nowEpochMs)
    }

    fun snapshot(
        currentBatteryPercent: Int?,
        currentRestingVoltageV: Double?,
    ): RideStoreSnapshot = synchronized(LOCK) {
        finalizeExpiredLocked(System.currentTimeMillis())
        val tracks = history.filter { it.isTrack }.sortedByDescending { it.startedAt }
        RideStoreSnapshot(
            activeRide = active?.summary,
            recentTracks = tracks.take(MAX_VISIBLE_TRACKS),
            rangeEstimate = RangeEstimator.estimate(
                currentBatteryPercent = currentBatteryPercent,
                currentRestingVoltageV = currentRestingVoltageV,
                sessions = tracks,
                calibrationPoints = calibrationPoints.toList(),
            ),
            reconnectGraceEndsAt = active?.pendingFinalizeAtMs?.let {
                Instant.ofEpochMilli(it).toString()
            },
        )
    }

    private fun finalizeExpiredLocked(nowEpochMs: Long): RideSummary? {
        val stored = active ?: return null
        val deadline = stored.pendingFinalizeAtMs ?: return null
        return if (nowEpochMs >= deadline) finalizeLocked("disconnect_grace_expired", nowEpochMs) else null
    }

    private fun finalizeLocked(reason: String, nowEpochMs: Long): RideSummary? {
        val stored = active ?: return null
        val endedAt = stored.summary.lastFrameAt ?: Instant.ofEpochMilli(nowEpochMs).toString()
        val completed = stored.summary.copy(endedAt = endedAt, active = false)
        JsonlSessionLog.appendSessionEnd(
            file = File(completed.logFile),
            initialSequence = stored.lastSequence,
            reason = reason,
            summary = completed,
        )
        if (completed.isTrack) {
            history.add(0, completed)
            history = history.distinctBy { it.id }.take(MAX_STORED_TRACKS).toMutableList()
        }
        active = null
        cancelFinalizationAlarm()
        persistLocked(force = true)
        return completed
    }

    private fun maybeAddCalibrationPointLocked(
        wallTime: Instant,
        batteryPercent: Int,
        voltage: Double,
        stored: StoredRide,
    ) {
        val now = wallTime.toEpochMilli()
        val last = calibrationPoints.lastOrNull()
        val changedPercent = last?.batteryPercent != batteryPercent
        if (last == null || changedPercent || now - stored.lastCalibrationAtMs >= CALIBRATION_INTERVAL_MS) {
            calibrationPoints.add(CalibrationPoint(wallTime.toString(), batteryPercent, voltage))
            calibrationPoints = calibrationPoints.takeLast(MAX_CALIBRATION_POINTS).toMutableList()
            stored.lastCalibrationAtMs = now
        }
    }

    private fun newRide(address: String, name: String?, now: Long): StoredRide {
        val id = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(now))
        val identity = listOfNotNull(name, address)
            .joinToString("_")
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val directory = (context.getExternalFilesDir("telemetry")
            ?: File(context.filesDir, "telemetry")).also { it.mkdirs() }
        val path = File(directory, "${id}_${identity}.jsonl").absolutePath
        return StoredRide(
            summary = RideSummary(
                id = id,
                startedAt = Instant.ofEpochMilli(now).toString(),
                endedAt = null,
                lastFrameAt = null,
                distanceKm = 0.0,
                movingSeconds = 0.0,
                maxSpeedKmh = 0.0,
                boardBatteryStart = null,
                boardBatteryEnd = null,
                boardBatteryMin = null,
                packVoltageStart = null,
                packVoltageEnd = null,
                packVoltageMin = null,
                packVoltageMax = null,
                restingVoltageStart = null,
                restingVoltageEnd = null,
                odometerStartKm = null,
                odometerEndKm = null,
                frameCount = 0,
                boardFrameCount = 0,
                crcErrorCount = 0,
                segmentCount = 1,
                modes = emptySet(),
                logFile = path,
                active = true,
            ),
            deviceAddress = address,
            deviceName = name,
            startedAtEpochMs = now,
            segmentOpen = true,
        )
    }

    private fun persistLocked(force: Boolean) {
        val elapsed = SystemClock.elapsedRealtime()
        if (!force && elapsed - lastPersistElapsedMs < PERSIST_INTERVAL_MS) return
        lastPersistElapsedMs = elapsed
        preferences.edit()
            .apply {
                val current = active
                if (current == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, current.toJson().toString())
            }
            .putString(KEY_HISTORY, JSONArray(history.map { it.toJson() }).toString())
            .putString(KEY_CALIBRATION, JSONArray(calibrationPoints.map { it.toJson() }).toString())
            .apply()
    }

    private fun readSummaryArray(key: String): MutableList<RideSummary> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]") ?: "[]")
        MutableList(array.length()) { index -> summaryFromJson(array.getJSONObject(index)) }
    }.getOrDefault(mutableListOf())

    private fun readCalibrationPoints(): MutableList<CalibrationPoint> = runCatching {
        val array = JSONArray(preferences.getString(KEY_CALIBRATION, "[]") ?: "[]")
        MutableList(array.length()) { index ->
            val value = array.getJSONObject(index)
            CalibrationPoint(
                wallTime = value.getString("wall_time"),
                batteryPercent = value.getInt("battery_percent"),
                restingVoltageV = value.getDouble("resting_voltage_v"),
            )
        }
    }.getOrDefault(mutableListOf())

    private fun scheduleFinalizationAlarm(deadlineEpochMs: Long) {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            deadlineEpochMs,
            finalizationIntent(),
        )
    }

    private fun cancelFinalizationAlarm() {
        context.getSystemService(AlarmManager::class.java).cancel(finalizationIntent())
    }

    private fun finalizationIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        FINALIZATION_REQUEST_CODE,
        Intent(context, RideFinalizationReceiver::class.java).setAction(ACTION_FINALIZE_RIDE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun storedRideFromJson(value: String): StoredRide? = runCatching {
        val json = JSONObject(value)
        StoredRide(
            summary = summaryFromJson(json.getJSONObject("summary")),
            deviceAddress = json.getString("device_address"),
            deviceName = json.optNullableString("device_name"),
            startedAtEpochMs = json.getLong("started_at_epoch_ms"),
            lastFrameEpochMs = json.optNullableLong("last_frame_epoch_ms"),
            lastSpeedKmh = json.optNullableDouble("last_speed_kmh"),
            pendingFinalizeAtMs = json.optNullableLong("pending_finalize_at_ms"),
            lastSequence = json.optLong("last_sequence", 0),
            lastCalibrationAtMs = json.optLong("last_calibration_at_ms", 0),
            segmentOpen = json.optBoolean("segment_open", false),
        )
    }.getOrNull()

    private data class StoredRide(
        var summary: RideSummary,
        val deviceAddress: String,
        val deviceName: String?,
        val startedAtEpochMs: Long,
        var lastFrameEpochMs: Long? = null,
        var lastSpeedKmh: Double? = null,
        var pendingFinalizeAtMs: Long? = null,
        var lastSequence: Long = 0,
        var lastCalibrationAtMs: Long = 0,
        var segmentOpen: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("summary", summary.toJson())
            .put("device_address", deviceAddress)
            .putNullable("device_name", deviceName)
            .put("started_at_epoch_ms", startedAtEpochMs)
            .putNullable("last_frame_epoch_ms", lastFrameEpochMs)
            .putNullable("last_speed_kmh", lastSpeedKmh)
            .putNullable("pending_finalize_at_ms", pendingFinalizeAtMs)
            .put("last_sequence", lastSequence)
            .put("last_calibration_at_ms", lastCalibrationAtMs)
            .put("segment_open", segmentOpen)
    }

    companion object {
        const val SESSION_GRACE_MS = 120_000L
        const val ACTION_FINALIZE_RIDE = "at.themrcodes.ridershub.FINALIZE_RIDE"
        private const val PREFERENCES_NAME = "ride_store"
        private const val KEY_ACTIVE = "active_ride"
        private const val KEY_HISTORY = "ride_history"
        private const val KEY_CALIBRATION = "calibration_points"
        private const val MAX_DISTANCE_INTEGRATION_GAP_MS = 2_000L
        private const val MOVING_SPEED_KMH = 1.0
        private const val RESTING_SPEED_KMH = 0.5
        private const val PERSIST_INTERVAL_MS = 5_000L
        private const val CALIBRATION_INTERVAL_MS = 10 * 60_000L
        private const val MAX_CALIBRATION_POINTS = 500
        private const val MAX_STORED_TRACKS = 100
        private const val MAX_VISIBLE_TRACKS = 30
        private const val FINALIZATION_REQUEST_CODE = 8102
        private val LOCK = Any()

        @Volatile
        @android.annotation.SuppressLint("StaticFieldLeak")
        private var instance: RideStore? = null

        fun get(context: Context): RideStore = instance ?: synchronized(LOCK) {
            instance ?: RideStore(context.applicationContext).also { instance = it }
        }
    }
}

private fun RideSummary.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("started_at", startedAt)
    .putNullable("ended_at", endedAt)
    .putNullable("last_frame_at", lastFrameAt)
    .put("distance_km", distanceKm)
    .put("moving_seconds", movingSeconds)
    .put("max_speed_kmh", maxSpeedKmh)
    .putNullable("board_battery_start", boardBatteryStart)
    .putNullable("board_battery_end", boardBatteryEnd)
    .putNullable("board_battery_min", boardBatteryMin)
    .putNullable("pack_voltage_start", packVoltageStart)
    .putNullable("pack_voltage_end", packVoltageEnd)
    .putNullable("pack_voltage_min", packVoltageMin)
    .putNullable("pack_voltage_max", packVoltageMax)
    .putNullable("resting_voltage_start", restingVoltageStart)
    .putNullable("resting_voltage_end", restingVoltageEnd)
    .putNullable("odometer_start_km", odometerStartKm)
    .putNullable("odometer_end_km", odometerEndKm)
    .put("frame_count", frameCount)
    .put("board_frame_count", boardFrameCount)
    .put("crc_error_count", crcErrorCount)
    .put("segment_count", segmentCount)
    .put("modes", JSONArray(modes.toList()))
    .put("log_file", logFile)
    .put("active", active)

private fun summaryFromJson(value: JSONObject): RideSummary = RideSummary(
    id = value.getString("id"),
    startedAt = value.getString("started_at"),
    endedAt = value.optNullableString("ended_at"),
    lastFrameAt = value.optNullableString("last_frame_at"),
    distanceKm = value.optDouble("distance_km", 0.0),
    movingSeconds = value.optDouble("moving_seconds", 0.0),
    maxSpeedKmh = value.optDouble("max_speed_kmh", 0.0),
    boardBatteryStart = value.optNullableInt("board_battery_start"),
    boardBatteryEnd = value.optNullableInt("board_battery_end"),
    boardBatteryMin = value.optNullableInt("board_battery_min"),
    packVoltageStart = value.optNullableDouble("pack_voltage_start"),
    packVoltageEnd = value.optNullableDouble("pack_voltage_end"),
    packVoltageMin = value.optNullableDouble("pack_voltage_min"),
    packVoltageMax = value.optNullableDouble("pack_voltage_max"),
    restingVoltageStart = value.optNullableDouble("resting_voltage_start"),
    restingVoltageEnd = value.optNullableDouble("resting_voltage_end"),
    odometerStartKm = value.optNullableDouble("odometer_start_km"),
    odometerEndKm = value.optNullableDouble("odometer_end_km"),
    frameCount = value.optLong("frame_count", 0),
    boardFrameCount = value.optLong("board_frame_count", 0),
    crcErrorCount = value.optLong("crc_error_count", 0),
    segmentCount = value.optInt("segment_count", 1),
    modes = value.optJSONArray("modes")?.let { array ->
        buildSet { repeat(array.length()) { add(array.getString(it)) } }
    } ?: emptySet(),
    logFile = value.getString("log_file"),
    active = value.optBoolean("active", false),
)

private fun CalibrationPoint.toJson(): JSONObject = JSONObject()
    .put("wall_time", wallTime)
    .put("battery_percent", batteryPercent)
    .put("resting_voltage_v", restingVoltageV)

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
    put(key, value ?: JSONObject.NULL)

private fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key) || !has(key)) null else getString(key)

private fun JSONObject.optNullableInt(key: String): Int? =
    if (isNull(key) || !has(key)) null else getInt(key)

private fun JSONObject.optNullableDouble(key: String): Double? =
    if (isNull(key) || !has(key)) null else getDouble(key)

private fun JSONObject.optNullableLong(key: String): Long? =
    if (isNull(key) || !has(key)) null else getLong(key)

private fun minNullable(first: Int?, second: Int?): Int? = when {
    first == null -> second
    second == null -> first
    else -> min(first, second)
}

private fun minNullable(first: Double?, second: Double?): Double? = when {
    first == null -> second
    second == null -> first
    else -> min(first, second)
}

private fun maxNullable(first: Double?, second: Double?): Double? = when {
    first == null -> second
    second == null -> first
    else -> max(first, second)
}

package at.themrcodes.ridershub.session

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import at.themrcodes.ridershub.homeassistant.HomeAssistantIntegration
import at.themrcodes.ridershub.log.JsonlSessionLog
import at.themrcodes.ridershub.log.TelemetryArchive
import at.themrcodes.ridershub.protocol.TelemetryFrame
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class RideStore private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val voltageHistory = VoltageHistoryStore(context)
    private val telemetryArchive = TelemetryArchive()
    private var activeChargeCycles: MutableMap<String, ChargeCycleSummary> =
        readChargeCycleArray(KEY_ACTIVE_CHARGE_CYCLES).associateBy { it.localBoardId }.toMutableMap()
    private var completedChargeCycles: MutableList<ChargeCycleSummary> =
        readChargeCycleArray(KEY_COMPLETED_CHARGE_CYCLES)
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
    private var lastCompletedRide: RideSummary? = preferences.getString(KEY_LAST_COMPLETED_RIDE, null)
        ?.let { encoded -> runCatching { summaryFromJson(JSONObject(encoded)) }.getOrNull() }
    private var calibrationPoints: MutableList<CalibrationPoint> = readCalibrationPoints()
    private var lastPersistElapsedMs = 0L

    init {
        archivePreviouslyFinalizedLogs()
    }

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

        val resumeDeadlineEpochMs = if (resumable) current?.pendingFinalizeAtMs else null
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
            resumeDeadlineEpochMs = resumeDeadlineEpochMs,
        )
    }

    fun recordFrame(frame: TelemetryFrame, wallTime: Instant = Instant.now()) = synchronized(LOCK) {
        val stored = active ?: return@synchronized
        val now = wallTime.toEpochMilli()
        var boardTelemetryValid = false
        var firstBoardFrame = false
        var summary = stored.summary.copy(
            frameCount = stored.summary.frameCount + 1,
            crcErrorCount = stored.summary.crcErrorCount + if (frame.crcValid) 0 else 1,
            lastFrameAt = wallTime.toString(),
        )

        if (frame.crcValid) {
            var distance = summary.distanceKm
            var movingSeconds = summary.movingSeconds
            var speedBucketDistances = summary.speedBucketDistancesKm
            val previousAt = stored.lastFrameEpochMs
            val previousSpeed = stored.lastSpeedKmh
            if (previousAt != null && previousSpeed != null) {
                val elapsedMs = now - previousAt
                if (elapsedMs in 1..MAX_DISTANCE_INTEGRATION_GAP_MS) {
                    val elapsedSeconds = elapsedMs / 1000.0
                    val averageSpeedKmh = (previousSpeed + frame.speedKmh) / 2.0
                    val intervalDistanceKm = averageSpeedKmh * elapsedSeconds / 3600.0
                    distance += intervalDistanceKm
                    speedBucketDistances = addSpeedBucketDistance(
                        distancesKm = speedBucketDistances,
                        speedKmh = averageSpeedKmh,
                        distanceKm = intervalDistanceKm,
                    )
                    if (max(previousSpeed, frame.speedKmh) >= MOVING_SPEED_KMH) {
                        movingSeconds += elapsedSeconds
                    }
                }
            }

            val battery = frame.boardBatteryPercent.takeIf { it in 0..100 && frame.packVoltageV > 10.0 }
            val voltage = frame.packVoltageV.takeIf { it in 10.0..70.0 }
            val restingVoltage = voltage.takeIf { frame.speedKmh <= RESTING_SPEED_KMH }
            boardTelemetryValid = battery != null && voltage != null
            firstBoardFrame = boardTelemetryValid &&
                summary.boardBatteryStart == null && summary.packVoltageStart == null
            summary = summary.copy(
                distanceKm = distance,
                speedBucketDistancesKm = speedBucketDistances,
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
            if (boardTelemetryValid && isVoltageSampleDue(now, stored.lastVoltageSampleEpochMs)) {
                stored.voltageBins = addVoltageCorrelationSample(
                    bins = stored.voltageBins,
                    sample = VoltageCorrelationSample(
                        observedAt = wallTime.toString(),
                        batteryPercent = requireNotNull(battery),
                        packVoltageV = requireNotNull(voltage),
                        speedKmh = frame.speedKmh,
                        loadRaw = frame.loadRaw,
                        odometerKm = frame.odometerKm,
                        rideDistanceKm = summary.distanceKm,
                    ),
                )
                stored.lastVoltageSampleEpochMs = now
            }
            if (battery != null && restingVoltage != null) {
                maybeAddCalibrationPointLocked(wallTime, battery, restingVoltage, stored)
            }
        }

        stored.summary = summary
        stored.lastFrameEpochMs = now
        persistLocked(force = false)
        if (boardTelemetryValid) {
            val integration = HomeAssistantIntegration.get(context)
            if (firstBoardFrame) integration.onRideStarted(summary)
            integration.onTelemetry(summary) {
                RangeEstimator.estimate(
                    currentBatteryPercent = summary.boardBatteryEnd,
                    currentRestingVoltageV = summary.restingVoltageEnd,
                    sessions = rangeEstimationSessions(history.filter { it.isTrack }, summary),
                    calibrationPoints = calibrationPoints,
                )
            }
        }
    }

    fun endSegment(
        reason: String,
        lastSequence: Long,
        resumeDeadlineEpochMs: Long? = null,
    ) = synchronized(LOCK) {
        val stored = active ?: return@synchronized
        val now = System.currentTimeMillis()
        stored.lastSequence = max(stored.lastSequence, lastSequence)
        stored.segmentOpen = false
        stored.pendingFinalizeAtMs = resumeDeadlineEpochMs ?: now + SESSION_GRACE_MS
        persistLocked(force = true)
        if (stored.pendingFinalizeAtMs!! <= now) {
            finalizeExpiredLocked(now)
        } else {
            scheduleFinalizationAlarm(stored.pendingFinalizeAtMs!!)
        }
    }

    fun finalizeExpired(nowEpochMs: Long = System.currentTimeMillis()): RideSummary? = synchronized(LOCK) {
        finalizeExpiredLocked(nowEpochMs)
    }

    internal fun voltageCorrelationHistory(
        localBoardId: String? = null,
    ): List<StoredVoltageCorrelationBin> = voltageHistory.readAll(localBoardId)

    fun snapshot(
        currentBatteryPercent: Int?,
        currentRestingVoltageV: Double?,
    ): RideStoreSnapshot = synchronized(LOCK) {
        finalizeExpiredLocked(System.currentTimeMillis())
        val tracks = history.filter { it.isTrack }.sortedByDescending { it.startedAt }
        val rangeSessions = rangeEstimationSessions(tracks, active?.summary)
        val chargeCycleSnapshot = ChargeCycleStoreSnapshot(
            activeCycles = activeChargeCycles.values.sortedByDescending { it.lastObservedAt },
            completedCycles = completedChargeCycles.sortedByDescending { it.closedAt },
        )
        RideStoreSnapshot(
            activeRide = active?.summary,
            lastCompletedRide = lastCompletedRide ?: tracks.firstOrNull(),
            recentTracks = tracks.take(MAX_VISIBLE_TRACKS),
            rangeEstimate = RangeEstimator.estimate(
                currentBatteryPercent = currentBatteryPercent,
                currentRestingVoltageV = currentRestingVoltageV,
                sessions = rangeSessions,
                calibrationPoints = calibrationPoints.toList(),
            ),
            reconnectGraceEndsAt = active?.pendingFinalizeAtMs?.let {
                Instant.ofEpochMilli(it).toString()
            },
            chargeCycles = chargeCycleSnapshot,
            batteryLongevity = BatteryLongevityEstimator.estimate(chargeCycleSnapshot),
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
        var completed = stored.summary.copy(endedAt = endedAt, active = false)
        val jsonlFile = File(completed.logFile)
        JsonlSessionLog.appendSessionEnd(
            file = jsonlFile,
            initialSequence = stored.lastSequence,
            reason = reason,
            summary = completed,
        )
        runCatching {
            telemetryArchive.archiveFinalized(
                source = jsonlFile,
                expectedSessionId = completed.id,
                expectedRemoteAddress = stored.deviceAddress,
            )
        }.getOrNull()?.takeIf { it.sourceDeleted }?.let { archived ->
            completed = completed.copy(logFile = archived.encoded)
        }
        var chargeCycleId = activeChargeCycles[stored.localBoardId]?.id
        if (completed.isTrack) {
            history.add(0, completed)
            history = history.distinctBy { it.id }.take(MAX_STORED_TRACKS).toMutableList()
            chargeCycleId = recordChargeCycleLocked(completed, stored.localBoardId) ?: chargeCycleId
            voltageHistory.recordRide(
                localBoardId = stored.localBoardId,
                chargeCycleId = chargeCycleId,
                ride = completed,
                bins = stored.voltageBins.values,
            )
        }
        if (completed.boardFrameCount > 0) lastCompletedRide = completed
        active = null
        cancelFinalizationAlarm()
        persistLocked(force = true)
        if (completed.boardBatteryStart != null && completed.packVoltageStart != null) {
            val rangeEstimate = RangeEstimator.estimate(
                currentBatteryPercent = completed.boardBatteryEnd,
                currentRestingVoltageV = completed.restingVoltageEnd,
                sessions = history.filter { it.isTrack },
                calibrationPoints = calibrationPoints,
            )
            HomeAssistantIntegration.get(context).onRideEnded(completed, rangeEstimate)
        }
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

    private fun recordChargeCycleLocked(ride: RideSummary, localBoardId: String): String? {
        val update = ChargeCycleTracker.recordRide(
            current = activeChargeCycles[localBoardId],
            ride = ride,
            localBoardId = localBoardId,
        ) ?: return activeChargeCycles[localBoardId]?.id
        activeChargeCycles[localBoardId] = update.activeCycle
        update.completedCycle?.let { completed ->
            completedChargeCycles.add(0, completed)
            completedChargeCycles = completedChargeCycles
                .distinctBy { it.id }
                .take(MAX_STORED_CHARGE_CYCLES)
                .toMutableList()
        }
        return update.activeCycle.id
    }

    private fun archivePreviouslyFinalizedLogs() {
        val directory = context.getExternalFilesDir("telemetry")
            ?: File(context.filesDir, "telemetry")
        val activePath = active?.summary?.logFile?.let(::File)?.absolutePath
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "riders-hub-archive-migration").apply { priority = Thread.MIN_PRIORITY }
        }
        executor.execute {
            directory.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.extension == "jsonl" && it.absolutePath != activePath }
                ?.sortedBy { it.name }
                ?.forEach { source ->
                    runCatching { telemetryArchive.archiveFinalized(source) }
                        .getOrNull()
                        ?.takeIf { it.sourceDeleted }
                        ?.let { archived ->
                            synchronized(LOCK) {
                                replaceCompletedLogReferenceLocked(source, archived.encoded)
                                persistLocked(force = true)
                            }
                        }
                }
        }
        executor.shutdown()
    }

    private fun replaceCompletedLogReferenceLocked(source: File, archiveReference: String) {
        val sourcePath = source.absolutePath
        history = history.map { ride ->
            if (File(ride.logFile).absolutePath == sourcePath) {
                ride.copy(logFile = archiveReference)
            } else {
                ride
            }
        }.toMutableList()
        lastCompletedRide = lastCompletedRide?.let { ride ->
            if (File(ride.logFile).absolutePath == sourcePath) {
                ride.copy(logFile = archiveReference)
            } else {
                ride
            }
        }
    }

    private fun newRide(address: String, name: String?, now: Long): StoredRide {
        val id = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.ROOT)
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
            localBoardId = pseudonymousBoardId(address),
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
                val completed = lastCompletedRide
                if (completed == null) remove(KEY_LAST_COMPLETED_RIDE)
                else putString(KEY_LAST_COMPLETED_RIDE, completed.toJson().toString())
            }
            .putString(KEY_HISTORY, JSONArray(history.map { it.toJson() }).toString())
            .putString(KEY_CALIBRATION, JSONArray(calibrationPoints.map { it.toJson() }).toString())
            .putString(
                KEY_ACTIVE_CHARGE_CYCLES,
                JSONArray(activeChargeCycles.values.map { it.toJson() }).toString(),
            )
            .putString(
                KEY_COMPLETED_CHARGE_CYCLES,
                JSONArray(completedChargeCycles.map { it.toJson() }).toString(),
            )
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

    private fun readChargeCycleArray(key: String): MutableList<ChargeCycleSummary> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]") ?: "[]")
        MutableList(array.length()) { index -> chargeCycleFromJson(array.getJSONObject(index)) }
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
        val deviceAddress = json.getString("device_address")
        StoredRide(
            summary = summaryFromJson(json.getJSONObject("summary")),
            deviceAddress = deviceAddress,
            deviceName = json.optNullableString("device_name"),
            localBoardId = json.optNullableString("local_board_id")
                ?: pseudonymousBoardId(deviceAddress),
            startedAtEpochMs = json.getLong("started_at_epoch_ms"),
            lastFrameEpochMs = json.optNullableLong("last_frame_epoch_ms"),
            lastSpeedKmh = json.optNullableDouble("last_speed_kmh"),
            pendingFinalizeAtMs = json.optNullableLong("pending_finalize_at_ms"),
            lastSequence = json.optLong("last_sequence", 0),
            lastCalibrationAtMs = json.optLong("last_calibration_at_ms", 0),
            lastVoltageSampleEpochMs = json.optNullableLong("last_voltage_sample_epoch_ms"),
            voltageBins = voltageBinsFromJson(json.optJSONArray("voltage_bins")),
            segmentOpen = json.optBoolean("segment_open", false),
        )
    }.getOrNull()

    private data class StoredRide(
        var summary: RideSummary,
        val deviceAddress: String,
        val deviceName: String?,
        val localBoardId: String,
        val startedAtEpochMs: Long,
        var lastFrameEpochMs: Long? = null,
        var lastSpeedKmh: Double? = null,
        var pendingFinalizeAtMs: Long? = null,
        var lastSequence: Long = 0,
        var lastCalibrationAtMs: Long = 0,
        var lastVoltageSampleEpochMs: Long? = null,
        var voltageBins: Map<VoltageCorrelationKey, VoltageCorrelationBin> = emptyMap(),
        var segmentOpen: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("summary", summary.toJson())
            .put("device_address", deviceAddress)
            .putNullable("device_name", deviceName)
            .put("local_board_id", localBoardId)
            .put("started_at_epoch_ms", startedAtEpochMs)
            .putNullable("last_frame_epoch_ms", lastFrameEpochMs)
            .putNullable("last_speed_kmh", lastSpeedKmh)
            .putNullable("pending_finalize_at_ms", pendingFinalizeAtMs)
            .put("last_sequence", lastSequence)
            .put("last_calibration_at_ms", lastCalibrationAtMs)
            .putNullable("last_voltage_sample_epoch_ms", lastVoltageSampleEpochMs)
            .put("voltage_bins", voltageBinsToJson(voltageBins.values))
            .put("segment_open", segmentOpen)
    }

    companion object {
        const val SESSION_GRACE_MS = 120_000L
        const val ACTION_FINALIZE_RIDE = "at.themrcodes.ridershub.FINALIZE_RIDE"
        private const val PREFERENCES_NAME = "ride_store"
        private const val KEY_ACTIVE = "active_ride"
        private const val KEY_LAST_COMPLETED_RIDE = "last_completed_ride"
        private const val KEY_HISTORY = "ride_history"
        private const val KEY_CALIBRATION = "calibration_points"
        private const val KEY_ACTIVE_CHARGE_CYCLES = "active_charge_cycles_v1"
        private const val KEY_COMPLETED_CHARGE_CYCLES = "completed_charge_cycles_v1"
        private const val MAX_DISTANCE_INTEGRATION_GAP_MS = 2_000L
        private const val MOVING_SPEED_KMH = 1.0
        private const val RESTING_SPEED_KMH = 0.5
        private const val PERSIST_INTERVAL_MS = 5_000L
        private const val CALIBRATION_INTERVAL_MS = 10 * 60_000L
        private const val MAX_CALIBRATION_POINTS = 500
        private const val MAX_STORED_TRACKS = 100
        private const val MAX_STORED_CHARGE_CYCLES = 500
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

internal fun rangeEstimationSessions(
    completedTracks: List<RideSummary>,
    activeRide: RideSummary?,
): List<RideSummary> = (listOfNotNull(activeRide) + completedTracks)
    .distinctBy { it.id }

internal fun speedBucketStartKmh(speedKmh: Double): Int =
    floor(max(0.0, speedKmh) / SPEED_BUCKET_WIDTH_KMH).toInt() * SPEED_BUCKET_WIDTH_KMH

internal fun addSpeedBucketDistance(
    distancesKm: Map<Int, Double>,
    speedKmh: Double,
    distanceKm: Double,
): Map<Int, Double> {
    if (!speedKmh.isFinite() || !distanceKm.isFinite() || distanceKm <= 0.0) return distancesKm
    val bucket = speedBucketStartKmh(speedKmh)
    return distancesKm + (bucket to ((distancesKm[bucket] ?: 0.0) + distanceKm))
}

internal const val SPEED_BUCKET_WIDTH_KMH = 5

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
    .put(
        "speed_bucket_distances_km",
        JSONObject().apply {
            speedBucketDistancesKm.toSortedMap().forEach { (bucketStartKmh, distanceKm) ->
                put(bucketStartKmh.toString(), distanceKm)
            }
        },
    )
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
    speedBucketDistancesKm = value.optJSONObject("speed_bucket_distances_km")?.let { buckets ->
        buildMap {
            buckets.keys().forEach { key ->
                key.toIntOrNull()?.takeIf { it >= 0 && it % SPEED_BUCKET_WIDTH_KMH == 0 }
                    ?.let { put(it, buckets.optDouble(key, 0.0)) }
            }
        }.filterValues { it.isFinite() && it > 0.0 }
    } ?: emptyMap(),
    logFile = value.getString("log_file"),
    active = value.optBoolean("active", false),
)

private fun CalibrationPoint.toJson(): JSONObject = JSONObject()
    .put("wall_time", wallTime)
    .put("battery_percent", batteryPercent)
    .put("resting_voltage_v", restingVoltageV)

internal fun ChargeCycleSummary.toJson(): JSONObject = JSONObject()
    .put("schema_version", 1)
    .put("id", id)
    .put("local_board_id", localBoardId)
    .put("started_at", startedAt)
    .put("last_observed_at", lastObservedAt)
    .putNullable("closed_at", closedAt)
    .put("start_reason", startReason.name)
    .putNullable("end_reason", endReason?.name)
    .putNullable("inferred_recharge_increase_percent", inferredRechargeIncreasePercent)
    .put("battery_start_percent", batteryStartPercent)
    .put("battery_end_percent", batteryEndPercent)
    .put("battery_min_percent", batteryMinPercent)
    .put("battery_max_percent", batteryMaxPercent)
    .putNullable("resting_voltage_start", restingVoltageStart)
    .putNullable("resting_voltage_end", restingVoltageEnd)
    .putNullable("resting_voltage_min", restingVoltageMin)
    .putNullable("resting_voltage_max", restingVoltageMax)
    .put("recorded_distance_km", recordedDistanceKm)
    .put("moving_seconds", movingSeconds)
    .put(
        "speed_bucket_distances_km",
        JSONObject().apply {
            speedBucketDistancesKm.toSortedMap().forEach { (bucketStartKmh, distanceKm) ->
                put(bucketStartKmh.toString(), distanceKm)
            }
        },
    )
    .putNullable("odometer_start_km", odometerStartKm)
    .putNullable("odometer_end_km", odometerEndKm)
    .put("ride_count", rideCount)

internal fun chargeCycleFromJson(value: JSONObject): ChargeCycleSummary = ChargeCycleSummary(
    id = value.getString("id"),
    localBoardId = value.getString("local_board_id"),
    startedAt = value.getString("started_at"),
    lastObservedAt = value.getString("last_observed_at"),
    closedAt = value.optNullableString("closed_at"),
    startReason = enumValueOrDefault(
        value.optString("start_reason"),
        ChargeCycleStartReason.FIRST_OBSERVATION,
    ),
    endReason = value.optNullableString("end_reason")?.let {
        enumValueOrDefault(it, ChargeCycleEndReason.INFERRED_RECHARGE)
    },
    inferredRechargeIncreasePercent = value.optNullableInt("inferred_recharge_increase_percent"),
    batteryStartPercent = value.getInt("battery_start_percent"),
    batteryEndPercent = value.getInt("battery_end_percent"),
    batteryMinPercent = value.getInt("battery_min_percent"),
    batteryMaxPercent = value.getInt("battery_max_percent"),
    restingVoltageStart = value.optNullableDouble("resting_voltage_start"),
    restingVoltageEnd = value.optNullableDouble("resting_voltage_end"),
    restingVoltageMin = value.optNullableDouble("resting_voltage_min"),
    restingVoltageMax = value.optNullableDouble("resting_voltage_max"),
    recordedDistanceKm = value.optDouble("recorded_distance_km", 0.0),
    movingSeconds = value.optDouble("moving_seconds", 0.0),
    speedBucketDistancesKm = value.optJSONObject("speed_bucket_distances_km")?.let { buckets ->
        buildMap {
            buckets.keys().forEach { key ->
                key.toIntOrNull()?.takeIf { it >= 0 && it % SPEED_BUCKET_WIDTH_KMH == 0 }
                    ?.let { put(it, buckets.optDouble(key, 0.0)) }
            }
        }.filterValues { it.isFinite() && it > 0.0 }
    } ?: emptyMap(),
    odometerStartKm = value.optNullableDouble("odometer_start_km"),
    odometerEndKm = value.optNullableDouble("odometer_end_km"),
    rideCount = value.optInt("ride_count", 1),
)

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default

internal fun pseudonymousBoardId(deviceAddress: String): String {
    val normalizedAddress = deviceAddress.trim().lowercase(Locale.ROOT)
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("riders-hub-board:$normalizedAddress".toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(6 + 24) {
        append("board_")
        digest.take(12).forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

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

package at.themrcodes.ridershub.session

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import at.themrcodes.ridershub.BatteryWarningNotifier
import at.themrcodes.ridershub.api.ApiIntegration
import at.themrcodes.ridershub.api.ApiArchiveWorker
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
    private var activeRangeWindows: MutableMap<String, RangeDepletionWindow> =
        readRangeWindowArray(KEY_ACTIVE_RANGE_WINDOWS).associateBy { it.localBoardId }.toMutableMap()
    private var completedRangeWindows: MutableList<RangeDepletionWindow> =
        readRangeWindowArray(KEY_COMPLETED_RANGE_WINDOWS)
    private var lastCompletedRide: RideSummary? = preferences.getString(KEY_LAST_COMPLETED_RIDE, null)
        ?.let { encoded -> runCatching { summaryFromJson(JSONObject(encoded)) }.getOrNull() }
    private var calibrationPoints: MutableList<CalibrationPoint> = readCalibrationPoints()
    private var batteryLearningModels = readBatteryLearningModels()
    private var batteryEstimates = BatteryEstimateRegistry(
        preferences.getString(KEY_BATTERY_ESTIMATES, "{}") ?: "{}",
    )
    private val boardContinuity = mutableMapOf<String, BoardContinuityState>().apply {
        val value = runCatching {
            JSONObject(preferences.getString("board_continuity_v1", "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        value.keys().forEach { key ->
            runCatching { BoardContinuityState.fromJson(value.getJSONObject(key)) }
                .getOrNull()?.let { put(key, it) }
        }
    }
    private var batteryChargeStats = BatteryChargeStatsRegistry(
        preferences.getString("battery_charge_stats_v1", "{}") ?: "{}",
    ).also { registry ->
        (completedChargeCycles + activeChargeCycles.values).forEach {
            registry.seedMaximum(it.localBoardId, it.batteryMaxPercent)
        }
    }
    private val boardObservationTrackers = mutableMapOf<String, BatteryObservationTracker>()
    private val boardLearningTrackers = mutableMapOf<String, BatteryLearningTracker>()
    private var speedBucketStats = BoardSpeedBucketStatsRegistry(
        preferences.getString("board_speed_bucket_stats_v1", "{}") ?: "{}",
    )
    private var lastPersistElapsedMs = 0L

    init {
        // An open BLE segment cannot survive process death. Preserve the logical ride's grace.
        active?.let { stored ->
            stored.segmentOpen = false
            if (stored.pendingFinalizeAtMs == null) {
                stored.pendingFinalizeAtMs = (stored.lastFrameEpochMs ?: stored.startedAtEpochMs) + SESSION_GRACE_MS
            }
            scheduleFinalizationAlarm(requireNotNull(stored.pendingFinalizeAtMs))
        }
        val knownBoards = history.mapNotNull { it.localBoardId } + listOfNotNull(active?.localBoardId) +
            completedRangeWindows.map { it.localBoardId } + activeRangeWindows.keys
        knownBoards.distinct().forEach { board ->
            seedRangeCalibrationHistoryLocked(board)
            seedSpeedBucketStatsLocked(board)
        }
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
        seedRangeCalibrationHistoryLocked(stored.localBoardId)
        seedSpeedBucketStatsLocked(stored.localBoardId)
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

    internal fun recordFrame(
        frame: TelemetryFrame,
        wallTime: Instant = Instant.now(),
        receivedElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): BatteryFrameDiagnostics = synchronized(LOCK) {
        val stored = active ?: return@synchronized BatteryFrameDiagnostics("no_active_ride")
        var batteryDiagnostics = BatteryFrameDiagnostics(
            if (frame.crcValid) "invalid_board_telemetry" else "invalid_crc",
        )
        val now = wallTime.toEpochMilli()
        var boardTelemetryValid = false
        var firstBoardFrame = false
        var summary = stored.summary.copy(
            frameCount = stored.summary.frameCount + 1,
            crcErrorCount = stored.summary.crcErrorCount + if (frame.crcValid) 0 else 1,
        )

        if (frame.crcValid) {
            var movingSeconds = summary.movingSeconds
            var speedBucketDistances = summary.speedBucketDistancesKm
            var intervalDistanceKm = 0.0
            var intervalSpeedKmh: Double? = null
            val elapsedNow = receivedElapsedMs
            var batteryElapsedSeconds = stored.lastBatteryElapsedMs?.let { (elapsedNow - it) / 1000.0 } ?: 0.0
            val previousAt = stored.lastFrameEpochMs
            val previousSpeed = stored.lastSpeedKmh
            if (previousAt != null && previousSpeed != null) {
                val elapsedMs = now - previousAt
                if (elapsedMs in 1..MAX_DISTANCE_INTEGRATION_GAP_MS) {
                    val elapsedSeconds = elapsedMs / 1000.0
                    val averageSpeedKmh = (previousSpeed + frame.speedKmh) / 2.0
                    intervalDistanceKm = averageSpeedKmh * elapsedSeconds / 3600.0
                    intervalSpeedKmh = averageSpeedKmh
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

            val voltage = frame.packVoltageV.takeIf { it in 10.0..70.0 }
            val battery = frame.boardBatteryPercent.takeIf { it in 0..100 && voltage != null }
            val restingVoltage = voltage.takeIf { frame.speedKmh <= RESTING_SPEED_KMH }
            boardTelemetryValid = battery != null && voltage != null
            firstBoardFrame = boardTelemetryValid &&
                summary.boardBatteryStart == null && summary.packVoltageStart == null
            summary = summary.copy(
                distanceKm = authoritativeTripDistanceKm(
                    remoteTripKm = frame.tripKm,
                    previousDistanceKm = summary.distanceKm,
                ),
                speedBucketDistancesKm = speedBucketDistances,
                movingSeconds = movingSeconds,
                maxSpeedKmh = max(summary.maxSpeedKmh, frame.speedKmh),
                boardBatteryStart = summary.boardBatteryStart ?: battery,
                lastFrameAt = if (boardTelemetryValid) wallTime.toString() else summary.lastFrameAt,
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
                boardFrameCount = summary.boardFrameCount + if (boardTelemetryValid) 1 else 0,
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
            if (boardTelemetryValid) {
                val chargeStats = batteryChargeStats.observe(
                    stored.localBoardId, requireNotNull(battery), now, frame.odometerKm,
                )
                val previousContinuity = boardContinuity[stored.localBoardId]
                val continuity = BoardContinuityTracker.observe(
                    previousContinuity, now, requireNotNull(battery),
                    frame.odometerKm, frame.speedKmh,
                )
                boardContinuity[stored.localBoardId] = continuity.state
                intervalDistanceKm = continuity.intervalDistanceKm
                intervalSpeedKmh = continuity.intervalSpeedKmh
                batteryElapsedSeconds = if (continuity.gapReason == null && previousContinuity != null) {
                    (now - previousContinuity.atMs) / 1000.0
                } else 0.0
                val estimatedGapProfile = recordStreamCalibrationLocked(stored, summary, continuity, wallTime)
                if (stored.batteryConsumptionModel == null || elapsedNow - stored.batteryModelAtMs >= 10_000L) {
                    stored.batteryConsumptionModel = RangeEstimator.estimate(
                        currentBatteryPercent = battery,
                        currentRestingVoltageV = null,
                        depletionWindows = completedRangeWindows.filter { it.localBoardId == stored.localBoardId },
                        profileSessions = rangeEstimationSessions(
                            history.filter { it.localBoardId == stored.localBoardId }, summary,
                        ),
                        calibrationPoints = emptyList(),
                        selectedBoardId = stored.localBoardId,
                        speedBucketStatistics = speedBucketStats.snapshot(stored.localBoardId),
                    )
                    stored.batteryModelAtMs = elapsedNow
                }
                val model = stored.batteryConsumptionModel
                val rate = intervalSpeedKmh?.let { speed ->
                    model?.bucketBatteryPercentPer100Km?.get(speedBucketStartKmh(speed))?.div(100.0)
                } ?: model?.kmPerPercent?.let { 1.0 / it }
                val learningModel = batteryLearningModels[stored.localBoardId] ?: BatteryLearningModel()
                val previousSummary = stored.summary.takeIf { it.estimatedBatteryPercent != null }
                    ?: lastCompletedRide?.takeIf {
                        it.localBoardId == stored.localBoardId && it.estimatedBatteryPercent != null
                    }
                    ?: history.firstOrNull { it.localBoardId == stored.localBoardId && it.estimatedBatteryPercent != null }
                val previousEstimate = batteryEstimates.state(stored.localBoardId)
                    ?: previousSummary?.let { previous ->
                        val percent = previous.estimatedBatteryPercent ?: return@let null
                        val reference = previous.boardBatteryEnd ?: return@let null
                        runCatching { BatteryEstimateState(percent, reference) }.getOrNull()
                    }
                val appliedRate = learningModel.rate(rate)
                val observationEstimate = boardObservationTrackers.getOrPut(stored.localBoardId) {
                    BatteryObservationTracker()
                }.observeWithDiagnostics(
                    state = previousEstimate,
                    reportedPercent = requireNotNull(battery),
                    elapsedSeconds = if (intervalSpeedKmh != null) batteryElapsedSeconds else 0.0,
                    distanceKm = intervalDistanceKm,
                    batteryPercentPerKm = appliedRate,
                    voltageV = requireNotNull(voltage),
                    loadRaw = frame.loadRaw,
                )
                val inferredGapBase = model?.let {
                    DigitalTwinDriveModel.inferredTransition(
                        inferredSpeedBucketDistancesKm = estimatedGapProfile,
                        bucketPercentPer100Km = it.bucketBatteryPercentPer100Km,
                        fallbackKmPerPercent = it.kmPerPercent,
                    )
                }
                val inferredGapTransition = inferredGapBase?.let { transition ->
                    learningModel.rate(transition.effectivePercentPerKm)?.let { learnedRate ->
                        InferredDriveTransition(
                            distanceKm = transition.distanceKm,
                            expectedBatteryUsePercent = transition.distanceKm * learnedRate,
                            effectivePercentPerKm = learnedRate,
                        )
                    }
                }
                val inferredGapStep = inferredGapTransition?.let { transition ->
                    BatteryPercentEstimator.propagateInferredDrive(
                        previousPercent = observationEstimate.state.percent,
                        reportedPercent = requireNotNull(battery),
                        transition = transition,
                    )
                }
                val estimate = if (inferredGapStep == null) {
                    observationEstimate
                } else {
                    BatteryObservationResult(
                        state = observationEstimate.state.copy(percent = inferredGapStep.percent),
                        diagnostics = observationEstimate.diagnostics.copy(
                            status = "propagated_inferred_gap",
                            consumptionPercent = observationEstimate.diagnostics.consumptionPercent +
                                inferredGapStep.consumptionPercent,
                        ),
                    )
                }
                batteryEstimates.record(stored.localBoardId, estimate.state)
                summary = summary.copy(estimatedBatteryPercent = estimate.state.percent)
                val learnedAfter = boardLearningTrackers.getOrPut(stored.localBoardId) {
                    BatteryLearningTracker()
                }.observe(
                    model = learningModel,
                    reportedPercent = requireNotNull(battery),
                    elapsedSeconds = if (intervalSpeedKmh != null) batteryElapsedSeconds else 0.0,
                    intervalDistanceKm = intervalDistanceKm,
                    basePercentPerKm = rate,
                    reliableObservation = estimate.diagnostics.settled,
                )
                batteryLearningModels[stored.localBoardId] = learnedAfter
                batteryDiagnostics = BatteryFrameDiagnostics(
                    status = estimate.diagnostics.status,
                    estimatedPercent = estimate.state.percent,
                    observation = estimate.diagnostics,
                    elapsedSeconds = if (intervalSpeedKmh != null) batteryElapsedSeconds else 0.0,
                    intervalDistanceKm = intervalDistanceKm,
                    speedBucketKmh = intervalSpeedKmh?.let(::speedBucketStartKmh),
                    basePercentPerKm = rate,
                    appliedPercentPerKm = appliedRate,
                    learningBefore = learningModel,
                    learningAfter = learnedAfter,
                    continuity = continuity,
                    estimatedGapSpeedBuckets = estimatedGapProfile,
                    inferredGapDistanceKm = inferredGapTransition?.distanceKm ?: 0.0,
                    inferredGapBasePercentPerKm = inferredGapBase?.effectivePercentPerKm,
                    inferredGapAppliedPercentPerKm = inferredGapTransition?.effectivePercentPerKm,
                    inferredGapConsumptionPercent = inferredGapStep?.consumptionPercent ?: 0.0,
                    highestReportedBatteryPercent = chargeStats.highestReportedPercent,
                    highestSettledBatteryPercent = chargeStats.highestSettledPercent,
                    bucketModelSource = when {
                        model?.usesAccumulatedBucketConsumption == true -> "cumulative"
                        model?.kmPerPercent != null -> "retained_windows"
                        else -> "unavailable"
                    },
                    bucketEvidence = intervalSpeedKmh?.let(::speedBucketStartKmh)?.let { bucket ->
                        model?.speedBucketStatistics?.buckets?.firstOrNull { it.startKmh == bucket }
                    },
                )
                stored.lastBatteryElapsedMs = elapsedNow

            } else {
                stored.lastBatteryElapsedMs = null
            }
        }

        if (!boardTelemetryValid) {
            batteryChargeStats.interrupted(stored.localBoardId)
            stored.lastBatteryElapsedMs = null
            boardContinuity[stored.localBoardId]?.let {
                boardContinuity[stored.localBoardId] = it.copy(interrupted = true, chargeCandidateSeconds = 0.0)
            }
        }
        stored.summary = summary
        stored.lastFrameEpochMs = now
        if (boardTelemetryValid) {
            // A non-zero remote trip on our first observation means the phone joined mid-ride;
            // publish current state but do not fabricate a historical start occurrence.
            if (firstBoardFrame && frame.tripKm <= MID_RIDE_START_THRESHOLD_KM) {
                ApiIntegration.get(context).onRideStarted(summary)
            }
        }
        // Persist the first observation only after its idempotent transition journal is durable.
        persistLocked(force = firstBoardFrame)
        batteryDiagnostics
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
        stored.lastBatteryElapsedMs = null
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

    fun activeRide(): RideSummary? = synchronized(LOCK) { active?.summary }

    internal fun resumeLocationLog(path: String): Boolean = synchronized(LOCK) {
        val ride = active?.summary ?: return@synchronized false
        if (ride.logFile != path || ride.endedAt != null || ride.boardFrameCount == 0L) return@synchronized false
        JsonlSessionLog.resumeRide(File(path))
        true
    }

    internal fun <T> withActiveRide(id: String, action: (RideSummary, RangeEstimate) -> T): T? = synchronized(LOCK) {
        val current = active?.summary?.takeIf { it.id == id && it.endedAt == null } ?: return@synchronized null
        val estimate = snapshot(current.boardBatteryEnd, current.restingVoltageEnd).rangeEstimate
        if (active?.summary?.id != id) return@synchronized null
        action(current, estimate)
    }

    internal fun voltageCorrelationHistory(
        localBoardId: String? = null,
    ): List<StoredVoltageCorrelationBin> = voltageHistory.readAll(localBoardId)

    /** Cumulative per-board analysis, independent of the range forecast's rolling history. */
    fun speedBucketStatistics(localBoardId: String): BoardSpeedBucketStatistics? = synchronized(LOCK) {
        speedBucketStats.snapshot(localBoardId)
    }

    private fun seedSpeedBucketStatsLocked(board: String) {
        speedBucketStats.seed(board, history + listOfNotNull(active?.summary, lastCompletedRide),
            completedRangeWindows + activeRangeWindows.values)
    }

    fun snapshot(
        currentBatteryPercent: Int?,
        currentRestingVoltageV: Double?,
    ): RideStoreSnapshot = synchronized(LOCK) {
        finalizeExpiredLocked(System.currentTimeMillis())
        val tracks = history.filter { it.isTrack }.sortedByDescending { it.startedAt }
        val rangeBoardId = active?.localBoardId
            ?: lastCompletedRide?.localBoardId
            ?: completedRangeWindows.maxByOrNull { it.lastObservedAt }?.localBoardId
            ?: activeRangeWindows.values.maxByOrNull { it.lastObservedAt }?.localBoardId
        val scopedTracks = rangeBoardId?.let { boardId ->
            tracks.filter { it.localBoardId == boardId }.ifEmpty {
                tracks.filter { it.localBoardId == null }
            }
        } ?: tracks
        val rangeSessions = rangeEstimationSessions(scopedTracks, active?.summary)
        val chargeCycleSnapshot = ChargeCycleStoreSnapshot(
            activeCycles = activeChargeCycles.values.sortedByDescending { it.lastObservedAt },
            completedCycles = completedChargeCycles.sortedByDescending { it.closedAt },
        )
        val bucketStatistics = rangeBoardId?.let { speedBucketStats.snapshot(it) }
        RideStoreSnapshot(
            activeRide = active?.summary,
            lastCompletedRide = lastCompletedRide ?: tracks.firstOrNull(),
            recentTracks = tracks,
            rangeEstimate = RangeEstimator.estimate(
                currentBatteryPercent = currentBatteryPercent,
                estimatedBatteryPercent = (active?.summary ?: lastCompletedRide)
                    ?.takeIf { it.boardBatteryEnd == currentBatteryPercent }?.estimatedBatteryPercent,
                currentRestingVoltageV = currentRestingVoltageV,
                depletionWindows = completedRangeWindows.filter { it.localBoardId == rangeBoardId },
                profileSessions = rangeSessions,
                calibrationPoints = calibrationPoints.toList(),
                selectedBoardId = rangeBoardId,
                speedBucketStatistics = bucketStatistics,
            ),
            reconnectGraceEndsAt = active?.pendingFinalizeAtMs?.let {
                Instant.ofEpochMilli(it).toString()
            },
            chargeCycles = chargeCycleSnapshot,
            batteryLongevity = BatteryLongevityEstimator.estimate(
                chargeCycleSnapshot, rangeBoardId,
                rangeBoardId?.let { batteryChargeStats.state(it) },
                bucketStatistics,
            ),
            speedBucketStatistics = bucketStatistics,
        )
    }

    private fun finalizeExpiredLocked(nowEpochMs: Long): RideSummary? {
        val stored = active ?: return null
        if (stored.summary.endedAt != null) return finalizeLocked("recovered_finalization", nowEpochMs)
        val deadline = stored.pendingFinalizeAtMs ?: return null
        return if (nowEpochMs >= deadline) finalizeLocked("disconnect_grace_expired", nowEpochMs) else null
    }

    private fun finalizeLocked(reason: String, nowEpochMs: Long): RideSummary? {
        val stored = active ?: return null
        // The logical ride ends after reconnect grace; lastFrameAt remains the distinct final
        // board observation and GPS records may legitimately fall between these timestamps.
        val endedAt = stored.summary.endedAt ?: Instant.ofEpochMilli(nowEpochMs).toString()
        var completed = stored.summary.copy(
            endedAt = endedAt,
            active = false,
            localBoardId = stored.localBoardId,
        )
        val jsonlFile = File(completed.logFile)
        // Journal the finalization timestamp before draining; retries retain the same occurrence.
        stored.summary = completed
        persistLocked(force = true)
        // Stop BLE realtime/GPS producers before closing the ride-owned record stream.
        ApiIntegration.get(context).onRideFinalizing(completed.id)
        stored.lastSequence = JsonlSessionLog.finishSession(
            file = jsonlFile,
            initialSequence = stored.lastSequence,
            reason = reason,
            summary = completed,
        )
        var chargeCycleId = activeChargeCycles[stored.localBoardId]?.id
        if (completed.isTrack) {
            history.add(0, completed)
            history = retainRangeProfileHistory(
                newestFirst = history.distinctBy { it.id },
                targetDistanceKm = RANGE_PROFILE_DISTANCE_KM,
                maxCount = MAX_STORED_TRACKS,
            ).toMutableList()
            // Older recovered rides may predate live stream collection.
            if (!boardContinuity.containsKey(stored.localBoardId)) {
                chargeCycleId = recordChargeCycleLocked(completed, stored.localBoardId) ?: chargeCycleId
            }
            voltageHistory.recordRide(
                localBoardId = stored.localBoardId,
                chargeCycleId = chargeCycleId,
                ride = completed,
                bins = stored.voltageBins.values,
            )
        }
        if (completed.boardFrameCount > 0) lastCompletedRide = completed
        val finalRangeEstimate = if (
            completed.boardBatteryStart != null && completed.packVoltageStart != null
        ) {
            RangeEstimator.estimate(
                currentBatteryPercent = completed.boardBatteryEnd,
                estimatedBatteryPercent = completed.estimatedBatteryPercent,
                currentRestingVoltageV = completed.restingVoltageEnd,
                depletionWindows = completedRangeWindows.filter {
                    it.localBoardId == stored.localBoardId
                },
                profileSessions = history.filter {
                    it.isTrack && it.localBoardId == stored.localBoardId
                },
                calibrationPoints = calibrationPoints,
                selectedBoardId = stored.localBoardId,
                speedBucketStatistics = speedBucketStats.snapshot(stored.localBoardId),
            )
        } else null
        // Journal first. A crash before active is cleared repeats this transition, but the
        // destination-scoped event key makes that recovery idempotent.
        ApiIntegration.get(context).onRideEnded(completed, finalRangeEstimate)
        active = null
        cancelFinalizationAlarm()
        persistLocked(force = true)
        if (completed.boardBatteryEnd != null && completed.packVoltageEnd != null) {
            BatteryWarningNotifier(context).maybeNotifyBoardAtRideEnd(
                sessionId = completed.id,
                batteryPercent = completed.boardBatteryEnd,
                packVoltageV = completed.packVoltageEnd,
                nowEpochMs = nowEpochMs,
            )
        }
        archiveCompletedAsync(jsonlFile, completed.id, stored.deviceAddress)
        return completed
    }

    private fun archiveCompletedAsync(source: File, sessionId: String, remoteAddress: String) {
        ARCHIVE_EXECUTOR.execute {
            runCatching {
                telemetryArchive.archiveFinalized(source, sessionId, remoteAddress)
            }.getOrNull()?.takeIf { it.sourceDeleted }?.let { archived ->
                synchronized(LOCK) {
                    replaceCompletedLogReferenceLocked(source, archived.encoded)
                    persistLocked(force = true)
                }
                ApiArchiveWorker.enqueue(context)
            }
        }
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

    private fun recordStreamCalibrationLocked(
        stored: StoredRide,
        summary: RideSummary,
        update: BoardContinuityUpdate,
        wallTime: Instant,
    ): Map<Int, Double> {
        val board = stored.localBoardId
        val percent = requireNotNull(summary.boardBatteryEnd)
        val at = wallTime.toString()
        val estimatedGapProfile = if (update.unprofiledDistanceKm > 0.0) {
            GapSpeedProfile.estimate(
                update.unprofiledDistanceKm, board,
                rangeEstimationSessions(history, stored.summary),
                speedBucketStats.snapshot(board),
            )
        } else emptyMap()
        speedBucketStats.recordDistance(
            board,
            measured = update.intervalSpeedKmh?.takeIf { update.intervalDistanceKm > 0 }?.let {
                mapOf(speedBucketStartKmh(it) to update.intervalDistanceKm)
            } ?: emptyMap(),
            inferred = estimatedGapProfile,
            unattributedKm = (update.unprofiledDistanceKm - estimatedGapProfile.values.sum()).coerceAtLeast(0.0),
        )
        // A pending rise or information gap must not contaminate a measured depletion window.
        if (update.calibrationGapReason != null || update.chargePending || update.rechargeIncreasePercent != null) {
            activeRangeWindows.remove(board)
            stored.batteryConsumptionModel = null
        }
        if (!update.chargePending) {
            recordRangeCalibrationLocked(
                board, percent, at,
                if (update.rechargeIncreasePercent == null) update.intervalDistanceKm + update.unprofiledDistanceKm else 0.0,
                update.intervalSpeedKmh,
                estimatedGapProfile,
            )
        }
        val distance = if (update.chargePending || update.rechargeIncreasePercent != null) 0.0
            else update.intervalDistanceKm + update.unprofiledDistanceKm
        val observation = summary.copy(
            id = "stream-$at", startedAt = at, endedAt = at, lastFrameAt = at,
            boardBatteryStart = percent, boardBatteryEnd = percent, boardBatteryMin = percent,
            distanceKm = distance,
            movingSeconds = if (distance > 0 && (update.intervalSpeedKmh ?: 0.0) >= MOVING_SPEED_KMH) {
                update.intervalSpeedKmh?.takeIf { it > 0 }?.let { distance * 3600 / it } ?: 0.0
            } else 0.0,
            speedBucketDistancesKm = update.intervalSpeedKmh?.takeIf { distance > 0 }?.let {
                mapOf(speedBucketStartKmh(it) to distance)
            } ?: emptyMap(),
            restingVoltageStart = summary.restingVoltageEnd,
            odometerStartKm = update.state.odometerKm, odometerEndKm = update.state.odometerKm,
        )
        if (!update.chargePending) {
            val result = ChargeCycleTracker.recordFrame(
                activeChargeCycles[board], observation, board, update.rechargeIncreasePercent,
                stored.summary.boardBatteryStart == null,
                estimatedGapProfile,
            )
            activeChargeCycles[board] = result.activeCycle
            result.completedCycle?.let {
                completedChargeCycles.add(0, it)
                completedChargeCycles = completedChargeCycles.distinctBy { cycle -> cycle.id }
                    .take(MAX_STORED_CHARGE_CYCLES).toMutableList()
            }
        }
        return estimatedGapProfile
    }

    private fun recordRangeCalibrationLocked(
        localBoardId: String,
        batteryPercent: Int,
        observedAt: String,
        distanceKm: Double,
        speedKmh: Double?,
        estimatedSpeedBuckets: Map<Int, Double> = emptyMap(),
    ) {
        val speedBuckets = if (speedKmh != null && distanceKm > 0.0) {
            mapOf(speedBucketStartKmh(speedKmh) to distanceKm)
        } else {
            emptyMap()
        }
        val update = RangeCalibrationTracker.observe(
            current = activeRangeWindows[localBoardId],
            localBoardId = localBoardId,
            batteryPercent = batteryPercent,
            observedAt = observedAt,
            distanceKm = distanceKm,
            speedBucketDistancesKm = speedBuckets,
            allowRechargeReset = false,
            estimatedSpeedBucketDistancesKm = estimatedSpeedBuckets,
        ) ?: return
        activeRangeWindows[localBoardId] = update.activeWindow
        update.completedWindow?.let { completed ->
            speedBucketStats.recordWindow(localBoardId, completed)
            active?.takeIf { it.localBoardId == localBoardId }?.batteryConsumptionModel = null
            completedRangeWindows.add(0, completed)
            completedRangeWindows = completedRangeWindows
                .distinctBy { it.id }
                .take(MAX_STORED_RANGE_WINDOWS)
                .toMutableList()
        }
    }

    private fun seedRangeCalibrationHistoryLocked(localBoardId: String) {
        if (
            activeRangeWindows.containsKey(localBoardId) ||
            completedRangeWindows.any { it.localBoardId == localBoardId }
        ) return
        val knownBoardIds = activeChargeCycles.keys + completedChargeCycles.map { it.localBoardId }
        if (knownBoardIds.isNotEmpty() && localBoardId !in knownBoardIds) return
        if (knownBoardIds.isEmpty() || knownBoardIds.all { it == localBoardId }) {
            history = history.map { ride ->
                if (ride.localBoardId == null) ride.copy(localBoardId = localBoardId) else ride
            }.toMutableList()
        }
        val seedHistory = history.filter { it.localBoardId == localBoardId }
        if (seedHistory.isEmpty()) return

        var current: RangeDepletionWindow? = null
        seedHistory.asReversed().filter { it.isTrack }.forEach { ride ->
            val update = RangeCalibrationTracker.observeRide(current, localBoardId, ride)
                ?: return@forEach
            current = update.activeWindow
            update.completedWindow?.let { completedRangeWindows.add(0, it) }
        }
        current?.let { activeRangeWindows[localBoardId] = it }
        completedRangeWindows = completedRangeWindows
            .distinctBy { it.id }
            .take(MAX_STORED_RANGE_WINDOWS)
            .toMutableList()
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
                            ApiArchiveWorker.enqueue(context)
                        }
                }
            migrateAnalyticsHistory(directory)
        }
        executor.shutdown()
    }

    /** Startup worker only: compute off the UI thread, then atomically replace idle legacy state. */
    private fun migrateAnalyticsHistory(telemetryDirectory: File) {
        val before = synchronized(LOCK) {
            val projectionCurrent = preferences.getString(AnalyticsHistoryMigration.VERSION_KEY, null)
                ?.toIntOrNull()?.let { it >= AnalyticsHistoryMigration.VERSION.toInt() } == true
            val modelCurrent = preferences.getString(AnalyticsHistoryMigration.MODEL_VERSION_KEY, null) ==
                AnalyticsHistoryMigration.MODEL_VERSION
            if (active != null || projectionCurrent && modelCurrent) return
            preferences.all.toMap()
        }
        val strings = before.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
        val archiveDirectory = File(telemetryDirectory, "archive")
        val rawArchiveAvailable = archiveDirectory.listFiles()?.any {
            it.isFile && it.extension.lowercase(Locale.ROOT) in setOf("rhp", "rha")
        } == true
        if (!AnalyticsHistoryMigration.required(strings, rawArchiveAvailable)) {
            synchronized(LOCK) {
                if (active == null && preferences.all == before) preferences.edit()
                    .putString(AnalyticsHistoryMigration.VERSION_KEY, AnalyticsHistoryMigration.VERSION)
                    .putString(AnalyticsHistoryMigration.MODEL_VERSION_KEY, AnalyticsHistoryMigration.MODEL_VERSION)
                    .commit()
            }
            return
        }
        val directory = File(context.filesDir, "analytics-migration").also { it.mkdirs() }
        val scratch = File(context.cacheDir, "analytics-migration").also { it.mkdirs() }
        runCatching {
            val rebuilt = AnalyticsHistoryMigration.rebuild(archiveDirectory, scratch, strings)
            // Backup and comparison are durable before the completion marker or derived state changes.
            val backup = File.createTempFile("before-v${AnalyticsHistoryMigration.VERSION}-", ".json", directory)
            java.io.FileOutputStream(backup).use { output ->
                output.write(JSONObject(before).toString().toByteArray()); output.fd.sync()
            }
            File(directory,
                "comparison-v${AnalyticsHistoryMigration.VERSION}-${AnalyticsHistoryMigration.MODEL_VERSION}.json",
            ).writeText(rebuilt.report.toString(2))
            synchronized(LOCK) {
                // A recording, finalization or settings writer may have changed the inputs during replay.
                if (active != null || preferences.all != before) return@synchronized
                val committed = preferences.edit().apply {
                    rebuilt.replacement.forEach { (key, value) -> putString(key, value) }
                    putString("analytics_history_backup", backup.name)
                    remove("analytics_history_migration_error")
                }.commit()
                if (!committed) {
                    // SharedPreferences changes its in-memory map even when disk persistence fails.
                    // Restore these keys too, so a failed write cannot suppress the next attempt.
                    preferences.edit().apply {
                        (rebuilt.replacement.keys + "analytics_history_backup" + "analytics_history_migration_error").forEach { key ->
                            val old = before[key] as? String
                            if (old == null) remove(key) else putString(key, old)
                        }
                    }.commit()
                    error("Could not persist rebuilt analytics")
                }
                activeRangeWindows = readRangeWindowArray(KEY_ACTIVE_RANGE_WINDOWS).associateBy { it.localBoardId }.toMutableMap()
                completedRangeWindows = readRangeWindowArray(KEY_COMPLETED_RANGE_WINDOWS)
                activeChargeCycles = readChargeCycleArray(KEY_ACTIVE_CHARGE_CYCLES).associateBy { it.localBoardId }.toMutableMap()
                completedChargeCycles = readChargeCycleArray(KEY_COMPLETED_CHARGE_CYCLES)
                speedBucketStats = BoardSpeedBucketStatsRegistry(rebuilt.replacement.getValue("board_speed_bucket_stats_v1"))
                batteryChargeStats = BatteryChargeStatsRegistry(rebuilt.replacement.getValue("battery_charge_stats_v1"))
                batteryLearningModels = readBatteryLearningModels()
                batteryEstimates = BatteryEstimateRegistry(
                    rebuilt.replacement.getValue("battery_estimates_v1"),
                )
                boardObservationTrackers.clear()
                boardLearningTrackers.clear()
                val continuity = JSONObject(rebuilt.replacement.getValue("board_continuity_v1"))
                boardContinuity.clear()
                continuity.keys().forEach { id -> boardContinuity[id] = BoardContinuityState.fromJson(continuity.getJSONObject(id)) }
            }
        }.onFailure {
            // Keep the old data and retry on a later idle startup; never mark a failed rebuild complete.
            preferences.edit().putString("analytics_history_migration_error", it.javaClass.simpleName).apply()
        }
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
        val localBoardId = pseudonymousBoardId(address)
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
                localBoardId = localBoardId,
            ),
            deviceAddress = address,
            deviceName = name,
            localBoardId = localBoardId,
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
            .putString(KEY_BATTERY_LEARNING_MODELS, JSONObject().apply {
                batteryLearningModels.forEach { (boardId, model) -> put(boardId, model.toJson()) }
            }.toString())
            .putString(KEY_BATTERY_ESTIMATES, batteryEstimates.toJson().toString())
            .putString("battery_charge_stats_v1", batteryChargeStats.toJson().toString())
            .putString("board_speed_bucket_stats_v1", speedBucketStats.toJson().toString())
            .putString("board_continuity_v1", JSONObject().apply {
                boardContinuity.forEach { (key, value) -> put(key, value.toJson()) }
            }.toString())
            .putString(KEY_HISTORY, JSONArray(history.map { it.toJson() }).toString())
            .putString(KEY_CALIBRATION, JSONArray(calibrationPoints.map { it.toJson() }).toString())
            .putString(
                KEY_ACTIVE_RANGE_WINDOWS,
                JSONArray(activeRangeWindows.values.map { it.toJson() }).toString(),
            )
            .putString(
                KEY_COMPLETED_RANGE_WINDOWS,
                JSONArray(completedRangeWindows.map { it.toJson() }).toString(),
            )
            .putString(
                KEY_ACTIVE_CHARGE_CYCLES,
                JSONArray(activeChargeCycles.values.map { it.toJson() }).toString(),
            )
            .putString(
                KEY_COMPLETED_CHARGE_CYCLES,
                JSONArray(completedChargeCycles.map { it.toJson() }).toString(),
            )
            .let { editor ->
                if (force) check(editor.commit()) { "Could not persist ride transition" } else editor.apply()
            }
    }

    private fun readBatteryLearningModels(): MutableMap<String, BatteryLearningModel> = runCatching {
        val value = JSONObject(preferences.getString(KEY_BATTERY_LEARNING_MODELS, "{}") ?: "{}")
        buildMap {
            value.keys().forEach { boardId ->
                value.optJSONObject(boardId)?.let { put(boardId, BatteryLearningModel.fromJson(it)) }
            }
        }.toMutableMap()
    }.getOrDefault(mutableMapOf())

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

    private fun readRangeWindowArray(key: String): MutableList<RangeDepletionWindow> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]") ?: "[]")
        MutableList(array.length()) { index ->
            rangeDepletionWindowFromJson(array.getJSONObject(index))
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
                var lastBatteryElapsedMs: Long? = null,
        var batteryModelAtMs: Long = 0L,
        var batteryConsumptionModel: RangeEstimate? = null,
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
        private const val KEY_BATTERY_LEARNING_MODELS = "battery_learning_models_v1"
        private const val KEY_BATTERY_ESTIMATES = "battery_estimates_v1"
        private const val KEY_LAST_COMPLETED_RIDE = "last_completed_ride"
        private const val KEY_HISTORY = "ride_history"
        private const val KEY_CALIBRATION = "calibration_points"
        private const val KEY_ACTIVE_RANGE_WINDOWS = "active_range_windows_v1"
        private const val KEY_COMPLETED_RANGE_WINDOWS = "completed_range_windows_v1"
        private const val KEY_ACTIVE_CHARGE_CYCLES = "active_charge_cycles_v1"
        private const val KEY_COMPLETED_CHARGE_CYCLES = "completed_charge_cycles_v1"
        private const val MAX_DISTANCE_INTEGRATION_GAP_MS = 2_000L
        private const val MOVING_SPEED_KMH = 1.0
        private const val RESTING_SPEED_KMH = 0.5
        private const val PERSIST_INTERVAL_MS = 5_000L
        private const val CALIBRATION_INTERVAL_MS = 10 * 60_000L
        private const val MAX_CALIBRATION_POINTS = 500
        private const val MAX_STORED_TRACKS = 1_000
        private const val RANGE_PROFILE_DISTANCE_KM = 100.0
        private const val MAX_STORED_RANGE_WINDOWS = 500
        private const val MAX_STORED_CHARGE_CYCLES = 500
        private const val FINALIZATION_REQUEST_CODE = 8102
        private const val MID_RIDE_START_THRESHOLD_KM = 0.05
        private val LOCK = Any()
        private val ARCHIVE_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "riders-hub-archive-finalizer").apply { priority = Thread.MIN_PRIORITY }
        }

        @Volatile
        @android.annotation.SuppressLint("StaticFieldLeak")
        private var instance: RideStore? = null

        fun get(context: Context): RideStore = instance ?: synchronized(LOCK) {
            instance ?: RideStore(context.applicationContext).also { instance = it }
        }
    }
}

internal fun retainRangeProfileHistory(
    newestFirst: List<RideSummary>,
    targetDistanceKm: Double = 100.0,
    maxCount: Int = 1_000,
): List<RideSummary> {
    if (targetDistanceKm <= 0.0 || maxCount <= 0) return emptyList()
    val retained = mutableListOf<RideSummary>()
    var retainedDistanceKm = 0.0
    newestFirst.forEach { ride ->
        if (retained.size >= maxCount || retainedDistanceKm >= targetDistanceKm) return@forEach
        retained += ride
        retainedDistanceKm += ride.distanceKm.coerceAtLeast(0.0)
    }
    return retained
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

internal fun authoritativeTripDistanceKm(
    remoteTripKm: Double,
    previousDistanceKm: Double,
): Double = remoteTripKm.takeIf { it.isFinite() && it >= 0.0 } ?: previousDistanceKm

internal fun RideSummary.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("started_at", startedAt)
    .putNullable("ended_at", endedAt)
    .putNullable("last_frame_at", lastFrameAt)
    .put("distance_km", distanceKm)
    .put("moving_seconds", movingSeconds)
    .put("max_speed_kmh", maxSpeedKmh)
    .putNullable("board_battery_start", boardBatteryStart)
    .putNullable("board_battery_end", boardBatteryEnd)
    .putNullable("estimated_battery_percent", estimatedBatteryPercent)
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
    .putNullable("local_board_id", localBoardId)

internal fun summaryFromJson(value: JSONObject): RideSummary = RideSummary(
    id = value.getString("id"),
    startedAt = value.getString("started_at"),
    endedAt = value.optNullableString("ended_at"),
    lastFrameAt = value.optNullableString("last_frame_at"),
    distanceKm = value.optDouble("distance_km", 0.0),
    movingSeconds = value.optDouble("moving_seconds", 0.0),
    maxSpeedKmh = value.optDouble("max_speed_kmh", 0.0),
    boardBatteryStart = value.optNullableInt("board_battery_start"),
    boardBatteryEnd = value.optNullableInt("board_battery_end"),
    estimatedBatteryPercent = value.optNullableDouble("estimated_battery_percent")
        ?.takeIf { it.isFinite() && it in 0.0..100.0 },
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
    localBoardId = value.optNullableString("local_board_id"),
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
    .put("estimated_speed_bucket_distances_km", bucketMapJson(estimatedSpeedBucketDistancesKm))

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
    estimatedSpeedBucketDistancesKm = value.readBucketMap("estimated_speed_bucket_distances_km"),
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

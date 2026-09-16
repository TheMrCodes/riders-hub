package at.themrcodes.ridershub.session

import at.themrcodes.ridershub.log.TelemetryArchive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/** Rebuild disposable analytics projections from immutable raw frames. */
internal object AnalyticsHistoryMigration {
    const val VERSION_KEY = "analytics_history_version"
    const val VERSION = "4"
    const val MODEL_VERSION = "digital-twin-replay-v2"
    const val MODEL_VERSION_KEY = "analytics_model_version"
    const val MANIFEST_KEY = "analytics_replay_manifest_v1"
    val derivedKeys = setOf("active_range_windows_v1", "completed_range_windows_v1",
        "active_charge_cycles_v1", "completed_charge_cycles_v1", "board_speed_bucket_stats_v1",
        "board_continuity_v1", "battery_charge_stats_v1", "battery_learning_models_v1",
        "battery_estimates_v1", MANIFEST_KEY)

    fun required(values: Map<String, String>, rawArchiveAvailable: Boolean = false) =
        ((values[VERSION_KEY]?.toIntOrNull() ?: 0) < VERSION.toInt() ||
            values[MODEL_VERSION_KEY] != MODEL_VERSION) &&
            (rawArchiveAvailable || (derivedKeys + "ride_history").any {
                values[it]?.let { s -> s != "[]" && s != "{}" } == true
            })

    data class Result(val replacement: Map<String, String>, val report: JSONObject)
    private data class Frame(val session: String, val board: String, val at: Instant, val seq: Long,
        val percent: Int, val speed: Double, val odo: Double, val voltage: Double, val loadRaw: Int,
        val valid: Boolean)
    private data class StreamFrame(
        val frame: Frame,
        val continuity: BoardContinuityUpdate,
        val confirmedRechargeAtStart: Int? = null,
    )

    private class Board {
        var window: RangeDepletionWindow? = null
        val windows = mutableListOf<RangeDepletionWindow>()
        var cycle: ChargeCycleSummary? = null
        val cycles = mutableListOf<ChargeCycleSummary>()
        var lastSession: String? = null
        var lastValidAtMs: Long? = null
        var batteryState: BatteryEstimateState? = null
        var learningModel = BatteryLearningModel()
        val observationTracker = BatteryObservationTracker()
        val learningTracker = BatteryLearningTracker()
        var rangeModel: RangeEstimate? = null
        var rangeModelAtMs: Long? = null
    }

    fun rebuild(directory: File, scratch: File, legacy: Map<String, String>): Result {
        val frames = mutableListOf<Frame>()
        val sessions = mutableMapOf<String, String>()
        val sessionStarts = mutableMapOf<String, Instant>()
        val sessionEnds = mutableMapOf<String, Instant>()
        val sourceDigest = MessageDigest.getInstance("SHA-256")
        var sourceRecords = 0L
        var session: String? = null
        var board: String? = null
        TelemetryArchive().forEachFinalizedRecordLine(directory, scratch) { line, row ->
            sourceDigest.update(line.toByteArray(Charsets.UTF_8))
            sourceDigest.update('\n'.code.toByte())
            sourceRecords++
            when (row.getString("type")) {
                "session_start" -> {
                    session = row.getString("session_id")
                    board = pseudonymousBoardId(row.getString("device_address"))
                    require(sessions.put(requireNotNull(session), requireNotNull(board)) == null) {
                        "Duplicate session identity"
                    }
                    sessionStarts[requireNotNull(session)] = Instant.parse(row.getString("wall_time"))
                }
                "session_end" -> {
                    require(row.getString("session_id") == session)
                    sessionEnds[requireNotNull(session)] = Instant.parse(row.getString("wall_time"))
                }
                "telemetry_frame" -> {
                    val p = row.getInt("board_battery_percent")
                    val v = row.getDouble("pack_voltage_v")
                    val speed = row.getDouble("speed_kmh")
                    val odo = row.getDouble("odometer_km")
                    frames += Frame(requireNotNull(session), requireNotNull(board),
                        Instant.parse(row.getString("wall_time")), row.getLong("seq"), p, speed, odo, v,
                        row.optInt("load_raw_signed_be", 0),
                        row.getBoolean("crc_valid") && p in 0..100 && v in 10.0..70.0 &&
                            speed.isFinite() && speed >= 0 && odo.isFinite() && odo >= 0)
                }
            }
        }
        val sourceSha256 = sourceDigest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        require(frames.any { it.valid }) { "No usable archived telemetry" }
        // A finalized archive must cover every retained trip, including its last observation.
        val rides = JSONArray(legacy["ride_history"] ?: "[]").let { array ->
            (0 until array.length()).map { summaryFromJson(array.getJSONObject(it)) }
        }
        rides.forEach { ride ->
            require(sessions.containsKey(ride.id) &&
                (ride.localBoardId == null || sessions[ride.id] == ride.localBoardId)) { "Archive does not cover retained trips" }
            val last = ride.lastFrameAt ?: ride.endedAt ?: ride.startedAt
            require(!requireNotNull(sessionEnds[ride.id]).isBefore(Instant.parse(last))) { "Archive ends before retained trip" }
        }
        val maxima = BatteryChargeStatsRegistry("{}")
        // Stable ordering keeps delivery order when frames share a millisecond.
        val ordered = frames.sortedWith(compareBy<Frame> { sessionStarts.getValue(it.session) }
            .thenBy { it.session }.thenBy { it.seq })
        val finalContinuity = mutableMapOf<String, BoardContinuityState>()
        val pendingChargeStart = mutableMapOf<String, Int>()
        val stream = mutableListOf<StreamFrame>()
        ordered.forEach { frame ->
            if (!frame.valid) {
                maxima.interrupted(frame.board)
                finalContinuity[frame.board] = finalContinuity[frame.board]
                    ?.copy(interrupted = true, chargeCandidateSeconds = 0.0)
                    ?: return@forEach
                pendingChargeStart.remove(frame.board)
                return@forEach
            }
            maxima.observe(frame.board, frame.percent, frame.at.toEpochMilli(), frame.odo)
            val update = BoardContinuityTracker.observe(finalContinuity[frame.board], frame.at.toEpochMilli(),
                frame.percent, frame.odo, frame.speed)
            val streamIndex = stream.size
            stream += StreamFrame(frame, update)
            when {
                update.chargePending -> pendingChargeStart.putIfAbsent(frame.board, streamIndex)
                update.rechargeIncreasePercent != null -> {
                    val start = pendingChargeStart.remove(frame.board) ?: streamIndex
                    stream[start] = stream[start].copy(
                        confirmedRechargeAtStart = update.rechargeIncreasePercent,
                    )
                }
                else -> pendingChargeStart.remove(frame.board)
            }
            finalContinuity[frame.board] = update.state
        }

        val boards = mutableMapOf<String, Board>()
        var stats = BoardSpeedBucketStatsRegistry("{}")
        var retainedGapKm = 0.0
        var chargeSpanningGapKm = 0.0
        var hardOdometerGapCount = 0
        stream.forEachIndexed { index, observation ->
            val frame = observation.frame
            val update = observation.continuity
            val b = boards.getOrPut(frame.board) { Board() }
            val at = frame.at.toString()
            val startsCharge = observation.confirmedRechargeAtStart != null
            val hardGap = update.calibrationGapReason != null &&
                update.calibrationGapReason != "first_observation" && !startsCharge
            if (update.calibrationGapReason == "unobserved_distance") hardOdometerGapCount++
            if (startsCharge) {
                chargeSpanningGapKm += update.odometerDeltaKm?.coerceAtLeast(0.0) ?: 0.0
            }
            val inferred = if (update.unprofiledDistanceKm > 0.0) {
                GapSpeedProfile.estimate(update.unprofiledDistanceKm, frame.board,
                    emptyList(), stats.snapshot(frame.board))
            } else emptyMap()
            retainedGapKm += update.unprofiledDistanceKm
            val measured = update.intervalSpeedKmh?.takeIf { update.intervalDistanceKm > 0 }?.let {
                mapOf(speedBucketStartKmh(it) to update.intervalDistanceKm)
            } ?: emptyMap()
            val unattributedDistance = if (startsCharge || hardGap) {
                update.odometerDeltaKm?.coerceAtLeast(0.0) ?: 0.0
            } else {
                (update.unprofiledDistanceKm - inferred.values.sum()).coerceAtLeast(0.0)
            }
            stats.recordDistance(frame.board, measured, inferred, unattributedDistance)
            if (startsCharge || hardGap || update.calibrationGapReason == "first_observation") b.window = null
            run {
                val distance = if (startsCharge || hardGap) 0.0
                    else update.intervalDistanceKm + update.unprofiledDistanceKm
                val profile = update.intervalSpeedKmh?.takeIf { distance > 0 }?.let {
                    mapOf(speedBucketStartKmh(it) to distance)
                } ?: emptyMap()
                val range = requireNotNull(RangeCalibrationTracker.observe(b.window, frame.board,
                    frame.percent, at, distance, profile, false, inferred))
                b.window = range.activeWindow
                range.completedWindow?.let {
                    b.windows += it
                    stats.recordWindow(frame.board, it)
                    b.rangeModel = null
                    b.rangeModelAtMs = null
                }
                val json = JSONObject().put("id", "stream-$at").put("started_at", at).put("ended_at", at)
                    .put("last_frame_at", at).put("log_file", "").put("local_board_id", frame.board)
                    .put("distance_km", distance).put("board_battery_start", frame.percent)
                    .put("board_battery_end", frame.percent).put("board_battery_min", frame.percent)
                    .put("odometer_start_km", frame.odo).put("odometer_end_km", frame.odo)
                    .put("moving_seconds", if (distance > 0 && (update.intervalSpeedKmh ?: 0.0) >= 1.0)
                        distance * 3600 / requireNotNull(update.intervalSpeedKmh) else 0.0)
                    .put("speed_bucket_distances_km", JSONObject().apply { profile.forEach { (key, value) -> put(key.toString(), value) } })
                if (frame.speed <= 0.5) json.put("resting_voltage_start", frame.voltage).put("resting_voltage_end", frame.voltage)
                val charge = ChargeCycleTracker.recordFrame(b.cycle, summaryFromJson(json), frame.board,
                    observation.confirmedRechargeAtStart, b.lastSession != frame.session, inferred)
                b.cycle = charge.activeCycle
                charge.completedCycle?.let { b.cycles += it }
            }

            // Replay the live estimator from an empty state. Models are refreshed on completed
            // windows and at the same ten-second cadence used by the live path; no legacy learned
            // parameter or battery estimate is admitted as input.
            val frameAtMs = frame.at.toEpochMilli()
            val refreshModel = b.rangeModel == null || b.rangeModelAtMs == null ||
                frameAtMs - requireNotNull(b.rangeModelAtMs) >= 10_000L
            if (refreshModel) {
                b.rangeModel = RangeEstimator.estimate(
                    currentBatteryPercent = frame.percent,
                    currentRestingVoltageV = null,
                    depletionWindows = b.windows,
                    profileSessions = emptyList(),
                    calibrationPoints = emptyList(),
                    estimatedBatteryPercent = b.batteryState?.percent,
                    selectedBoardId = frame.board,
                    speedBucketStatistics = stats.snapshot(frame.board),
                )
                b.rangeModelAtMs = frameAtMs
            }
            val model = b.rangeModel
            val continuous = !startsCharge && update.gapReason == null && b.lastValidAtMs != null
            val elapsedSeconds = if (continuous) {
                (frameAtMs - requireNotNull(b.lastValidAtMs)) / 1000.0
            } else 0.0
            val intervalDistance = if (continuous) update.intervalDistanceKm else 0.0
            val baseRate = update.intervalSpeedKmh?.takeIf { continuous }?.let { speed ->
                model?.bucketBatteryPercentPer100Km?.get(speedBucketStartKmh(speed))?.div(100.0)
            } ?: model?.kmPerPercent?.let { 1.0 / it }
            val appliedRate = b.learningModel.rate(baseRate)
            val observationEstimate = b.observationTracker.observeWithDiagnostics(
                state = b.batteryState,
                reportedPercent = frame.percent,
                elapsedSeconds = elapsedSeconds,
                distanceKm = intervalDistance,
                batteryPercentPerKm = appliedRate,
                voltageV = frame.voltage,
                loadRaw = frame.loadRaw,
            )
            val inferredGapBase = model?.let {
                DigitalTwinDriveModel.inferredTransition(
                    inferredSpeedBucketDistancesKm = inferred,
                    bucketPercentPer100Km = it.bucketBatteryPercentPer100Km,
                    fallbackKmPerPercent = it.kmPerPercent,
                )
            }
            val inferredGapTransition = inferredGapBase?.let { transition ->
                b.learningModel.rate(transition.effectivePercentPerKm)?.let { learnedRate ->
                    InferredDriveTransition(
                        distanceKm = transition.distanceKm,
                        expectedBatteryUsePercent = transition.distanceKm * learnedRate,
                        effectivePercentPerKm = learnedRate,
                    )
                }
            }
            val gapStep = inferredGapTransition?.let {
                BatteryPercentEstimator.propagateInferredDrive(
                    previousPercent = observationEstimate.state.percent,
                    reportedPercent = frame.percent,
                    transition = it,
                )
            }
            b.batteryState = if (gapStep == null) observationEstimate.state
                else observationEstimate.state.copy(percent = gapStep.percent)
            b.learningModel = b.learningTracker.observe(
                model = b.learningModel,
                reportedPercent = frame.percent,
                elapsedSeconds = elapsedSeconds,
                intervalDistanceKm = intervalDistance,
                basePercentPerKm = baseRate,
                reliableObservation = observationEstimate.diagnostics.settled,
            )
            b.lastValidAtMs = frameAtMs
            b.lastSession = frame.session
            if (index % 10000 == 0) {
                val snapshots = boards.keys.associateWith { stats.snapshot(it) }
                stats = BoardSpeedBucketStatsRegistry(stats.toJson().toString())
                require(snapshots.all { (id, snapshot) -> stats.snapshot(id) == snapshot }) { "Statistics changed after serialization" }
            }
        }
        val knownBoards = derivedKeys.filter { it.endsWith("cycles_v1") || it.endsWith("windows_v1") }
            .flatMap { key -> JSONArray(legacy[key] ?: "[]").let { a ->
                (0 until a.length()).map { a.getJSONObject(it).getString("local_board_id") }
            } }.toSet()
        require(boards.keys.containsAll(knownBoards)) { "Archive does not cover every stored board" }
        derivedKeys.filter { it.endsWith("cycles_v1") || it.endsWith("windows_v1") }.forEach { key ->
            val array = JSONArray(legacy[key] ?: "[]")
            repeat(array.length()) { i ->
                val old = array.getJSONObject(i)
                val own = sessions.filterValues { it == old.getString("local_board_id") }.keys
                val first = own.minOf { sessionStarts.getValue(it) }
                val last = own.maxOf { sessionEnds.getValue(it) }
                require(!Instant.parse(old.getString("started_at")).isBefore(first) &&
                    !Instant.parse(old.getString("last_observed_at")).isAfter(last)) { "Archive does not cover legacy analytics interval" }
            }
        }
        val manifest = JSONObject()
            .put("schema_version", 1)
            .put("projection_version", VERSION.toInt())
            .put("model_version", MODEL_VERSION)
            .put("source_sha256", sourceSha256)
            .put("source_records", sourceRecords)
            .put("sessions", sessions.size)
            .put("frames", frames.size)
            .put("valid_frames", frames.count { it.valid })
            .put("boards", boards.size)
        val replacement = linkedMapOf(
            "active_range_windows_v1" to JSONArray(boards.values.mapNotNull { it.window?.toJson() }).toString(),
            "completed_range_windows_v1" to JSONArray(boards.values.flatMap { it.windows }.sortedByDescending { it.closedAt }.take(500).map { it.toJson() }).toString(),
            "active_charge_cycles_v1" to JSONArray(boards.values.mapNotNull { it.cycle?.toJson() }).toString(),
            "completed_charge_cycles_v1" to JSONArray(boards.values.flatMap { it.cycles }.sortedByDescending { it.closedAt }.take(500).map { it.toJson() }).toString(),
            "board_speed_bucket_stats_v1" to stats.toJson().toString(),
            "battery_charge_stats_v1" to maxima.toJson().toString(),
            "board_continuity_v1" to JSONObject().apply {
                finalContinuity.forEach { (id, continuity) -> put(id, continuity.toJson()) }
            }.toString(),
            "battery_learning_models_v1" to JSONObject().apply {
                boards.forEach { (id, value) -> put(id, value.learningModel.toJson()) }
            }.toString(),
            "battery_estimates_v1" to JSONObject().apply {
                boards.forEach { (id, value) -> value.batteryState?.let { put(id, it.toJson()) } }
            }.toString(),
            MANIFEST_KEY to manifest.toString(),
            MODEL_VERSION_KEY to MODEL_VERSION,
            VERSION_KEY to VERSION,
        )
        val report = JSONObject().put("schema_version", 2)
            .put("replay", JSONObject(manifest.toString()))
            .put("previous_projection", JSONObject()
                .put("projection_version", legacy[VERSION_KEY]?.toIntOrNull() ?: 0)
                .put("model_version", legacy[MODEL_VERSION_KEY] ?: JSONObject.NULL))
            .put("sessions", sessions.size)
            .put("frames", frames.size).put("valid_frames", frames.count { it.valid })
            .put("stream", JSONObject()
                .put("confirmed_charge_boundaries", stream.count { it.confirmedRechargeAtStart != null })
                .put("hard_odometer_gaps", hardOdometerGapCount)
                .put("retained_sub_2km_gap_km", retainedGapKm)
                .put("charge_spanning_gap_km", chargeSpanningGapKm))
            .put("boards", JSONArray().apply { boards.forEach { (id, b) ->
                val snapshot = requireNotNull(stats.snapshot(id))
                val longevity = BatteryLongevityEstimator.estimate(ChargeCycleStoreSnapshot(listOfNotNull(b.cycle), b.cycles), id, null, snapshot)
                val rangeAtForty = RangeEstimator.estimate(
                    currentBatteryPercent = 40,
                    currentRestingVoltageV = null,
                    depletionWindows = b.windows,
                    profileSessions = emptyList(),
                    calibrationPoints = emptyList(),
                    estimatedBatteryPercent = 40.0,
                    selectedBoardId = id,
                    speedBucketStatistics = snapshot,
                )
                put(JSONObject().put("board_id", id).put("windows", b.windows.size)
                    .put("observed_charges", longevity.observedCycleCount).put("usable_charges", longevity.usableCycleCount)
                    .put("full_range_km", longevity.currentFullRangeKm ?: JSONObject.NULL)
                    .put("forecast_range_at_40_percent_km", rangeAtForty.remainingKm ?: JSONObject.NULL)
                    .put("forecast_full_battery_km", rangeAtForty.kmPerPercent?.times(100.0) ?: JSONObject.NULL)
                    .put("forecast_status", rangeAtForty.status.name)
                    .put("estimated_battery_percent", b.batteryState?.percent ?: JSONObject.NULL)
                    .put("estimated_battery_reference_percent", b.batteryState?.referencePercent ?: JSONObject.NULL)
                    .put("estimated_battery_error_to_reported_percent",
                        b.batteryState?.let { state ->
                            finalContinuity[id]?.lastReportedPercent?.let { state.percent - it }
                        } ?: JSONObject.NULL)
                    .put("learning_correction_factor", b.learningModel.correctionFactor)
                    .put("learning_percent_per_km", b.learningModel.learnedPercentPerKm ?: JSONObject.NULL)
                    .put("learning_accepted_windows", b.learningModel.acceptedWindows)
                    .put("learning_fallback_active", b.learningModel.hasActiveFallback)
                    .put("estimated_total_cycles", longevity.cycleAge?.estimatedLifetimeFullCycles ?: JSONObject.NULL)
                    .put("measured_km", snapshot.measuredDistanceKm).put("inferred_km", snapshot.inferredDistanceKm)
                    .put("unattributed_km", snapshot.unattributedDistanceKm)
                    .put("accounted_km", snapshot.accountedDistanceKm)
                    .put("buckets", JSONArray().apply { snapshot.buckets.forEach { bucket ->
                        put(JSONObject().put("start_kmh", bucket.startKmh)
                            .put("percent_per_km", bucket.batteryPercentPer100Km?.div(100.0) ?: JSONObject.NULL)
                            .put("usable_for_forecast", bucket.hasUsableConsumptionSupport)
                            .put("measured_km", bucket.measuredDistanceKm)
                            .put("calibration_measured_km", bucket.calibrationMeasuredKm)
                            .put("contributing_windows", bucket.contributingWindows)
                            .put("model_limit_reached", bucket.modelLimitReached))
                    } }))
            } })
        val oldStats = BoardSpeedBucketStatsRegistry(legacy["board_speed_bucket_stats_v1"] ?: "{}")
        val oldEstimates = BatteryEstimateRegistry(legacy["battery_estimates_v1"] ?: "{}")
        val oldLearning = runCatching {
            JSONObject(legacy["battery_learning_models_v1"] ?: "{}")
        }.getOrDefault(JSONObject())
        fun records(key: String) = JSONArray(legacy[key] ?: "[]").let { a -> (0 until a.length()).map(a::getJSONObject) }
        val oldWindows = (records("completed_range_windows_v1") + records("active_range_windows_v1")).map(::rangeDepletionWindowFromJson)
        val oldCycles = ChargeCycleStoreSnapshot(records("active_charge_cycles_v1").map(::chargeCycleFromJson),
            records("completed_charge_cycles_v1").map(::chargeCycleFromJson))
        report.put("before", JSONArray().apply { boards.keys.forEach { id ->
            oldStats.seed(id, rides, oldWindows)
            val snapshot = oldStats.snapshot(id)
            val longevity = BatteryLongevityEstimator.estimate(oldCycles, id, null, snapshot)
            val oldLearningModel = oldLearning.optJSONObject(id)?.let(BatteryLearningModel::fromJson)
            put(JSONObject().put("board_id", id).put("windows", oldWindows.count { it.localBoardId == id && it.closedAt != null })
                .put("observed_charges", longevity.observedCycleCount).put("usable_charges", longevity.usableCycleCount)
                .put("full_range_km", longevity.currentFullRangeKm ?: JSONObject.NULL)
                .put("estimated_battery_percent", oldEstimates.state(id)?.percent ?: JSONObject.NULL)
                .put("learning_correction_factor", oldLearningModel?.correctionFactor ?: JSONObject.NULL)
                .put("learning_percent_per_km", oldLearningModel?.learnedPercentPerKm ?: JSONObject.NULL)
                .put("learning_accepted_windows", oldLearningModel?.acceptedWindows ?: JSONObject.NULL)
                .put("estimated_total_cycles", longevity.cycleAge?.estimatedLifetimeFullCycles ?: JSONObject.NULL)
                .put("buckets", JSONArray().apply { snapshot?.buckets?.forEach { bucket ->
                    put(JSONObject().put("start_kmh", bucket.startKmh)
                        .put("percent_per_km", bucket.batteryPercentPer100Km?.div(100.0) ?: JSONObject.NULL)
                        .put("usable_for_forecast", bucket.hasUsableConsumptionSupport)
                        .put("model_limit_reached", bucket.modelLimitReached))
                } }))
        } })
        return Result(replacement, report)
    }
}

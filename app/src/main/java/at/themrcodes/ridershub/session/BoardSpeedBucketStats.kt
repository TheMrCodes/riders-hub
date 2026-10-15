package at.themrcodes.ridershub.session

import org.json.JSONObject
import kotlin.math.sqrt

internal const val MIN_SUPPORTED_SPEED_BUCKET_KM = 1.0
internal const val MIN_PROFILE_SPEED_BUCKET_KM = 5.0

/** Cumulative observations, not a probability that a consumption estimate is correct. */
data class SpeedBucketStatistics(
    val startKmh: Int,
    val measuredDistanceKm: Double,
    val distanceSharePercent: Double,
    val inferredDistanceKm: Double,
    val calibrationMeasuredKm: Double,
    val calibrationInferredKm: Double,
    val contributingWindows: Long,
    val batteryPercentPer100Km: Double?,
    val fullBatteryRangeKm: Double?,
    val consumptionSharePercent: Double?,
    val modelLimitReached: Boolean,
) {
    // Measured distance is deliberately the simple confidence proxy, not a confidence percentage.
    val confidenceKm: Double get() = measuredDistanceKm
    val hasUsableDistanceSupport: Boolean
        get() = confidenceKm.isFinite() && confidenceKm >= MIN_SUPPORTED_SPEED_BUCKET_KM
    // Presentation gate: measured kilometres remain the confidence proxy; inferred gaps do not count.
    val hasUsableConsumptionSupport: Boolean
        get() = hasUsableDistanceSupport && calibrationMeasuredKm.isFinite() &&
            calibrationMeasuredKm > 0.0 && contributingWindows > 0 &&
            !modelLimitReached && batteryPercentPer100Km?.let { it.isFinite() && it > 0.0 } == true
}

data class BoardSpeedBucketStatistics(
    val localBoardId: String,
    val buckets: List<SpeedBucketStatistics>,
    val unattributedDistanceKm: Double,
    val completedWindows: Long,
    val observedDepletionPercent: Double,
    val seededFromRetainedHistory: Boolean,
    val observedDistanceKm: Double = 0.0,
    val rollingPercentPerKm: Double? = null,
    val aerodynamicPercentPerKmPerKmhSquared: Double? = null,
    val unconstrainedRollingCoefficient: Double? = null,
    val unconstrainedAerodynamicCoefficientPerKmhSquared: Double? = null,
    val rollingCoefficientStandardError: Double? = null,
    val aerodynamicCoefficientStandardErrorPerKmhSquared: Double? = null,
    val shapeReferenceSpeedKmh: Double? = null,
    val aerodynamicShareAtReference: Double? = null,
    val shapeConditionNumber: Double? = null,
    val shapeRSquared: Double? = null,
    val shapeEvidenceKm: Double = 0.0,
    val shapeSupportedBucketCount: Int = 0,
    val shapeRefits: Long = 0,
    /** Physics-informed frame model; null until fitted with at least two motion windows. */
    val physicsShape: PhysicsShape? = null,
    /** Average braked-work cost per riding kilometre, added to every cruise bucket rate. */
    val stopGoPercentPerKm: Double = 0.0,
    val hillPercentPerKm: Double = 0.0,
    val climbMPerKm: Double? = null,
    val barometricShare: Double = 0.0,
    val physicsWindows: Long = 0,
    /** Stated pack energy and the learned multiplier on its depletion scale (1 = as stated). */
    val nominalBatteryWh: Double? = null,
    val packEnergyFactor: Double? = null,
) {
    /** Energy the pack actually delivers across the remote's 0 to 100 percent. */
    val effectiveBatteryWh: Double?
        get() = if (nominalBatteryWh != null && packEnergyFactor != null) nominalBatteryWh / packEnergyFactor else null

    /** Cruise rates exclude stop-go and hill braking; consumers forecast with the sum. */
    val overheadPercentPerKm: Double get() = stopGoPercentPerKm + hillPercentPerKm

    val measuredDistanceKm: Double get() = buckets.sumOf { it.measuredDistanceKm }
    val inferredDistanceKm: Double get() = buckets.sumOf { it.inferredDistanceKm }
    /** Distance evidence ledger; unattributed kilometres remain known without fabricated buckets. */
    val attributedDistanceKm: Double get() = measuredDistanceKm + inferredDistanceKm
    val accountedDistanceKm: Double get() = attributedDistanceKm + unattributedDistanceKm

    /** Chart-only selection: trim sparse tails and holes without changing the evidence ledger. */
    internal fun profileBuckets(energyUse: Boolean): List<SpeedBucketStatistics> {
        val supported = buckets.filter {
            it.startKmh >= 0 && it.startKmh % SPEED_BUCKET_WIDTH_KMH == 0 &&
                it.measuredDistanceKm.isFinite() && it.measuredDistanceKm >= MIN_PROFILE_SPEED_BUCKET_KM &&
                if (energyUse) it.hasUsableConsumptionSupport else it.hasUsableDistanceSupport
        }.sortedBy { it.startKmh }
        val distanceKm = supported.sumOf { it.measuredDistanceKm }
        return supported.map { it.copy(distanceSharePercent = 100.0 * it.measuredDistanceKm / distanceKm) }
    }

    internal fun measuredProfile(): Map<Int, Double> = buckets.filter {
        it.startKmh >= 0 && it.startKmh % SPEED_BUCKET_WIDTH_KMH == 0 &&
            it.measuredDistanceKm.isFinite() && it.measuredDistanceKm > 0.0
    }.associate { it.startKmh to it.measuredDistanceKm }

    /** Only supported rates may influence the battery twin or remaining-range forecast. */
    internal fun consumptionRates(): Map<Int, Double> = buckets.filter {
        it.startKmh >= 0 && it.startKmh % SPEED_BUCKET_WIDTH_KMH == 0 &&
            it.hasUsableConsumptionSupport
    }.associate { it.startKmh to requireNotNull(it.batteryPercentPer100Km) / 100.0 + overheadPercentPerKm }
}

/**
 * Board-scoped lifetime-since-collection aggregates. Window sufficient statistics retain the
 * weighted least-squares fit after source windows are trimmed, without retaining raw telemetry.
 * Shared by range and capacity analytics; recent-window fitting remains a legacy fallback.
 */
internal class BoardSpeedBucketStatsRegistry(encoded: String = "{}") {
    private class Totals {
        val measured = mutableMapOf<Int, Double>()
        val inferred = mutableMapOf<Int, Double>()
        val support = mutableMapOf<Int, Double>()
        val inferredSupport = mutableMapOf<Int, Double>()
        val counts = mutableMapOf<Int, Long>()
        val cross = mutableMapOf<Int, MutableMap<Int, Double>>()
        val target = mutableMapOf<Int, Double>()
        val fitExposure = mutableMapOf<Int, Double>()
        var fitDepletion = 0.0
        var fitSquaredDepletion = 0.0
        var fitWindowWeight = 0.0
        var fitAcceptedWindows = 0L
        var rawRollingCoefficient = 1.0
        var rawAerodynamicCoefficient = 0.0
        var unconstrainedRollingCoefficient: Double? = null
        var unconstrainedAerodynamicCoefficient: Double? = null
        var rollingCoefficientStandardError: Double? = null
        var aerodynamicCoefficientStandardError: Double? = null
        var shapeReferenceSpeedKmh: Double? = null
        var shapeConditionNumber: Double? = null
        var shapeRSquared: Double? = null
        var shapeEvidenceKm = 0.0
        var shapeSupportedBucketCount = 0
        var shapeRefits = 0L
        var lastShapeTripId: String? = null
        var unattributed = 0.0
        var distance = 0.0
        var depletion = 0.0
        var windows = 0L
        var seeded = false
        var lastWindowId: String? = null
        // Same weighting as the fit totals, restricted to windows that carry aligned motion.
        val physicsExposure = mutableMapOf<Int, Double>()
        var physicsDepletion = 0.0
        var physicsMotion = WindowMotion.ZERO
        var physicsWindows = 0L
    }
    private val boards = mutableMapOf<String, Totals>()
    private val physicsModels = mutableMapOf<String, BoardPhysicsModel>()

    init {
        val json = runCatching { JSONObject(encoded) }.getOrDefault(JSONObject())
        json.optJSONObject(PHYSICS_KEY)?.let { models ->
            models.keys().forEach { board -> physicsModels[board] = BoardPhysicsModel.fromJson(models.optJSONObject(board)) }
        }
        json.keys().forEach { board ->
            if (board == PHYSICS_KEY) return@forEach
            runCatching {
                val j = json.getJSONObject(board)
                require(j.getInt("schema_version") == 1)
                val t = Totals()
                fun read(key: String, into: MutableMap<Int, Double>) {
                    val map = j.getJSONObject(key)
                    map.keys().forEach { k ->
                        val bucket = k.toInt()
                        val value = map.getDouble(k)
                        require(validBucket(bucket) && value.isFinite() && value >= 0.0)
                        into[bucket] = value
                    }
                }
                read("measured_km", t.measured); read("inferred_km", t.inferred)
                read("calibration_measured_km", t.support); read("calibration_inferred_km", t.inferredSupport)
                read("fit_target", t.target)
                val hasFitTotals = j.has("fit_exposure_km") && j.has("fit_depletion_percent")
                if (hasFitTotals) read("fit_exposure_km", t.fitExposure)
                val cross = j.getJSONObject("fit_cross")
                cross.keys().forEach { key ->
                    val bucket = key.toInt()
                    require(validBucket(bucket))
                    val row = cross.getJSONObject(key)
                    t.cross[bucket] = mutableMapOf<Int, Double>().apply {
                        row.keys().forEach { column ->
                            val other = column.toInt(); val value = row.getDouble(column)
                            require(validBucket(other) && value.isFinite() && value >= 0.0)
                            put(other, value)
                        }
                    }
                }
                val counts = j.getJSONObject("contributing_windows")
                counts.keys().forEach { key ->
                    val bucket = key.toInt(); val count = counts.getLong(key)
                    require(validBucket(bucket) && count >= 0)
                    t.counts[bucket] = count
                }
                fun number(key: String) = j.getDouble(key).also { require(it.isFinite() && it >= 0) }
                // The durable key predates the clearer "unattributed" terminology.
                t.unattributed = number("unassigned_km"); t.distance = number("window_distance_km")
                t.depletion = number("observed_depletion_percent")
                if (hasFitTotals) {
                    t.fitDepletion = number("fit_depletion_percent")
                } else {
                    // Older projections did not persist the weighted exposure. They are normally
                    // rebuilt from raw frames after a model-version change; retain a conservative
                    // readable approximation for installations without an archive.
                    add(t.fitExposure, t.support)
                    add(t.fitExposure, t.inferredSupport)
                    val covered = t.fitExposure.values.sum()
                    t.fitDepletion = if (t.distance > 0.0) {
                        t.depletion * (covered / t.distance).coerceIn(0.0, 1.0)
                    } else 0.0
                }
                t.fitSquaredDepletion = j.optDouble("fit_squared_depletion_percent", 0.0)
                    .takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                t.fitWindowWeight = j.optDouble("fit_window_weight", 0.0)
                    .takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                t.fitAcceptedWindows = j.optLong("fit_accepted_windows", 0L).coerceAtLeast(0L)
                t.rawRollingCoefficient = j.optDouble("quadratic_raw_rolling", 1.0)
                    .takeIf { it.isFinite() && it >= 0.0 } ?: 1.0
                t.rawAerodynamicCoefficient = j.optDouble("quadratic_raw_aerodynamic", 0.0)
                    .takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                t.unconstrainedRollingCoefficient = j.optionalFinite("quadratic_unconstrained_rolling")
                t.unconstrainedAerodynamicCoefficient =
                    j.optionalFinite("quadratic_unconstrained_aerodynamic")
                t.rollingCoefficientStandardError =
                    j.optionalFinite("quadratic_rolling_standard_error")?.takeIf { it >= 0.0 }
                t.aerodynamicCoefficientStandardError =
                    j.optionalFinite("quadratic_aerodynamic_standard_error")?.takeIf { it >= 0.0 }
                t.shapeReferenceSpeedKmh = j.optionalFinite("quadratic_reference_speed_kmh")
                    ?.takeIf { it > 0.0 }
                t.shapeConditionNumber = j.optionalFinite("quadratic_condition_number")
                    ?.takeIf { it >= 1.0 }
                t.shapeRSquared = j.optionalFinite("quadratic_r_squared")
                t.shapeEvidenceKm = j.optDouble("quadratic_evidence_km", 0.0)
                    .takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                t.shapeSupportedBucketCount = j.optInt("quadratic_supported_bucket_count", 0)
                    .coerceAtLeast(0)
                t.shapeRefits = j.optLong("quadratic_refits", 0L).coerceAtLeast(0L)
                t.lastShapeTripId = if (j.has("last_quadratic_trip_id") &&
                    !j.isNull("last_quadratic_trip_id")) j.getString("last_quadratic_trip_id") else null
                t.windows = j.getLong("completed_windows").also { require(it >= 0) }
                t.seeded = j.getBoolean("seeded_from_retained_history")
                t.lastWindowId = if (j.isNull("last_window_id")) null else j.getString("last_window_id")
                j.optJSONObject("physics_exposure_km")?.let { read("physics_exposure_km", t.physicsExposure) }
                t.physicsDepletion = j.optDouble("physics_depletion_percent", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                t.physicsMotion = WindowMotion.fromJson(j.optJSONObject("physics_motion"))
                t.physicsWindows = j.optLong("physics_windows", 0L).coerceAtLeast(0L)
                boards[board] = t
            }
        }
    }

    /** Seed once, using only this board's retained records. Missing older history is not invented. */
    fun seed(board: String, rides: List<RideSummary>, windows: List<RangeDepletionWindow>) {
        if (boards.containsKey(board)) return
        val ownRides = rides.filter { it.localBoardId == board }.distinctBy { it.id }
        val ownWindows = windows.filter { it.localBoardId == board }.distinctBy { it.id }
        boards[board] = Totals().apply { seeded = ownRides.isNotEmpty() || ownWindows.isNotEmpty() }
        ownRides.forEach { recordDistance(board, it.speedBucketDistancesKm) }
        // Gap profiles are not in legacy ride summaries. Windows preserve the known allocation.
        ownWindows.forEach { recordDistance(board, inferred = it.estimatedSpeedBucketDistancesKm) }
        ownWindows.filter { it.closedAt != null }.sortedBy { it.closedAt }.forEach { recordWindow(board, it) }
        refitShape(board)
    }

    fun recordDistance(
        board: String,
        measured: Map<Int, Double> = emptyMap(),
        inferred: Map<Int, Double> = emptyMap(),
        unattributedKm: Double = 0.0,
    ) {
        val t = boards.getOrPut(board) { Totals() }
        add(t.measured, measured); add(t.inferred, inferred)
        if (unattributedKm.isFinite() && unattributedKm > 0) t.unattributed += unattributedKm
    }

    /** Called once on each newly completed stream window, before history retention runs. */
    fun recordWindow(board: String, window: RangeDepletionWindow) {
        if (window.localBoardId != board || window.closedAt == null ||
            window.observedDepletionPercent < RangeCalibrationTracker.DEPLETION_STEP_PERCENT ||
            !window.recordedDistanceKm.isFinite() || window.recordedDistanceKm <= 0) return
        val t = boards.getOrPut(board) { Totals() }
        if (t.lastWindowId == window.id) return
        t.lastWindowId = window.id
        t.windows++
        t.distance += window.recordedDistanceKm
        t.depletion += window.observedDepletionPercent
        val measured = clean(window.speedBucketDistancesKm)
        val inferred = clean(window.estimatedSpeedBucketDistancesKm)
        val effective = measured.toMutableMap().apply { add(this, inferred) }
        val weight = (measured.values.sum() / window.recordedDistanceKm).coerceIn(0.0, 1.0)
        if (weight <= 0 || effective.values.sum() < window.recordedDistanceKm * 0.95) return
        add(t.support, measured); add(t.inferredSupport, inferred)
        t.fitDepletion += weight * window.observedDepletionPercent
        t.fitSquaredDepletion += weight * window.observedDepletionPercent *
            window.observedDepletionPercent
        t.fitWindowWeight += weight
        t.fitAcceptedWindows++
        effective.forEach { (b, x) ->
            t.fitExposure[b] = (t.fitExposure[b] ?: 0.0) + weight * x
            t.counts[b] = (t.counts[b] ?: 0) + 1
            t.target[b] = (t.target[b] ?: 0.0) + weight * x * window.observedDepletionPercent
            val row = t.cross.getOrPut(b) { mutableMapOf() }
            effective.forEach { (c, y) -> row[c] = (row[c] ?: 0.0) + weight * x * y }
        }
        if (!window.motion.isEmpty) {
            effective.forEach { (b, x) -> t.physicsExposure[b] = (t.physicsExposure[b] ?: 0.0) + weight * x }
            t.physicsDepletion += weight * window.observedDepletionPercent
            t.physicsMotion += window.motion.scaled(weight)
            t.physicsWindows++
        }
    }

    /** Durable frame learner; created on demand without creating bucket totals. */
    fun physicsModel(board: String): BoardPhysicsModel = physicsModels.getOrPut(board) { BoardPhysicsModel() }

    /**
     * Refit once at trip completion from the complete cumulative sufficient statistics. The trip
     * itself never supplies a direct parameter update; it only changes the aggregate evidence.
     */
    fun completeTrip(board: String, tripId: String) {
        physicsModels[board]?.completeTrip(tripId)
        val t = boards[board] ?: return
        if (t.lastShapeTripId == tripId) return
        t.lastShapeTripId = tripId
        refitShape(board)
    }

    private fun refitShape(board: String) {
        val t = boards[board] ?: return
        val supported = t.fitExposure.keys.filterTo(sortedSetOf()) { bucket ->
            (t.measured[bucket] ?: 0.0) >= MIN_SUPPORTED_SPEED_BUCKET_KM &&
                (t.support[bucket] ?: 0.0) > 0.0 && (t.counts[bucket] ?: 0L) > 0L
        }
        val fit = SpeedBucketQuadraticFit.fit(
            exposureKm = t.fitExposure,
            target = t.target,
            cross = t.cross,
            supportedBuckets = supported,
            weightedDepletion = t.fitDepletion,
            weightedSquaredDepletion = t.fitSquaredDepletion,
            windowWeight = t.fitWindowWeight,
            acceptedWindows = t.fitAcceptedWindows,
        )
        t.rawRollingCoefficient = fit.rollingCoefficient
        t.rawAerodynamicCoefficient = fit.aerodynamicCoefficientPerKmhSquared
        t.unconstrainedRollingCoefficient = fit.unconstrainedRollingCoefficient
        t.unconstrainedAerodynamicCoefficient =
            fit.unconstrainedAerodynamicCoefficientPerKmhSquared
        t.rollingCoefficientStandardError = fit.rollingCoefficientStandardError
        t.aerodynamicCoefficientStandardError =
            fit.aerodynamicCoefficientStandardErrorPerKmhSquared
        t.shapeReferenceSpeedKmh = fit.referenceSpeedKmh
        t.shapeConditionNumber = fit.conditionNumber
        t.shapeRSquared = fit.rSquared
        t.shapeEvidenceKm = fit.evidenceKm
        t.shapeSupportedBucketCount = fit.supportedBucketCount
        t.shapeRefits++
    }

    private fun rates(t: Totals): SpeedBucketConsumptionFit.Result {
        val fitDistance = t.fitExposure.values.sum()
        if (t.distance < 1 || t.depletion < 5 || t.distance / t.depletion !in 0.01..1.5 ||
            fitDistance <= 0.0 || t.fitDepletion <= 0.0
        ) return SpeedBucketConsumptionFit.Result.EMPTY
        return SpeedBucketConsumptionFit.quadratic(
            t.fitExposure,
            t.fitDepletion,
            t.rawRollingCoefficient,
            t.rawAerodynamicCoefficient,
        )
    }

    private class PhysicsRates(
        val fit: SpeedBucketConsumptionFit.Result,
        val shape: PhysicsShape,
        val stopGoPercentPerKm: Double,
        val hillPercentPerKm: Double,
        val climbMPerKm: Double?,
        val barometricShare: Double,
        val packEnergyFactor: Double,
    )

    /**
     * Depletion scale on per-mass work: the profile's mass and pack energy give the nominal scale,
     * and one pack-energy factor learned from depletion windows corrects it. Cruise rates follow
     * the frame-fitted physics; braked work, net kinetic and net height changes complete the
     * identity used for the factor.
     */
    private fun physicsRates(
        t: Totals,
        board: String,
        keys: Collection<Int>,
        profile: RiderBoardProfile,
    ): PhysicsRates? {
        val shape = physicsModels[board]?.shape() ?: return null
        // Sorted summation keeps the snapshot bit-identical across a JSON round trip.
        val exposure = t.physicsExposure.toSortedMap()
        val exposureKm = exposure.values.sum()
        val eta = shape.recoveryFraction ?: 0.0
        val motion = t.physicsMotion
        val cruise = exposure.entries.sumOf { (b, km) -> km * shape.cruiseJPerKgPerKm(bucketCenterMps(b)) }
        val brakedLoss = (1.0 - eta) * (motion.brakingStopGoJPerKg + motion.brakingDescentJPerKg)
        val demand = cruise + motion.netKineticJPerKg +
            PhysicsPowerConstants.GRAVITY * motion.netHeightM + brakedLoss
        val nominal = profile.nominalPercentPerJPerKg
        val windowsUsable = t.physicsWindows >= 2 && exposureKm >= 1.0 && t.physicsDepletion >= 5.0 &&
            t.distance >= 1 && t.depletion >= 5 && t.distance / t.depletion in 0.01..1.5 &&
            demand.isFinite() && demand > 0.0 && cruise > 0.0
        // Shrinkage toward the stated pack: the prior counts as PACK_PRIOR_PERCENT of observed depletion.
        val factor = if (!windowsUsable) 1.0 else
            ((t.physicsDepletion + PACK_PRIOR_PERCENT) / (demand * nominal + PACK_PRIOR_PERCENT))
                .coerceIn(MIN_PACK_ENERGY_FACTOR, MAX_PACK_ENERGY_FACTOR)
        val scale = nominal * factor
        val rates = keys.associateWith { b -> scale * shape.cruiseJPerKgPerKm(bucketCenterMps(b)) }
        val limits = rates.filterValues { rate ->
            rate <= SpeedBucketConsumptionFit.MIN_RATE + 1e-9 || rate >= SpeedBucketConsumptionFit.MAX_RATE - 1e-9
        }.keys
        val clamped = rates.mapValues { (_, rate) ->
            rate.coerceIn(SpeedBucketConsumptionFit.MIN_RATE, SpeedBucketConsumptionFit.MAX_RATE)
        }
        val seconds = motion.alignedSeconds
        return PhysicsRates(
            fit = SpeedBucketConsumptionFit.Result(clamped, limits, scale),
            shape = shape,
            stopGoPercentPerKm = if (exposureKm > 0.0) scale * (1.0 - eta) * motion.brakingStopGoJPerKg / exposureKm else 0.0,
            hillPercentPerKm = if (exposureKm > 0.0) scale * (1.0 - eta) * motion.brakingDescentJPerKg / exposureKm else 0.0,
            climbMPerKm = if (exposureKm > 0.0) motion.climbM / exposureKm else null,
            barometricShare = if (seconds > 0.0) (motion.barometricSeconds / seconds).coerceIn(0.0, 1.0) else 0.0,
            packEnergyFactor = factor,
        )
    }

    fun snapshot(board: String, profile: RiderBoardProfile = RiderBoardProfile.DEFAULT): BoardSpeedBucketStatistics? {
        val t = boards[board] ?: return null
        val physics = physicsRates(t, board, (t.measured.keys + t.inferred.keys + t.fitExposure.keys).toSortedSet(), profile)
        val fit = physics?.fit ?: rates(t)
        val rates = fit.rates
        val totalDistance = t.measured.toSortedMap().values.sum()
        // Shares use measured riding only, conditional on buckets with fitted consumption.
        val modeledUse = rates.entries.sumOf { (b, rate) -> rate * (t.measured[b] ?: 0.0) }
        val keys = (t.measured.keys + t.inferred.keys + rates.keys).sorted()
        return BoardSpeedBucketStatistics(board, keys.map { b ->
            val km = t.measured[b] ?: 0.0
            val rate = rates[b]
            SpeedBucketStatistics(b, km, if (totalDistance > 0) 100 * km / totalDistance else 0.0,
                t.inferred[b] ?: 0.0, t.support[b] ?: 0.0, t.inferredSupport[b] ?: 0.0,
                t.counts[b] ?: 0L, rate?.times(100), rate?.let { 100 / it },
                rate?.takeIf { modeledUse > 0 }?.let { 100 * it * km / modeledUse },
                b in fit.limitBuckets)
        }, t.unattributed, t.windows, t.depletion, t.seeded, t.distance,
            fit.scale?.let { t.rawRollingCoefficient * it },
            fit.scale?.let { t.rawAerodynamicCoefficient * it },
            t.unconstrainedRollingCoefficient,
            t.unconstrainedAerodynamicCoefficient,
            t.rollingCoefficientStandardError,
            t.aerodynamicCoefficientStandardError,
            t.shapeReferenceSpeedKmh,
            fit.scale?.let { scale ->
                val referenceSquared = t.shapeReferenceSpeedKmh?.let { it * it }
                val rolling = t.rawRollingCoefficient * scale
                val aero = referenceSquared?.let { t.rawAerodynamicCoefficient * it * scale }
                aero?.let { it / (rolling + it) }?.takeIf { it.isFinite() }
            },
            t.shapeConditionNumber, t.shapeRSquared, t.shapeEvidenceKm,
            t.shapeSupportedBucketCount, t.shapeRefits,
            physics?.shape,
            physics?.stopGoPercentPerKm ?: 0.0,
            physics?.hillPercentPerKm ?: 0.0,
            physics?.climbMPerKm,
            physics?.barometricShare ?: 0.0,
            t.physicsWindows,
            physics?.let { profile.nominalBatteryWh },
            physics?.packEnergyFactor)
    }

    fun toJson() = JSONObject().apply {
        boards.forEach { (board, t) ->
            put(board, JSONObject().put("schema_version", 1)
                .put("measured_km", bucketJson(t.measured)).put("inferred_km", bucketJson(t.inferred))
                .put("calibration_measured_km", bucketJson(t.support))
                .put("calibration_inferred_km", bucketJson(t.inferredSupport))
                .put("contributing_windows", bucketJson(t.counts))
                .put("fit_target", bucketJson(t.target))
                .put("fit_exposure_km", bucketJson(t.fitExposure))
                .put("fit_depletion_percent", t.fitDepletion)
                .put("fit_squared_depletion_percent", t.fitSquaredDepletion)
                .put("fit_window_weight", t.fitWindowWeight)
                .put("fit_accepted_windows", t.fitAcceptedWindows)
                .put("quadratic_raw_rolling", t.rawRollingCoefficient)
                .put("quadratic_raw_aerodynamic", t.rawAerodynamicCoefficient)
                .put("quadratic_unconstrained_rolling",
                    t.unconstrainedRollingCoefficient ?: JSONObject.NULL)
                .put("quadratic_unconstrained_aerodynamic",
                    t.unconstrainedAerodynamicCoefficient ?: JSONObject.NULL)
                .put("quadratic_rolling_standard_error",
                    t.rollingCoefficientStandardError ?: JSONObject.NULL)
                .put("quadratic_aerodynamic_standard_error",
                    t.aerodynamicCoefficientStandardError ?: JSONObject.NULL)
                .put("quadratic_reference_speed_kmh", t.shapeReferenceSpeedKmh ?: JSONObject.NULL)
                .put("quadratic_condition_number", t.shapeConditionNumber ?: JSONObject.NULL)
                .put("quadratic_r_squared", t.shapeRSquared ?: JSONObject.NULL)
                .put("quadratic_evidence_km", t.shapeEvidenceKm)
                .put("quadratic_supported_bucket_count", t.shapeSupportedBucketCount)
                .put("quadratic_refits", t.shapeRefits)
                .put("last_quadratic_trip_id", t.lastShapeTripId ?: JSONObject.NULL)
                .put("fit_cross", JSONObject().apply { t.cross.forEach { (b, row) -> put(b.toString(), bucketJson(row)) } })
                .put("unassigned_km", t.unattributed).put("window_distance_km", t.distance)
                .put("observed_depletion_percent", t.depletion).put("completed_windows", t.windows)
                .put("seeded_from_retained_history", t.seeded)
                .put("last_window_id", t.lastWindowId ?: JSONObject.NULL)
                .put("physics_exposure_km", bucketJson(t.physicsExposure))
                .put("physics_depletion_percent", t.physicsDepletion)
                .put("physics_motion", t.physicsMotion.toJson())
                .put("physics_windows", t.physicsWindows))
        }
        if (physicsModels.isNotEmpty()) put(PHYSICS_KEY, JSONObject().apply {
            physicsModels.toSortedMap().forEach { (board, model) -> put(board, model.toJson()) }
        })
    }

    companion object {
        private fun bucketJson(values: Map<Int, *>) = JSONObject().apply {
            values.toSortedMap().forEach { (bucket, value) -> put(bucket.toString(), value) }
        }
        private fun validBucket(b: Int) = b >= 0 && b % SPEED_BUCKET_WIDTH_KMH == 0
        private fun clean(values: Map<Int, Double>) = values.filter { (b, v) -> validBucket(b) && v.isFinite() && v > 0 }
        private fun add(target: MutableMap<Int, Double>, source: Map<Int, Double>) {
            clean(source).forEach { (b, v) -> target[b] = (target[b] ?: 0.0) + v }
        }
        private fun JSONObject.optionalFinite(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null
        /** Weight of the stated pack energy, in percent of observed depletion (two 5 % windows). */
        private const val PACK_PRIOR_PERCENT = 10.0
        private const val MIN_PACK_ENERGY_FACTOR = 1.0 / 3.0
        private const val MAX_PACK_ENERGY_FACTOR = 3.0
        /** Reserved registry key; board identifiers are pseudonymous hex and cannot collide. */
        private const val PHYSICS_KEY = "__physics_models__"
        private fun bucketCenterMps(bucket: Int) = (bucket + SPEED_BUCKET_WIDTH_KMH / 2.0) / 3.6
    }
}

/** Apply a non-negative rolling-plus-aerodynamic shape and conserve aggregate depletion. */
internal object SpeedBucketConsumptionFit {
    const val MIN_RATE = 1.0 / 1.5
    const val MAX_RATE = 1.0 / 0.01
    private const val LIMIT_MARGIN = 0.10

    data class Result(
        val rates: Map<Int, Double>,
        val limitBuckets: Set<Int>,
        val scale: Double? = null,
    ) {
        companion object { val EMPTY = Result(emptyMap(), emptySet(), null) }
    }

    fun quadratic(
        exposureKm: Map<Int, Double>,
        observedDepletionPercent: Double,
        rollingCoefficient: Double,
        aerodynamicCoefficientPerKmhSquared: Double,
    ): Result {
        if (!rollingCoefficient.isFinite() || rollingCoefficient < 0.0 ||
            !aerodynamicCoefficientPerKmhSquared.isFinite() ||
            aerodynamicCoefficientPerKmhSquared < 0.0 ||
            rollingCoefficient + aerodynamicCoefficientPerKmhSquared <= 0.0
        ) return Result.EMPTY
        val shape = exposureKm.keys.associateWith { bucket ->
            val speed = bucket + SPEED_BUCKET_WIDTH_KMH / 2.0
            rollingCoefficient + aerodynamicCoefficientPerKmhSquared * speed * speed
        }
        return normalizeInternal(shape, exposureKm, observedDepletionPercent, flagRawLimits = false)
    }

    fun normalize(
        rawRates: Map<Int, Double>,
        exposureKm: Map<Int, Double>,
        observedDepletionPercent: Double,
    ): Result = normalizeInternal(rawRates, exposureKm, observedDepletionPercent, flagRawLimits = true)

    private fun normalizeInternal(
        rawRates: Map<Int, Double>,
        exposureKm: Map<Int, Double>,
        observedDepletionPercent: Double,
        flagRawLimits: Boolean,
    ): Result {
        val cleanExposure = exposureKm.filter { (bucket, value) ->
            rawRates.containsKey(bucket) && value.isFinite() && value > 0.0
        }
        if (rawRates.isEmpty() || cleanExposure.isEmpty() ||
            !observedDepletionPercent.isFinite() || observedDepletionPercent <= 0.0
        ) return Result.EMPTY
        val minUse = cleanExposure.values.sum() * MIN_RATE
        val maxUse = cleanExposure.values.sum() * MAX_RATE
        if (observedDepletionPercent !in minUse..maxUse) return Result.EMPTY

        // A single shared scale preserves the supplied relative shape. Bisection with clamping also
        // preserves depletion if a coefficient reaches a physical model boundary.
        fun modeledUse(scale: Double) = cleanExposure.entries.sumOf { (bucket, distance) ->
            distance * (requireNotNull(rawRates[bucket]) * scale).coerceIn(MIN_RATE, MAX_RATE)
        }
        var low = 0.0
        var high = 1.0
        while (modeledUse(high) < observedDepletionPercent && high < 1e12) high *= 2.0
        repeat(80) {
            val middle = (low + high) / 2.0
            if (modeledUse(middle) < observedDepletionPercent) low = middle else high = middle
        }
        val scale = (low + high) / 2.0
        val normalized = rawRates.mapValues { (_, rate) ->
            (rate * scale).coerceIn(MIN_RATE, MAX_RATE)
        }
        val rawLimits = if (flagRawLimits) rawRates.filterValues { rate ->
            rate <= MIN_RATE * (1.0 + LIMIT_MARGIN) || rate >= MAX_RATE * (1.0 - LIMIT_MARGIN)
        }.keys else emptySet()
        val limitBuckets = rawLimits + normalized.filterValues { rate ->
            rate <= MIN_RATE + 1e-9 || rate >= MAX_RATE - 1e-9
        }.keys
        return Result(normalized, limitBuckets, scale)
    }
}

/**
 * Weighted non-negative least squares for `rolling + aerodynamic * speed^2`.
 * Bucket centres are the only speed approximation. The reference speed is the evidence-weighted
 * RMS speed and exists solely to condition the two-by-two solve; it is not a model prior.
 */
internal object SpeedBucketQuadraticFit {
    private const val NUMERICAL_RANK_TOLERANCE = 1e-12

    data class Result(
        val rollingCoefficient: Double,
        val aerodynamicCoefficientPerKmhSquared: Double,
        val unconstrainedRollingCoefficient: Double?,
        val unconstrainedAerodynamicCoefficientPerKmhSquared: Double?,
        val rollingCoefficientStandardError: Double?,
        val aerodynamicCoefficientStandardErrorPerKmhSquared: Double?,
        val referenceSpeedKmh: Double?,
        val conditionNumber: Double?,
        val rSquared: Double?,
        val evidenceKm: Double,
        val supportedBucketCount: Int,
    )

    fun fit(
        exposureKm: Map<Int, Double>,
        target: Map<Int, Double>,
        cross: Map<Int, Map<Int, Double>>,
        supportedBuckets: Set<Int>,
        weightedDepletion: Double,
        weightedSquaredDepletion: Double,
        windowWeight: Double,
        acceptedWindows: Long,
    ): Result {
        val keys = supportedBuckets.filter { bucket ->
            (exposureKm[bucket] ?: 0.0).isFinite() && (exposureKm[bucket] ?: 0.0) > 0.0 &&
                (target[bucket] ?: 0.0).isFinite()
        }.sorted()
        val evidenceKm = keys.sumOf { exposureKm[it] ?: 0.0 }
        fun fallback(referenceSpeed: Double? = null) = Result(
            rollingCoefficient = 1.0,
            aerodynamicCoefficientPerKmhSquared = 0.0,
            unconstrainedRollingCoefficient = null,
            unconstrainedAerodynamicCoefficientPerKmhSquared = null,
            rollingCoefficientStandardError = null,
            aerodynamicCoefficientStandardErrorPerKmhSquared = null,
            referenceSpeedKmh = referenceSpeed,
            conditionNumber = null,
            rSquared = null,
            evidenceKm = evidenceKm,
            supportedBucketCount = keys.size,
        )
        if (keys.size < 2 || evidenceKm <= 0.0 || acceptedWindows < 2L) return fallback()

        val meanSpeedSquared = keys.sumOf { bucket ->
            val speed = bucket + SPEED_BUCKET_WIDTH_KMH / 2.0
            (exposureKm[bucket] ?: 0.0) * speed * speed
        } / evidenceKm
        if (!meanSpeedSquared.isFinite() || meanSpeedSquared <= 0.0) return fallback()
        val referenceSpeed = sqrt(meanSpeedSquared)
        val quadratic = keys.associateWith { bucket ->
            val speed = bucket + SPEED_BUCKET_WIDTH_KMH / 2.0
            speed * speed / meanSpeedSquared
        }
        fun crossValue(row: Int, column: Int) = cross[row]?.get(column) ?: 0.0
        val s00 = keys.sumOf { row -> keys.sumOf { column -> crossValue(row, column) } }
        val s01 = keys.sumOf { row ->
            keys.sumOf { column -> crossValue(row, column) * quadratic.getValue(column) }
        }
        val s11 = keys.sumOf { row ->
            keys.sumOf { column ->
                quadratic.getValue(row) * crossValue(row, column) * quadratic.getValue(column)
            }
        }
        val t0 = keys.sumOf { target[it] ?: 0.0 }
        val t1 = keys.sumOf { quadratic.getValue(it) * (target[it] ?: 0.0) }
        val determinant = s00 * s11 - s01 * s01
        val scale = maxOf(1.0, s00 * s11)
        if (s00 <= 0.0 || s11 <= 0.0 || determinant <= NUMERICAL_RANK_TOLERANCE * scale) {
            return fallback(referenceSpeed)
        }

        val unconstrainedRolling = (t0 * s11 - t1 * s01) / determinant
        val unconstrainedAeroAtReference = (s00 * t1 - s01 * t0) / determinant
        data class Candidate(val rolling: Double, val aeroAtReference: Double)
        fun objective(candidate: Candidate): Double =
            candidate.rolling * candidate.rolling * s00 +
                2.0 * candidate.rolling * candidate.aeroAtReference * s01 +
                candidate.aeroAtReference * candidate.aeroAtReference * s11 -
                2.0 * candidate.rolling * t0 - 2.0 * candidate.aeroAtReference * t1
        val candidates = buildList {
            if (unconstrainedRolling >= 0.0 && unconstrainedAeroAtReference >= 0.0) {
                add(Candidate(unconstrainedRolling, unconstrainedAeroAtReference))
            }
            add(Candidate((t0 / s00).coerceAtLeast(0.0), 0.0))
            add(Candidate(0.0, (t1 / s11).coerceAtLeast(0.0)))
            add(Candidate(0.0, 0.0))
        }
        val selected = candidates.minBy(::objective)
        val trace = s00 + s11
        val discriminant = sqrt((s00 - s11) * (s00 - s11) + 4.0 * s01 * s01)
        val largestEigenvalue = (trace + discriminant) / 2.0
        val smallestEigenvalue = (trace - discriminant) / 2.0
        val condition = (largestEigenvalue / smallestEigenvalue)
            .takeIf { smallestEigenvalue > 0.0 && it.isFinite() }
        val sumSquaredError = weightedSquaredDepletion + objective(selected)
        val totalSumSquares = if (windowWeight > 0.0) {
            weightedSquaredDepletion - weightedDepletion * weightedDepletion / windowWeight
        } else 0.0
        val rSquared = if (totalSumSquares > 0.0) {
            (1.0 - sumSquaredError / totalSumSquares).takeIf { it.isFinite() }
        } else null
        val unconstrainedError = weightedSquaredDepletion + objective(
            Candidate(unconstrainedRolling, unconstrainedAeroAtReference),
        )
        val residualVariance = if (acceptedWindows > 2L) {
            (unconstrainedError.coerceAtLeast(0.0) / (acceptedWindows - 2L)).takeIf {
                it.isFinite()
            }
        } else null
        val rollingStandardError = residualVariance?.let {
            sqrt(it * s11 / determinant).takeIf { value -> value.isFinite() }
        }
        val aeroStandardError = residualVariance?.let {
            (sqrt(it * s00 / determinant) / meanSpeedSquared).takeIf { value -> value.isFinite() }
        }
        return Result(
            rollingCoefficient = selected.rolling,
            aerodynamicCoefficientPerKmhSquared =
                selected.aeroAtReference / meanSpeedSquared,
            unconstrainedRollingCoefficient = unconstrainedRolling,
            unconstrainedAerodynamicCoefficientPerKmhSquared =
                unconstrainedAeroAtReference / meanSpeedSquared,
            rollingCoefficientStandardError = rollingStandardError,
            aerodynamicCoefficientStandardErrorPerKmhSquared = aeroStandardError,
            referenceSpeedKmh = referenceSpeed,
            conditionNumber = condition,
            rSquared = rSquared,
            evidenceKm = evidenceKm,
            supportedBucketCount = keys.size,
        )
    }
}

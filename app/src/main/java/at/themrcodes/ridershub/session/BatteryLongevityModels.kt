package at.themrcodes.ridershub.session

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.max

enum class BatteryLongevityStatus {
    COLLECTING_DATA,
    ESTIMATING,
    TRACKING,
}

data class BatteryCapacityObservation(
    val cycleId: String,
    val observedAt: Instant,
    val normalizedFullRangeKm: Double,
    val observedDistanceKm: Double,
    val observedDepletionPercent: Double,
    val fullChargeObserved: Boolean,
    val estimatedLifetimeFullCycles: Double? = null,
    val odometerKm: Double? = null,
    val completed: Boolean = true,
)

data class BatteryLongevitySnapshot(
    val status: BatteryLongevityStatus,
    val currentFullRangeKm: Double?,
    val observedCycleCount: Int,
    val usableCycleCount: Int,
    val observations: List<BatteryCapacityObservation>,
    val allTimeHighKm: Double?,
    val message: String,
    val cycleAge: BatteryCycleAge? = null,
    val chargeStats: BatteryChargeStats? = null,
    val wearTrend: BatteryWearTrend? = null,
    val speedBucketStatistics: BoardSpeedBucketStatistics? = null,
)

/**
 * Distance-equivalent full cycles, not a count of charger connections or measured energy.
 * The historical riding profile and original battery are assumed to match the observed board.
 */
data class BatteryCycleAge(
    val estimatedLifetimeFullCycles: Double,
    val estimatedPriorFullCycles: Double,
    val referenceFullRangeKm: Double,
)

object BatteryLongevityEstimator {
    private const val MIN_DEPLETION_PERCENT = 5.0
    private const val MIN_DISTANCE_KM = 0.5
    private const val MIN_PROFILE_COVERAGE = 0.90
    private const val MIN_FULL_RANGE_KM = 1.0
    private const val MAX_FULL_RANGE_KM = 200.0
    private const val MODEL_ITERATIONS = 50
    private const val PRIOR_STRENGTH_KM_SQUARED = 4.0
    private const val CURRENT_WINDOW_SIZE = 3

    @JvmOverloads
    fun estimate(
        cycles: ChargeCycleStoreSnapshot,
        selectedBoardId: String? = null,
        chargeStats: BatteryChargeStats? = null,
        speedBucketStatistics: BoardSpeedBucketStatistics? = null,
    ): BatteryLongevitySnapshot {
        val selectedBoard = selectedBoardId ?: cycles.activeCycles.maxByOrNull { it.lastObservedAt }?.localBoardId
            ?: cycles.completedCycles.maxByOrNull { it.closedAt ?: it.lastObservedAt }?.localBoardId
        if (selectedBoard == null) return collecting(0).copy(chargeStats = chargeStats)
        val boardStats = speedBucketStatistics?.takeIf { it.localBoardId == selectedBoard }

        val boardCycles = (cycles.completedCycles + cycles.activeCycles)
            .filter { it.localBoardId == selectedBoard }
            .distinctBy { it.id }
            .sortedBy { it.startedAt }
        val confirmedCycles = boardCycles.filter {
            it.startReason == ChargeCycleStartReason.INFERRED_RECHARGE &&
                (it.inferredRechargeIncreasePercent ?: 0) >= ChargeCycleTracker.MIN_INFERRED_RECHARGE_PERCENT
        }
        val qualified = confirmedCycles.mapNotNull(::qualify)
        if (qualified.isEmpty()) return collecting(confirmedCycles.size).copy(
            chargeStats = chargeStats, speedBucketStatistics = boardStats)

        val aggregatePercentPerKm = qualified.sumOf { it.depletionPercent } /
            qualified.sumOf { it.cycle.recordedDistanceKm }
        if (!aggregatePercentPerKm.isFinite() || aggregatePercentPerKm <= 0.0) {
            return collecting(confirmedCycles.size).copy(chargeStats = chargeStats, speedBucketStatistics = boardStats)
        }
        // Use the actual recorded mix for distance-equivalent age, not the normalized capacity
        // of a selected recent window. Missing rides and history pruned from the store remain
        // represented by the lifetime odometer; never add observed event counts to that total.
        val ageRangeKm = 100.0 / aggregatePercentPerKm
        val cycleAge = estimateCycleAge(boardCycles, ageRangeKm)
        val costs = boardStats?.consumptionRates().orEmpty()
            .ifEmpty { fitPercentPerKmBySpeed(qualified, aggregatePercentPerKm) }
        val referenceProfile = boardStats?.measuredProfile().orEmpty()
            .ifEmpty { mergeProfiles(qualified.map { it.cycle.effectiveSpeedBucketDistancesKm }) }
        val referenceDistance = referenceProfile.values.sum()
        val referencePercentPerKm = if (referenceDistance > 0.0) {
            referenceProfile.entries.sumOf { (bucket, distance) ->
                distance * (costs[bucket] ?: aggregatePercentPerKm)
            } / referenceDistance
        } else {
            aggregatePercentPerKm
        }

        val observations = qualified.mapNotNull { observation ->
            val cycle = observation.cycle
            val predictedDepletion = cycle.effectiveSpeedBucketDistancesKm.entries.sumOf { (bucket, distance) ->
                distance * (costs[bucket] ?: aggregatePercentPerKm)
            }
            val normalizedRange = 100.0 * predictedDepletion /
                (observation.depletionPercent * referencePercentPerKm)
            val observedAt = runCatching {
                Instant.parse(cycle.closedAt ?: cycle.lastObservedAt)
            }.getOrNull()
            if (
                observedAt == null ||
                !normalizedRange.isFinite() ||
                normalizedRange !in MIN_FULL_RANGE_KM..MAX_FULL_RANGE_KM
            ) {
                null
            } else {
                BatteryCapacityObservation(
                    cycleId = cycle.id,
                    observedAt = observedAt,
                    normalizedFullRangeKm = normalizedRange,
                    observedDistanceKm = cycle.recordedDistanceKm,
                    observedDepletionPercent = observation.depletionPercent,
                    fullChargeObserved = cycle.fullChargeObserved,
                    odometerKm = cycle.odometerEndKm,
                    completed = cycle.isClosed,
                    estimatedLifetimeFullCycles = cycleAge?.let {
                        cycle.odometerEndKm?.takeIf { km -> km.isFinite() && km >= 0.0 }
                            ?.div(it.referenceFullRangeKm)
                    },
                )
            }
        }.sortedBy { it.observedAt }

        if (observations.isEmpty()) return collecting(confirmedCycles.size).copy(
            chargeStats = chargeStats, speedBucketStatistics = boardStats)
        val recent = observations.takeLast(CURRENT_WINDOW_SIZE)
        val recentWeight = recent.sumOf { it.observedDepletionPercent }
        val current = recent.sumOf {
            it.normalizedFullRangeKm * it.observedDepletionPercent
        } / recentWeight
        val status = if (observations.size >= 3) {
            BatteryLongevityStatus.TRACKING
        } else {
            BatteryLongevityStatus.ESTIMATING
        }
        return BatteryLongevitySnapshot(
            status = status,
            currentFullRangeKm = current,
            observedCycleCount = confirmedCycles.size,
            usableCycleCount = observations.size,
            observations = observations,
            cycleAge = cycleAge,
            chargeStats = chargeStats,
            speedBucketStatistics = boardStats,
            wearTrend = BatteryWearTrendEstimator.estimate(observations),
            allTimeHighKm = observations.maxOf { it.normalizedFullRangeKm },
            message = if (status == BatteryLongevityStatus.TRACKING) {
                "Speed-normalized from ${observations.size} usable charge observations"
            } else {
                "More charge observations will stabilize this estimate"
            },
        )
    }

    private fun estimateCycleAge(
        cycles: List<ChargeCycleSummary>,
        rangeKm: Double,
    ): BatteryCycleAge? {
        if (!rangeKm.isFinite() || rangeKm !in MIN_FULL_RANGE_KM..MAX_FULL_RANGE_KM) return null
        // A reset, replacement counter, or missing endpoint makes lifetime distance ambiguous.
        // Do not silently use the maximum counter or assume a new battery.
        var previousEnd = 0.0
        for (cycle in cycles) {
            val start = cycle.odometerStartKm ?: return null
            val end = cycle.odometerEndKm ?: return null
            if (!start.isFinite() || !end.isFinite() || start < previousEnd || end < start) return null
            previousEnd = end
        }
        val first = cycles.firstOrNull() ?: return null
        return BatteryCycleAge(
            estimatedLifetimeFullCycles = previousEnd / rangeKm,
            estimatedPriorFullCycles = requireNotNull(first.odometerStartKm) / rangeKm,
            referenceFullRangeKm = rangeKm,
        )
    }

    private fun collecting(observedCycles: Int) = BatteryLongevitySnapshot(
        status = BatteryLongevityStatus.COLLECTING_DATA,
        currentFullRangeKm = null,
        observedCycleCount = observedCycles,
        usableCycleCount = 0,
        observations = emptyList(),
        allTimeHighKm = null,
        message = if (observedCycles == 0) {
            "A charge observation starts after battery rises by at least ${ChargeCycleTracker.MIN_INFERRED_RECHARGE_PERCENT} percentage points and stays elevated for 30 seconds"
        } else {
            "Use at least 5% battery with recorded speed data to create a capacity point"
        },
    )

    private fun qualify(cycle: ChargeCycleSummary): QualifiedCycle? {
        if (!cycle.recordedDistanceKm.isFinite() ||
            cycle.speedBucketDistancesKm.values.any { !it.isFinite() || it < 0.0 }
        ) return null
        val depletion = cycle.observedDepletionPercent.toDouble()
        if (depletion < MIN_DEPLETION_PERCENT || cycle.recordedDistanceKm < MIN_DISTANCE_KM) return null
        val profiledDistance = cycle.profiledDistanceKm
        if (profiledDistance <= 0.0) return null
        val referenceDistance = cycle.odometerDistanceKm ?: cycle.recordedDistanceKm
        if (referenceDistance <= 0.0) return null
        val coverage = (profiledDistance / referenceDistance).coerceIn(0.0, 1.0)
        if (coverage < MIN_PROFILE_COVERAGE) return null
        return QualifiedCycle(cycle, depletion)
    }

    private fun fitPercentPerKmBySpeed(
        cycles: List<QualifiedCycle>,
        aggregatePercentPerKm: Double,
    ): Map<Int, Double> {
        val buckets = cycles.flatMap { it.cycle.effectiveSpeedBucketDistancesKm.keys }.distinct().sorted()
        if (buckets.isEmpty()) return emptyMap()
        val costs = buckets.associateWith { aggregatePercentPerKm }.toMutableMap()
        repeat(MODEL_ITERATIONS) {
            buckets.forEach { bucket ->
                var numerator = PRIOR_STRENGTH_KM_SQUARED * aggregatePercentPerKm
                var denominator = PRIOR_STRENGTH_KM_SQUARED
                cycles.forEach { observation ->
                    val distance = observation.cycle.effectiveSpeedBucketDistancesKm[bucket] ?: 0.0
                    if (distance <= 0.0) return@forEach
                    val otherDepletion = observation.cycle.effectiveSpeedBucketDistancesKm.entries.sumOf {
                        (otherBucket, otherDistance) ->
                        if (otherBucket == bucket) 0.0
                        else otherDistance * (costs[otherBucket] ?: aggregatePercentPerKm)
                    }
                    numerator += distance * (observation.depletionPercent - otherDepletion)
                    denominator += distance * distance
                }
                costs[bucket] = max(0.01, numerator / denominator)
            }
        }
        return costs
    }

    private fun mergeProfiles(profiles: List<Map<Int, Double>>): Map<Int, Double> = buildMap {
        profiles.forEach { profile ->
            profile.forEach { (bucket, distance) ->
                put(bucket, (get(bucket) ?: 0.0) + distance)
            }
        }
    }

    private data class QualifiedCycle(
        val cycle: ChargeCycleSummary,
        val depletionPercent: Double,
    )
}

enum class LongevityGranularity(val displayName: String) {
    DAY("Daily"),
    WEEK("Weekly"),
    MONTH("Monthly"),
    YEAR("Yearly");

    fun coarser(): LongevityGranularity? = entries.getOrNull(ordinal + 1)
    fun finer(): LongevityGranularity? = entries.getOrNull(ordinal - 1)
}

data class BatteryLongevityBar(
    val id: String,
    val start: Instant,
    val endExclusive: Instant,
    val label: String,
    val fullRangeKm: Double,
    val observationCount: Int,
    val latestObservationAt: Instant,
)

object BatteryLongevityChart {
    const val DEFAULT_MAX_BARS = 8

    fun aggregate(
        observations: List<BatteryCapacityObservation>,
        granularity: LongevityGranularity,
        zoneId: ZoneId,
        anchor: Instant? = null,
        maxBars: Int = DEFAULT_MAX_BARS,
        locale: Locale = Locale.US,
    ): List<BatteryLongevityBar> {
        require(maxBars > 0)
        // Include the complete selected period, even when the anchor is an earlier
        // observation within it. Earlier populated periods fill the remaining slots.
        val endExclusive = anchor?.let { bucketEnd(bucketStart(it, granularity, zoneId), granularity).toInstant() }
        val filtered = observations.filter { endExclusive == null || it.observedAt.isBefore(endExclusive) }
        return filtered.groupBy { bucketStart(it.observedAt, granularity, zoneId).toInstant() }
            .map { (start, values) ->
                val end = bucketEnd(start.atZone(zoneId), granularity).toInstant()
                val weight = values.sumOf { it.observedDepletionPercent }
                val average = values.sumOf {
                    it.normalizedFullRangeKm * it.observedDepletionPercent
                } / weight
                BatteryLongevityBar(
                    id = "${granularity.name}:$start",
                    start = start,
                    endExclusive = end,
                    label = label(start.atZone(zoneId), granularity, locale),
                    fullRangeKm = average,
                    observationCount = values.size,
                    latestObservationAt = values.maxOf { it.observedAt },
                )
            }
            .sortedBy { it.start }
            .takeLast(maxBars)
    }

    private fun bucketStart(
        instant: Instant,
        granularity: LongevityGranularity,
        zoneId: ZoneId,
    ): ZonedDateTime {
        val local = instant.atZone(zoneId).toLocalDate()
        val date = when (granularity) {
            LongevityGranularity.DAY -> local
            LongevityGranularity.WEEK -> local.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            LongevityGranularity.MONTH -> local.withDayOfMonth(1)
            LongevityGranularity.YEAR -> local.withDayOfYear(1)
        }
        return date.atStartOfDay(zoneId)
    }

    private fun bucketEnd(
        start: ZonedDateTime,
        granularity: LongevityGranularity,
    ): ZonedDateTime = when (granularity) {
        LongevityGranularity.DAY -> start.plusDays(1)
        LongevityGranularity.WEEK -> start.plusWeeks(1)
        LongevityGranularity.MONTH -> start.plusMonths(1)
        LongevityGranularity.YEAR -> start.plusYears(1)
    }

    private fun label(
        start: ZonedDateTime,
        granularity: LongevityGranularity,
        locale: Locale = Locale.US,
    ): String =
        when (granularity) {
            LongevityGranularity.DAY -> DateTimeFormatter.ofPattern("d MMM", locale).format(start)
            LongevityGranularity.WEEK -> "W${DateTimeFormatter.ofPattern("ww", locale).format(start)}"
            LongevityGranularity.MONTH -> DateTimeFormatter.ofPattern("MMM", locale).format(start)
            LongevityGranularity.YEAR -> DateTimeFormatter.ofPattern("yyyy", locale).format(start)
        }
}

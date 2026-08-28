package at.themrcodes.ridershub.session

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object RangeEstimator {
    private const val MIN_DISTANCE_KM = 1.0
    private const val MIN_DEPLETION_PERCENT = 2.0
    private const val MIN_BUCKET_COVERAGE_RATIO = 0.95
    private const val BUCKET_PRIOR_STRENGTH_KM_SQUARED = 1.0
    private const val BUCKET_MODEL_ITERATIONS = 40

    fun estimate(
        currentBatteryPercent: Int?,
        currentRestingVoltageV: Double?,
        sessions: List<RideSummary>,
        calibrationPoints: List<CalibrationPoint>,
    ): RangeEstimate {
        val voltageModel = fitVoltageModel(calibrationPoints)
        val usable = sessions.filter { it.isTrack && it.distanceKm >= 0.1 }
        var observedDistance = 0.0
        var observedDepletion = 0.0
        val observations = mutableListOf<DepletionObservation>()

        usable.forEach { session ->
            val percentDrop = pairDrop(
                session.boardBatteryStart?.toDouble(),
                session.boardBatteryEnd?.toDouble(),
            )
            val voltageDrop = voltageModel?.let { model ->
                val volts = pairDrop(session.restingVoltageStart, session.restingVoltageEnd)
                volts?.div(model.voltsPerPercent)
            }
            val effectiveDrop = listOfNotNull(percentDrop, voltageDrop)
                .filter { it > 0.0 }
                .maxOrNull()
            if (effectiveDrop != null && effectiveDrop >= 0.25) {
                observedDistance += session.distanceKm
                observedDepletion += effectiveDrop
                observations += DepletionObservation(session, effectiveDrop)
            }
        }

        if (
            currentBatteryPercent == null ||
            observedDistance < MIN_DISTANCE_KM ||
            observedDepletion < MIN_DEPLETION_PERCENT
        ) {
            val distanceProgress = min(1.0, observedDistance / MIN_DISTANCE_KM)
            val depletionProgress = min(1.0, observedDepletion / MIN_DEPLETION_PERCENT)
            val progress = ((distanceProgress + depletionProgress) * 50).toInt()
            return RangeEstimate(
                status = RangeEstimateStatus.COLLECTING_DATA,
                remainingKm = null,
                kmPerPercent = null,
                confidencePercent = progress,
                observedDistanceKm = observedDistance,
                observedDepletionPercent = observedDepletion,
                message = "Record at least 1 km with 2% battery use to calculate range",
            )
        }

        val aggregateKmPerPercent = observedDistance / observedDepletion
        if (aggregateKmPerPercent !in MIN_KM_PER_PERCENT..MAX_KM_PER_PERCENT) {
            return RangeEstimate(
                status = RangeEstimateStatus.COLLECTING_DATA,
                remainingKm = null,
                kmPerPercent = null,
                confidencePercent = 0,
                observedDistanceKm = observedDistance,
                observedDepletionPercent = observedDepletion,
                message = "More complete rides are required before range can be calculated",
            )
        }

        val kmPerPercent = bucketAwareKmPerPercent(
            observations = observations,
            profileCandidates = usable,
            aggregateKmPerPercent = aggregateKmPerPercent,
        )

        val voltagePercent = if (currentRestingVoltageV != null) {
            voltageModel?.percentAt(currentRestingVoltageV)
        } else {
            null
        }
        val effectiveBattery = voltagePercent
            ?.takeIf { it in (currentBatteryPercent - 15.0)..(currentBatteryPercent + 15.0) }
            ?.let { currentBatteryPercent * 0.7 + it * 0.3 }
            ?: currentBatteryPercent.toDouble()
        val confidence = min(
            100,
            (min(1.0, observedDistance / 20.0) * 50 +
                min(1.0, observedDepletion / 30.0) * 50).toInt(),
        )
        val status = if (observedDistance >= 5.0 && observedDepletion >= 10.0) {
            RangeEstimateStatus.CALIBRATED
        } else {
            RangeEstimateStatus.PROVISIONAL
        }
        return RangeEstimate(
            status = status,
            remainingKm = max(0.0, effectiveBattery * kmPerPercent),
            kmPerPercent = kmPerPercent,
            confidencePercent = confidence,
            observedDistanceKm = observedDistance,
            observedDepletionPercent = observedDepletion,
            message = if (status == RangeEstimateStatus.CALIBRATED) {
                "Speed-calibrated from ${"%.1f".format(Locale.US, observedDistance)} km of your riding"
            } else {
                "Provisional speed-aware estimate from ${"%.1f".format(Locale.US, observedDistance)} km"
            },
        )
    }

    private fun bucketAwareKmPerPercent(
        observations: List<DepletionObservation>,
        profileCandidates: List<RideSummary>,
        aggregateKmPerPercent: Double,
    ): Double {
        val aggregatePercentPerKm = 1.0 / aggregateKmPerPercent
        val bucketed = observations.filter { hasCompleteBucketCoverage(it.session) }
        val bucketStarts = bucketed
            .flatMap { it.session.speedBucketDistancesKm.keys }
            .distinct()
            .sorted()
        if (bucketStarts.isEmpty()) return aggregateKmPerPercent

        val percentPerKm = bucketStarts.associateWith { aggregatePercentPerKm }.toMutableMap()
        repeat(BUCKET_MODEL_ITERATIONS) {
            bucketStarts.forEach { bucket ->
                var numerator = BUCKET_PRIOR_STRENGTH_KM_SQUARED * aggregatePercentPerKm
                var denominator = BUCKET_PRIOR_STRENGTH_KM_SQUARED
                bucketed.forEach { observation ->
                    val bucketDistance = observation.session.speedBucketDistancesKm[bucket] ?: 0.0
                    if (bucketDistance <= 0.0) return@forEach
                    val depletionFromOtherBuckets = observation.session.speedBucketDistancesKm.entries.sumOf {
                        (otherBucket, distanceKm) ->
                        if (otherBucket == bucket) 0.0
                        else distanceKm * (percentPerKm[otherBucket] ?: aggregatePercentPerKm)
                    }
                    numerator += bucketDistance * (observation.depletionPercent - depletionFromOtherBuckets)
                    denominator += bucketDistance * bucketDistance
                }
                percentPerKm[bucket] = (numerator / denominator).coerceIn(
                    1.0 / MAX_KM_PER_PERCENT,
                    1.0 / MIN_KM_PER_PERCENT,
                )
            }
        }

        val activeProfile = profileCandidates
            .firstOrNull { it.active && hasCompleteBucketCoverage(it) }
            ?.speedBucketDistancesKm
        val profile = activeProfile ?: buildMap {
            bucketed.forEach { observation ->
                observation.session.speedBucketDistancesKm.forEach { (bucket, distanceKm) ->
                    put(bucket, (get(bucket) ?: 0.0) + distanceKm)
                }
            }
        }
        val profileDistance = profile.values.sum()
        if (profileDistance <= 0.0) return aggregateKmPerPercent
        val profilePercentPerKm = profile.entries.sumOf { (bucket, distanceKm) ->
            distanceKm * (percentPerKm[bucket] ?: aggregatePercentPerKm)
        } / profileDistance
        return (1.0 / profilePercentPerKm).coerceIn(MIN_KM_PER_PERCENT, MAX_KM_PER_PERCENT)
    }

    private fun hasCompleteBucketCoverage(session: RideSummary): Boolean {
        val bucketedDistance = session.speedBucketDistancesKm.values.sum()
        return bucketedDistance > 0.0 &&
            bucketedDistance >= session.distanceKm * MIN_BUCKET_COVERAGE_RATIO
    }

    private fun pairDrop(start: Double?, end: Double?): Double? =
        if (start != null && end != null && start > end) start - end else null

    private data class DepletionObservation(
        val session: RideSummary,
        val depletionPercent: Double,
    )

    private fun fitVoltageModel(points: List<CalibrationPoint>): VoltageModel? {
        val unique = points.distinctBy { it.batteryPercent to it.restingVoltageV }
        if (unique.size < 4) return null
        val minPercent = unique.minOf { it.batteryPercent }
        val maxPercent = unique.maxOf { it.batteryPercent }
        if (maxPercent - minPercent < 5) return null

        val meanPercent = unique.map { it.batteryPercent.toDouble() }.average()
        val meanVoltage = unique.map { it.restingVoltageV }.average()
        val denominator = unique.sumOf {
            val delta = it.batteryPercent - meanPercent
            delta * delta
        }
        if (denominator == 0.0) return null
        val voltsPerPercent = unique.sumOf {
            (it.batteryPercent - meanPercent) * (it.restingVoltageV - meanVoltage)
        } / denominator
        if (voltsPerPercent !in 0.005..0.2) return null
        val intercept = meanVoltage - voltsPerPercent * meanPercent
        return VoltageModel(voltsPerPercent, intercept)
    }

    private data class VoltageModel(
        val voltsPerPercent: Double,
        val intercept: Double,
    ) {
        fun percentAt(voltage: Double): Double = (voltage - intercept) / voltsPerPercent
    }

    private const val MIN_KM_PER_PERCENT = 0.03
    private const val MAX_KM_PER_PERCENT = 1.5
}

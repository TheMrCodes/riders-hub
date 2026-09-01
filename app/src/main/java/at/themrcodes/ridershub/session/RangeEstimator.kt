package at.themrcodes.ridershub.session

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object RangeEstimator {
    private const val PROFILE_DISTANCE_KM = 100.0
    private const val MIN_DISTANCE_KM = 1.0
    private const val MIN_DEPLETION_PERCENT = 5.0
    private const val MIN_BUCKET_COVERAGE_RATIO = 0.95
    private const val BUCKET_PRIOR_STRENGTH_KM_SQUARED = 1.0
    private const val BUCKET_MODEL_ITERATIONS = 40

    fun estimate(
        currentBatteryPercent: Int?,
        currentRestingVoltageV: Double?,
        depletionWindows: List<RangeDepletionWindow>,
        profileSessions: List<RideSummary>,
        calibrationPoints: List<CalibrationPoint>,
    ): RangeEstimate {
        val voltageModel = fitVoltageModel(calibrationPoints)
        val observations = recentObservations(depletionWindows, PROFILE_DISTANCE_KM)
        val observedDistance = observations.sumOf { it.distanceKm }
        val observedDepletion = observations.sumOf { it.depletionPercent }

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
                message = "Collecting the first 5% battery-use window",
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
                message = "More complete 5% battery-use windows are required",
            )
        }

        val aggregatePercentPerKm = 1.0 / aggregateKmPerPercent
        val bucketPercentPerKm = fitBucketConsumption(observations, aggregatePercentPerKm)
        val commonProfile = recentSpeedProfile(profileSessions, PROFILE_DISTANCE_KM)
            .ifEmpty { pooledObservationProfile(observations) }
        val activeProfile = activeRideProfile(profileSessions)
        val forecastProfile = activeProfile ?: commonProfile
        val forecastProfileDistance = forecastProfile.values.sum()
        val profilePercentPerKm = if (forecastProfileDistance > 0.0) {
            forecastProfile.entries.sumOf { (bucket, distanceKm) ->
                distanceKm * (bucketPercentPerKm[bucket] ?: aggregatePercentPerKm)
            } / forecastProfileDistance
        } else {
            aggregatePercentPerKm
        }
        val kmPerPercent = (1.0 / profilePercentPerKm)
            .coerceIn(MIN_KM_PER_PERCENT, MAX_KM_PER_PERCENT)

        val voltagePercent = currentRestingVoltageV?.let { voltageModel?.percentAt(it) }
        val effectiveBattery = voltagePercent
            ?.takeIf { it in (currentBatteryPercent - 15.0)..(currentBatteryPercent + 15.0) }
            ?.let { currentBatteryPercent * 0.7 + it * 0.3 }
            ?: currentBatteryPercent.toDouble()
        val confidence = min(
            100,
            (min(1.0, observedDistance / 100.0) * 60 +
                min(1.0, observedDepletion / 20.0) * 40).toInt(),
        )
        val status = if (observedDistance >= 20.0 && observedDepletion >= 10.0) {
            RangeEstimateStatus.CALIBRATED
        } else {
            RangeEstimateStatus.PROVISIONAL
        }
        val commonProfileDistance = commonProfile.values.sum()
        val profileDistributionPercent = if (commonProfileDistance > 0.0) {
            commonProfile.mapValues { (_, distanceKm) ->
                distanceKm / commonProfileDistance * 100.0
            }
        } else {
            emptyMap()
        }
        return RangeEstimate(
            status = status,
            remainingKm = max(0.0, effectiveBattery * kmPerPercent),
            kmPerPercent = kmPerPercent,
            confidencePercent = confidence,
            observedDistanceKm = observedDistance,
            observedDepletionPercent = observedDepletion,
            message = if (activeProfile != null) {
                "Adjusted to this trip's speed mix; calibrated from 5% battery-use windows"
            } else if (status == RangeEstimateStatus.CALIBRATED) {
                "Calibrated to your latest ${"%.0f".format(Locale.US, observedDistance)} km usage pattern"
            } else {
                "Provisional estimate from ${"%.1f".format(Locale.US, observedDistance)} km in 5% windows"
            },
            bucketBatteryPercentPer100Km = bucketPercentPerKm.mapValues { (_, rate) -> rate * 100.0 },
            commonSpeedBucketDistributionPercent = profileDistributionPercent,
        )
    }

    private fun fitBucketConsumption(
        observations: List<DepletionObservation>,
        aggregatePercentPerKm: Double,
    ): Map<Int, Double> {
        val bucketed = observations.filter { it.hasCompleteBucketCoverage() }
        val bucketStarts = bucketed
            .flatMap { it.speedBucketDistancesKm.keys }
            .distinct()
            .sorted()
        if (bucketStarts.isEmpty()) return emptyMap()
        if (bucketStarts.size == 1) return mapOf(bucketStarts.single() to aggregatePercentPerKm)

        val percentPerKm = bucketStarts.associateWith { aggregatePercentPerKm }.toMutableMap()
        repeat(BUCKET_MODEL_ITERATIONS) {
            bucketStarts.forEach { bucket ->
                var numerator = BUCKET_PRIOR_STRENGTH_KM_SQUARED * aggregatePercentPerKm
                var denominator = BUCKET_PRIOR_STRENGTH_KM_SQUARED
                bucketed.forEach observationLoop@{ observation ->
                    val bucketDistance = observation.speedBucketDistancesKm[bucket] ?: 0.0
                    if (bucketDistance <= 0.0) return@observationLoop
                    val depletionFromOtherBuckets = observation.speedBucketDistancesKm.entries.sumOf {
                        (otherBucket, distanceKm) ->
                        if (otherBucket == bucket) 0.0
                        else distanceKm * (percentPerKm[otherBucket] ?: aggregatePercentPerKm)
                    }
                    numerator += bucketDistance *
                        (observation.depletionPercent - depletionFromOtherBuckets)
                    denominator += bucketDistance * bucketDistance
                }
                percentPerKm[bucket] = (numerator / denominator).coerceIn(
                    1.0 / MAX_KM_PER_PERCENT,
                    1.0 / MIN_KM_PER_PERCENT,
                )
            }
        }
        return percentPerKm
    }

    private fun recentObservations(
        windows: List<RangeDepletionWindow>,
        maxDistanceKm: Double,
    ): List<DepletionObservation> {
        var remainingDistanceKm = maxDistanceKm
        return buildList {
            windows.asSequence()
                .filter {
                    it.closedAt != null &&
                        it.observedDepletionPercent >= RangeCalibrationTracker.DEPLETION_STEP_PERCENT &&
                        it.recordedDistanceKm > 0.0
                }
                .sortedByDescending { it.closedAt }
                .forEach windowLoop@{ window ->
                    if (remainingDistanceKm <= 0.0) return@windowLoop
                    val fraction = min(1.0, remainingDistanceKm / window.recordedDistanceKm)
                    add(
                        DepletionObservation(
                            distanceKm = window.recordedDistanceKm * fraction,
                            depletionPercent = window.observedDepletionPercent * fraction,
                            speedBucketDistancesKm = window.speedBucketDistancesKm
                                .mapValues { (_, distanceKm) -> distanceKm * fraction },
                        ),
                    )
                    remainingDistanceKm -= window.recordedDistanceKm * fraction
                }
        }
    }

    private fun recentSpeedProfile(
        sessions: List<RideSummary>,
        maxDistanceKm: Double,
    ): Map<Int, Double> {
        var remainingDistanceKm = maxDistanceKm
        return buildMap {
            sessions.asSequence()
                .filter { it.isTrack && hasCompleteBucketCoverage(it) }
                .forEach sessionLoop@{ session ->
                    if (remainingDistanceKm <= 0.0) return@sessionLoop
                    val fraction = min(1.0, remainingDistanceKm / session.distanceKm)
                    session.speedBucketDistancesKm.forEach { (bucket, distanceKm) ->
                        put(bucket, (get(bucket) ?: 0.0) + distanceKm * fraction)
                    }
                    remainingDistanceKm -= session.distanceKm * fraction
                }
        }
    }

    private fun activeRideProfile(sessions: List<RideSummary>): Map<Int, Double>? = sessions
        .firstOrNull { it.active && hasCompleteBucketCoverage(it) }
        ?.speedBucketDistancesKm

    private fun pooledObservationProfile(
        observations: List<DepletionObservation>,
    ): Map<Int, Double> = buildMap {
        observations.forEach { observation ->
            observation.speedBucketDistancesKm.forEach { (bucket, distanceKm) ->
                put(bucket, (get(bucket) ?: 0.0) + distanceKm)
            }
        }
    }

    private fun hasCompleteBucketCoverage(session: RideSummary): Boolean {
        val bucketedDistance = session.speedBucketDistancesKm.values.sum()
        return session.distanceKm > 0.0 &&
            bucketedDistance >= session.distanceKm * MIN_BUCKET_COVERAGE_RATIO
    }

    private data class DepletionObservation(
        val distanceKm: Double,
        val depletionPercent: Double,
        val speedBucketDistancesKm: Map<Int, Double>,
    ) {
        fun hasCompleteBucketCoverage(): Boolean =
            speedBucketDistancesKm.values.sum() >= distanceKm * MIN_BUCKET_COVERAGE_RATIO
    }

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

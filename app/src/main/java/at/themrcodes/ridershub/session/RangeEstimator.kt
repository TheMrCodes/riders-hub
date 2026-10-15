package at.themrcodes.ridershub.session

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object RangeEstimator {
    private const val PROFILE_DISTANCE_KM = 100.0
    private const val MIN_DISTANCE_KM = 1.0
    private const val MIN_DEPLETION_PERCENT = 5.0
    private const val MIN_BUCKET_COVERAGE_RATIO = 0.95

    @JvmOverloads
    fun estimate(
        currentBatteryPercent: Int?,
        currentRestingVoltageV: Double?,
        depletionWindows: List<RangeDepletionWindow>,
        profileSessions: List<RideSummary>,
        calibrationPoints: List<CalibrationPoint>,
        estimatedBatteryPercent: Double? = null,
        selectedBoardId: String? = null,
        speedBucketStatistics: BoardSpeedBucketStatistics? = null,
    ): RangeEstimate {
        val voltageModel = fitVoltageModel(calibrationPoints)
        val boardStats = speedBucketStatistics?.takeIf { it.localBoardId == selectedBoardId }
        val accumulatedRates = boardStats?.consumptionRates().orEmpty()
        val useAccumulated = accumulatedRates.isNotEmpty() && boardStats != null &&
            boardStats.observedDistanceKm.isFinite() && boardStats.observedDistanceKm >= MIN_DISTANCE_KM &&
            boardStats.observedDepletionPercent.isFinite() && boardStats.observedDepletionPercent >= MIN_DEPLETION_PERCENT
        val observations = recentObservations(depletionWindows, PROFILE_DISTANCE_KM)
        val observedDistance = if (useAccumulated) requireNotNull(boardStats).observedDistanceKm
            else observations.sumOf { it.distanceKm }
        val observedDepletion = if (useAccumulated) requireNotNull(boardStats).observedDepletionPercent
            else observations.sumOf { it.depletionPercent }

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
                speedBucketStatistics = boardStats,
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
                speedBucketStatistics = boardStats,
            )
        }

        val aggregatePercentPerKm = 1.0 / aggregateKmPerPercent
        val bucketPercentPerKm = if (useAccumulated) accumulatedRates
            else fitBucketConsumption(observations)
        val commonProfile = boardStats?.measuredProfile().orEmpty()
            .ifEmpty { recentSpeedProfile(profileSessions, PROFILE_DISTANCE_KM) }
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
        val effectiveBattery = estimatedBatteryPercent
            ?.takeIf { it.isFinite() && it in 0.0..100.0 }
            ?: (voltagePercent
                ?.takeIf { it in (currentBatteryPercent - 15.0)..(currentBatteryPercent + 15.0) }
                ?.let { currentBatteryPercent * 0.7 + it * 0.3 }
                ?: currentBatteryPercent.toDouble())
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
            } else if (useAccumulated) {
                "Calibrated from accumulated per-board speed buckets"
            } else if (status == RangeEstimateStatus.CALIBRATED) {
                "Calibrated to your latest ${"%.0f".format(Locale.US, observedDistance)} km usage pattern"
            } else {
                "Provisional estimate from ${"%.1f".format(Locale.US, observedDistance)} km in 5% windows"
            },
            bucketBatteryPercentPer100Km = bucketPercentPerKm.mapValues { (_, rate) -> rate * 100.0 },
            commonSpeedBucketDistributionPercent = profileDistributionPercent,
            speedBucketStatistics = boardStats,
            usesAccumulatedBucketConsumption = useAccumulated,
        )
    }

    private fun fitBucketConsumption(
        observations: List<DepletionObservation>,
    ): Map<Int, Double> {
        val bucketed = observations.filter { it.hasCompleteBucketCoverage() }
        val bucketStarts = bucketed
            .flatMap { it.effectiveBuckets.keys }
            .distinct()
            .sorted()
        if (bucketStarts.isEmpty()) return emptyMap()

        val exposure = mutableMapOf<Int, Double>()
        val measured = mutableMapOf<Int, Double>()
        val target = mutableMapOf<Int, Double>()
        val cross = mutableMapOf<Int, MutableMap<Int, Double>>()
        val fittedDepletion = bucketed.sumOf { observation ->
            observation.measuredShare * observation.depletionPercent
        }
        val squaredDepletion = bucketed.sumOf { observation ->
            observation.measuredShare * observation.depletionPercent * observation.depletionPercent
        }
        val windowWeight = bucketed.sumOf { it.measuredShare }
        bucketed.forEach { observation ->
            observation.speedBucketDistancesKm.forEach { (bucket, distanceKm) ->
                measured[bucket] = (measured[bucket] ?: 0.0) + distanceKm
            }
            observation.effectiveBuckets.forEach { (bucket, distanceKm) ->
                exposure[bucket] = (exposure[bucket] ?: 0.0) +
                    observation.measuredShare * distanceKm
                target[bucket] = (target[bucket] ?: 0.0) + observation.measuredShare *
                    distanceKm * observation.depletionPercent
                val row = cross.getOrPut(bucket) { mutableMapOf() }
                observation.effectiveBuckets.forEach { (column, columnDistanceKm) ->
                    row[column] = (row[column] ?: 0.0) + observation.measuredShare *
                        distanceKm * columnDistanceKm
                }
            }
        }
        val shape = SpeedBucketQuadraticFit.fit(
            exposureKm = exposure,
            target = target,
            cross = cross,
            supportedBuckets = measured.filterValues { it >= MIN_DISTANCE_KM }.keys,
            weightedDepletion = fittedDepletion,
            weightedSquaredDepletion = squaredDepletion,
            windowWeight = windowWeight,
            acceptedWindows = bucketed.size.toLong(),
        )
        val consumption = SpeedBucketConsumptionFit.quadratic(
            exposure,
            fittedDepletion,
            shape.rollingCoefficient,
            shape.aerodynamicCoefficientPerKmhSquared,
        )
        return consumption.rates.filterKeys { it !in consumption.limitBuckets }
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
                            estimatedSpeedBucketDistancesKm = window.estimatedSpeedBucketDistancesKm
                                .mapValues { (_, distanceKm) -> distanceKm * fraction },
                        ),
                    )
                    remainingDistanceKm -= window.recordedDistanceKm * fraction
                }
        }
    }

    internal fun observedCommonProfile(sessions: List<RideSummary>): Map<Int, Double> =
        recentSpeedProfile(sessions, PROFILE_DISTANCE_KM)

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
        val estimatedSpeedBucketDistancesKm: Map<Int, Double>,
    ) {
        val effectiveBuckets: Map<Int, Double>
            get() = mergeSpeedBuckets(speedBucketDistancesKm, estimatedSpeedBucketDistancesKm)
        val measuredShare: Double
            get() = (speedBucketDistancesKm.values.sum() / distanceKm).coerceIn(0.0, 1.0)
        fun hasCompleteBucketCoverage(): Boolean =
            measuredShare > 0.0 && effectiveBuckets.values.sum() >= distanceKm * MIN_BUCKET_COVERAGE_RATIO
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

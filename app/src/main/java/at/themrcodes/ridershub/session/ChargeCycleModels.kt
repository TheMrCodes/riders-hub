package at.themrcodes.ridershub.session

import kotlin.math.max
import kotlin.math.min

enum class ChargeCycleStartReason {
    FIRST_OBSERVATION,
    INFERRED_RECHARGE,
}

enum class ChargeCycleEndReason {
    INFERRED_RECHARGE,
}

/**
 * Durable sufficient statistics for comparing usable range across charging cycles.
 *
 * A cycle is an observation window, not a claim that the charger was directly detected. The
 * protocol does not expose charger state, so a new cycle is inferred from a sufficiently large
 * battery increase between recorded rides. Keeping the speed buckets and odometer span allows a
 * later estimator to normalize cycles to one reference riding profile and reject incomplete data.
 */
data class ChargeCycleSummary(
    val id: String,
    val localBoardId: String,
    val startedAt: String,
    val lastObservedAt: String,
    val closedAt: String?,
    val startReason: ChargeCycleStartReason,
    val endReason: ChargeCycleEndReason?,
    val inferredRechargeIncreasePercent: Int?,
    val batteryStartPercent: Int,
    val batteryEndPercent: Int,
    val batteryMinPercent: Int,
    val batteryMaxPercent: Int,
    val restingVoltageStart: Double?,
    val restingVoltageEnd: Double?,
    val restingVoltageMin: Double?,
    val restingVoltageMax: Double?,
    val recordedDistanceKm: Double,
    val movingSeconds: Double,
    val speedBucketDistancesKm: Map<Int, Double>,
    val odometerStartKm: Double?,
    val odometerEndKm: Double?,
    val rideCount: Int,
) {
    val isClosed: Boolean
        get() = closedAt != null

    val observedDepletionPercent: Int
        get() = max(0, batteryMaxPercent - batteryMinPercent)

    val fullChargeObserved: Boolean
        get() = batteryMaxPercent >= FULL_CHARGE_CONFIDENCE_PERCENT

    val odometerDistanceKm: Double?
        get() = if (
            odometerStartKm != null &&
            odometerEndKm != null &&
            odometerEndKm >= odometerStartKm
        ) {
            odometerEndKm - odometerStartKm
        } else {
            null
        }

    val profiledDistanceKm: Double
        get() = speedBucketDistancesKm.values.sum()

    val profileCoverage: Double?
        get() = odometerDistanceKm
            ?.takeIf { it >= MIN_MEANINGFUL_DISTANCE_KM }
            ?.let { (profiledDistanceKm / it).coerceIn(0.0, 1.0) }

    companion object {
        const val FULL_CHARGE_CONFIDENCE_PERCENT = 95
        private const val MIN_MEANINGFUL_DISTANCE_KM = 0.02
    }
}

data class ChargeCycleStoreSnapshot(
    val activeCycles: List<ChargeCycleSummary>,
    val completedCycles: List<ChargeCycleSummary>,
)

data class ChargeCycleUpdate(
    val activeCycle: ChargeCycleSummary,
    val completedCycle: ChargeCycleSummary?,
)

object ChargeCycleTracker {
    const val MIN_INFERRED_RECHARGE_PERCENT = 5

    fun recordRide(
        current: ChargeCycleSummary?,
        ride: RideSummary,
        localBoardId: String,
    ): ChargeCycleUpdate? {
        if (!ride.isTrack) return null
        val rideStartPercent = ride.boardBatteryStart?.takeIf { it in 0..100 } ?: return null
        val rideEndPercent = ride.boardBatteryEnd?.takeIf { it in 0..100 } ?: return null
        val belongsToCurrentBoard = current?.takeIf { it.localBoardId == localBoardId }
        val rechargeIncrease = belongsToCurrentBoard?.let {
            rideStartPercent - it.batteryEndPercent
        }
        val rechargeDetected = rechargeIncrease != null &&
            rechargeIncrease >= MIN_INFERRED_RECHARGE_PERCENT

        if (belongsToCurrentBoard == null || rechargeDetected) {
            val completed = belongsToCurrentBoard?.copy(
                closedAt = ride.startedAt,
                endReason = ChargeCycleEndReason.INFERRED_RECHARGE,
            )
            return ChargeCycleUpdate(
                activeCycle = newCycle(
                    ride = ride,
                    localBoardId = localBoardId,
                    startReason = if (rechargeDetected) {
                        ChargeCycleStartReason.INFERRED_RECHARGE
                    } else {
                        ChargeCycleStartReason.FIRST_OBSERVATION
                    },
                    inferredRechargeIncreasePercent = rechargeIncrease?.takeIf { rechargeDetected },
                ),
                completedCycle = completed,
            )
        }

        return ChargeCycleUpdate(
            activeCycle = belongsToCurrentBoard.add(ride),
            completedCycle = null,
        )
    }

    private fun newCycle(
        ride: RideSummary,
        localBoardId: String,
        startReason: ChargeCycleStartReason,
        inferredRechargeIncreasePercent: Int?,
    ): ChargeCycleSummary {
        val startPercent = requireNotNull(ride.boardBatteryStart)
        val endPercent = requireNotNull(ride.boardBatteryEnd)
        return ChargeCycleSummary(
            id = "cycle-${ride.id}",
            localBoardId = localBoardId,
            startedAt = ride.startedAt,
            lastObservedAt = ride.endedAt ?: ride.lastFrameAt ?: ride.startedAt,
            closedAt = null,
            startReason = startReason,
            endReason = null,
            inferredRechargeIncreasePercent = inferredRechargeIncreasePercent,
            batteryStartPercent = startPercent,
            batteryEndPercent = endPercent,
            batteryMinPercent = min(startPercent, min(endPercent, ride.boardBatteryMin ?: endPercent)),
            batteryMaxPercent = max(startPercent, endPercent),
            restingVoltageStart = ride.restingVoltageStart,
            restingVoltageEnd = ride.restingVoltageEnd,
            restingVoltageMin = listOfNotNull(ride.restingVoltageStart, ride.restingVoltageEnd).minOrNull(),
            restingVoltageMax = listOfNotNull(ride.restingVoltageStart, ride.restingVoltageEnd).maxOrNull(),
            recordedDistanceKm = ride.distanceKm,
            movingSeconds = ride.movingSeconds,
            speedBucketDistancesKm = ride.speedBucketDistancesKm,
            odometerStartKm = ride.odometerStartKm,
            odometerEndKm = ride.odometerEndKm,
            rideCount = 1,
        )
    }

    private fun ChargeCycleSummary.add(ride: RideSummary): ChargeCycleSummary {
        val startPercent = requireNotNull(ride.boardBatteryStart)
        val endPercent = requireNotNull(ride.boardBatteryEnd)
        val rideMin = min(startPercent, min(endPercent, ride.boardBatteryMin ?: endPercent))
        val restingValues = listOfNotNull(
            restingVoltageMin,
            restingVoltageMax,
            ride.restingVoltageStart,
            ride.restingVoltageEnd,
        )
        return copy(
            lastObservedAt = ride.endedAt ?: ride.lastFrameAt ?: ride.startedAt,
            batteryEndPercent = endPercent,
            batteryMinPercent = min(batteryMinPercent, rideMin),
            batteryMaxPercent = max(batteryMaxPercent, max(startPercent, endPercent)),
            restingVoltageEnd = ride.restingVoltageEnd ?: restingVoltageEnd,
            restingVoltageMin = restingValues.minOrNull(),
            restingVoltageMax = restingValues.maxOrNull(),
            recordedDistanceKm = recordedDistanceKm + ride.distanceKm,
            movingSeconds = movingSeconds + ride.movingSeconds,
            speedBucketDistancesKm = mergeSpeedBuckets(
                speedBucketDistancesKm,
                ride.speedBucketDistancesKm,
            ),
            odometerStartKm = odometerStartKm ?: ride.odometerStartKm,
            odometerEndKm = ride.odometerEndKm ?: odometerEndKm,
            rideCount = rideCount + 1,
        )
    }
}

internal fun mergeSpeedBuckets(
    first: Map<Int, Double>,
    second: Map<Int, Double>,
): Map<Int, Double> = buildMap {
    first.forEach { (bucket, distance) -> put(bucket, distance) }
    second.forEach { (bucket, distance) -> put(bucket, (get(bucket) ?: 0.0) + distance) }
}

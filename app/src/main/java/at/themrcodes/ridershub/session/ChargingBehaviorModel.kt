package at.themrcodes.ridershub.session

import kotlin.math.ceil
import kotlin.math.floor

internal data class ChargingBehaviorEstimate(
    val notificationThresholdPercent: Int,
    val confirmedChargeCount: Int,
    val lowerChargeStartPercent: Int,
)

/**
 * Learns when this board's user normally decides to charge.
 *
 * This is intentionally separate from the physical low-output threshold. It may
 * influence only an end-of-trip reminder, never SOC, capacity, range or power.
 */
internal object ChargingBehaviorEstimator {
    const val MIN_CONFIRMED_CHARGES = 3
    private const val LOWER_QUANTILE = 0.20
    private const val NOTIFICATION_STEP_PERCENT = 5
    private const val MAX_NOTIFICATION_PERCENT = 50

    fun estimate(
        completedCycles: List<ChargeCycleSummary>,
        localBoardId: String,
    ): ChargingBehaviorEstimate? {
        val starts = completedCycles.asSequence()
            .filter {
                it.localBoardId == localBoardId &&
                    it.endReason == ChargeCycleEndReason.INFERRED_RECHARGE
            }
            // The last settled value before the confirmed rise is the closest retained
            // evidence for the user's charge-start decision. Do not use the cycle minimum:
            // a transient sag or earlier low point is not charging behaviour.
            .map { it.batteryEndPercent }
            .filter { it in 0..100 }
            .sorted()
            .toList()
        if (starts.size < MIN_CONFIRMED_CHARGES) return null

        val index = floor((starts.size - 1) * LOWER_QUANTILE).toInt()
        val lowerStart = starts[index]
        val rounded = (ceil(lowerStart.toDouble() / NOTIFICATION_STEP_PERCENT) *
            NOTIFICATION_STEP_PERCENT).toInt()
        return ChargingBehaviorEstimate(
            notificationThresholdPercent = rounded.coerceIn(
                NOTIFICATION_STEP_PERCENT,
                MAX_NOTIFICATION_PERCENT,
            ),
            confirmedChargeCount = starts.size,
            lowerChargeStartPercent = lowerStart,
        )
    }
}

package at.themrcodes.ridershub.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargingBehaviorModelTest {
    @Test fun requiresThreeConfirmedChargesForAnEndOfTripThreshold() {
        assertNull(ChargingBehaviorEstimator.estimate(listOf(cycle("a", 18), cycle("b", 21)), BOARD))
    }

    @Test fun learnsTheLowerRegularChargeStartAndRoundsUpForTheUx() {
        val estimate = ChargingBehaviorEstimator.estimate(
            listOf(cycle("a", 21), cycle("b", 17), cycle("c", 24), cycle("d", 19), cycle("e", 42)),
            BOARD,
        )!!
        assertEquals(5, estimate.confirmedChargeCount)
        assertEquals(17, estimate.lowerChargeStartPercent)
        assertEquals(20, estimate.notificationThresholdPercent)
    }

    @Test fun ignoresOtherBoardsAndUnconfirmedCycleEnds() {
        val cycles = listOf(
            cycle("a", 17), cycle("b", 18),
            cycle("c", 19).copy(endReason = null),
            cycle("d", 40).copy(localBoardId = "other-board"),
        )
        assertNull(ChargingBehaviorEstimator.estimate(cycles, BOARD))
    }

    private fun cycle(id: String, chargeStartPercent: Int) = ChargeCycleSummary(
        id = id,
        localBoardId = BOARD,
        startedAt = "2030-01-01T00:00:00Z",
        lastObservedAt = "2030-01-01T01:00:00Z",
        closedAt = "2030-01-01T02:00:00Z",
        startReason = ChargeCycleStartReason.INFERRED_RECHARGE,
        endReason = ChargeCycleEndReason.INFERRED_RECHARGE,
        inferredRechargeIncreasePercent = 20,
        batteryStartPercent = 80,
        batteryEndPercent = chargeStartPercent,
        batteryMinPercent = chargeStartPercent,
        batteryMaxPercent = 80,
        restingVoltageStart = null,
        restingVoltageEnd = null,
        restingVoltageMin = null,
        restingVoltageMax = null,
        recordedDistanceKm = 10.0,
        movingSeconds = 1_000.0,
        speedBucketDistancesKm = mapOf(20 to 10.0),
        odometerStartKm = null,
        odometerEndKm = null,
        rideCount = 1,
    )

    private companion object {
        const val BOARD = "synthetic-board"
    }
}

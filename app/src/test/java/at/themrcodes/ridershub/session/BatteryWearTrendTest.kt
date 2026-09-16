package at.themrcodes.ridershub.session

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class BatteryWearTrendTest {
    private fun points() = (0..5).map { index ->
        BatteryCapacityObservation(
            cycleId = "cycle-$index",
            observedAt = Instant.parse("2030-01-01T00:00:00Z").plus(index * 20L, ChronoUnit.DAYS),
            normalizedFullRangeKm = if (index < 3) 50.0 else 45.0,
            observedDistanceKm = 10.0, observedDepletionPercent = 20.0,
            fullChargeObserved = false, odometerKm = index * 200.0, completed = true,
        )
    }

    @Test fun estimatesRelativeRetentionAcrossKilometresAndMonths() {
        val trend = requireNotNull(BatteryWearTrendEstimator.estimate(points()))
        assertEquals(90.0, trend.retentionPercent, 0.0001)
        assertEquals(600.0, trend.distanceSpanKm, 0.0001)
        assertEquals(-10.0 * 1000 / 600, trend.changePercentPer1000Km, 0.0001)
    }

    @Test fun oneUnusualWindowDoesNotMoveTheMedianBaseline() {
        val points = points().toMutableList()
        points[1] = points[1].copy(normalizedFullRangeKm = 150.0)
        assertEquals(90.0, BatteryWearTrendEstimator.estimate(points)!!.retentionPercent, 0.0001)
    }

    @Test fun requiresEnoughIndependentCompletedWindowsTimeAndDistance() {
        val points = points()
        assertNull(BatteryWearTrendEstimator.estimate(points.dropLast(1)))
        assertNull(BatteryWearTrendEstimator.estimate(points.map { it.copy(completed = false) }))
        assertNull(BatteryWearTrendEstimator.estimate(points.map { it.copy(odometerKm = it.odometerKm!! / 10) }))
        assertNull(BatteryWearTrendEstimator.estimate(points.map { it.copy(observedAt = points[0].observedAt) }))
        assertNull(BatteryWearTrendEstimator.estimate(points.map { it.copy(cycleId = "same") }))
    }

    @Test fun odometerResetOrUnknownDistanceWithholdsTrend() {
        val points = points().toMutableList()
        points[3] = points[3].copy(odometerKm = 0.0)
        assertNull(BatteryWearTrendEstimator.estimate(points))
        assertNull(BatteryWearTrendEstimator.estimate(points().map { it.copy(odometerKm = null) }))
    }

    @Test fun improvementIsNotClampedIntoAnAgingClaim() {
        val points = points().mapIndexed { i, p -> p.copy(normalizedFullRangeKm = if (i < 3) 50.0 else 55.0) }
        assertEquals(110.0, BatteryWearTrendEstimator.estimate(points)!!.retentionPercent, 0.0001)
    }
}

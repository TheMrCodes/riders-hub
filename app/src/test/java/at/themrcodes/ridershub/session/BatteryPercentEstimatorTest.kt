package at.themrcodes.ridershub.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryPercentEstimatorTest {
    @Test fun firstObservationInitializesAnEstimate() {
        assertEquals(60.0, update(null), 0.0)
    }

    @Test fun changedObservationsDoNotSnapOrClipTheEstimate() {
        for (reported in listOf(0, 55, 65, 100)) {
            val estimate = update(58.3, reported, elapsed = 0.1)
            assertTrue(kotlin.math.abs(estimate - 58.3) <= 0.1000001)
        }
        assertEquals(57.0, update(57.0, reported = 55), 0.0)
    }

    @Test fun consumptionContinuesWhenTheReportedValueChanges() {
        assertEquals(55.98, update(56.0, reported = 55, distance = .005, rate = 4.0), 0.000001)
    }

    @Test fun learnedConsumptionMovesWithinAReportedPlateau() {
        var percent = update(null)
        repeat(60) { percent = update(percent, distance = 0.005, rate = 4.0) }
        assertEquals(58.8, percent, 0.00001)
    }

    @Test fun modelAvailabilityDoesNotPullPlateausBackToPrintedValue() {
        assertEquals(58.0, update(58.0, rate = 4.0), 0.00001)
    }

    @Test fun noModelStillAllowsObservationGuidanceWithoutInventingConsumption() {
        assertEquals(60.0, update(60.0, distance = 1.0), 0.0)
        assertTrue(update(60.0, reported = 55) in 59.0..59.999)
        val estimate = update(60.0, reported = 40, distance = 1.0)
        assertTrue(estimate in 59.0..59.999)
    }

    @Test fun prolongedIncorrectConsumptionCannotRunAwayFromTheReference() {
        var percent = 60.0
        repeat(3000) { percent = update(percent, distance = .01, rate = 30.0) }
        assertTrue(percent in 57.5..57.501)
    }

    @Test fun sustainedObservationsGraduallyCorrectInEitherDirection() {
        var high = 70.0
        var low = 40.0
        repeat(300) {
            high = update(high)
            low = update(low)
        }
        assertTrue(high in 62.5..62.501)
        assertTrue(low in 57.499..57.5)
    }

    @Test fun shortReboundsHaveLimitedInfluence() {
        var percent = 58.0
        repeat(10) { percent = update(percent, reported = 80, elapsed = .1) }
        assertTrue(percent <= 59.000001)
        repeat(100) { percent = update(percent, reported = 60, elapsed = .1) }
        assertTrue(percent <= 59.000001)
    }

    @Test fun gapsAndClockResetsCannotConsumeUnobservedDistance() {
        for (seconds in listOf(0.0, -1.0, 3.0, 120.0, Double.NaN)) {
            assertEquals(58.0, update(58.0, reported = 20, elapsed = seconds, distance = 10.0, rate = 4.0), 0.0)
        }
    }

    @Test fun validatedInferredGapDistanceAdvancesStateWithoutApplyingObservationTime() {
        val transition = InferredDriveTransition(
            distanceKm = 1.0,
            expectedBatteryUsePercent = 4.0,
            effectivePercentPerKm = 4.0,
        )
        val result = BatteryPercentEstimator.propagateInferredDrive(60.0, 55, transition)
        assertEquals(56.0, result.percent, 1e-12)
        assertEquals(4.0, result.consumptionPercent, 1e-12)
        assertEquals(0.0, result.observationCorrectionPercent, 0.0)
    }

    @Test fun observationGuidanceDependsOnTimeRatherThanFrameCount() {
        var fast = 55.0
        var slow = 55.0
        repeat(100) { fast = update(fast, elapsed = .1) }
        repeat(10) { slow = update(slow, elapsed = 1.0) }
        assertEquals(slow, fast, 0.000001)
    }

    @Test fun predictionDependsOnDistanceRatherThanFrameCount() {
        var fast = 60.0
        var slow = 60.0
        repeat(100) { fast = update(fast, elapsed = 0.1, distance = 0.001, rate = 4.0) }
        repeat(10) { slow = update(slow, elapsed = 1.0, distance = 0.01, rate = 4.0) }
        assertEquals(slow, fast, 0.000001)
    }

    @Test fun consumptionTaperAlsoDoesNotDependOnFrameCount() {
        var fast = 60.0
        var slow = 60.0
        repeat(1000) { fast = update(fast, elapsed = .1, distance = .001, rate = 4.0) }
        repeat(100) { slow = update(slow, elapsed = 1.0, distance = .01, rate = 4.0) }
        assertEquals(slow, fast, 0.000001)
        assertTrue(fast > 57.5)
    }

    @Test fun invalidInputsCannotCorruptTheEstimate() {
        assertEquals(0.0, update(0.0, reported = 0, distance = 1.0, rate = 4.0), 0.0)
        assertEquals(60.0, update(60.0, distance = Double.NaN, rate = 4.0), 0.0)
        assertEquals(60.0, update(60.0, distance = 1.0, rate = Double.NaN), 0.0)
    }

    private fun update(
        previous: Double?, reported: Int = 60,
        elapsed: Double = 1.0, distance: Double = 0.0, rate: Double? = null,
    ) = BatteryPercentEstimator.observe(previous, reported, elapsed, distance, rate)
}

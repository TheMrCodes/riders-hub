package at.themrcodes.ridershub.session

import org.junit.Assert.*
import org.junit.Test

class ProvisionalPowerEstimatorTest {
    @Test fun bootstrapAndLearnedZeroRetainDriveScaleAndSuppressFalseRecovery() {
        val bootstrap = RawLoadZeroEstimate()
        for (raw in -10..10) assertEquals(0.0,
            ProvisionalPowerEstimator.estimate(48.0, bootstrap.effectiveRaw(raw), true, 18.0)!!, 0.0)
        val learned = RawLoadZeroEstimate(zeroRaw = 7.0, noiseDeviationRaw = 0.0)
        for (raw in 6..8) assertEquals(0.0,
            ProvisionalPowerEstimator.estimate(48.0, learned.effectiveRaw(raw), true, 18.0)!!, 0.0)
        val draw = ProvisionalPowerEstimator.estimate(48.0, learned.effectiveRaw(108), true, 18.0)!!
        val braking = ProvisionalPowerEstimator.estimate(48.0, learned.effectiveRaw(-94), true, 18.0)!!
        assertEquals(98.38687659706423, draw, 1e-9)
        assertEquals(-480.0 * (100.0 / 670.0) * 5.0 * 0.40, braking, 1e-9)
    }

    @Test fun recoveryUsesSpeedAndCommandWithBoundedMechanicalPower() {
        fun power(speed: Double, command: Double = -670.0) =
            ProvisionalPowerEstimator.estimate(48.0, command, true, speed)!!
        assertEquals(0.0, power(0.0), 0.0)
        assertEquals(-192.0, power(3.6), 1e-9)
        assertEquals(-96.0, power(3.6, -335.0), 1e-9)
        assertEquals(-1152.0, power(21.6), 1e-9)
        assertEquals(power(21.6), power(72.0, -2000.0), 1e-9)
        assertTrue(power(18.0, 100.0) > 0.0)
        assertEquals(0.0, power(36.0, 0.0), 0.0)
        // Voltage rebound is not a measurement of regenerated current.
        assertEquals(power(18.0), ProvisionalPowerEstimator.estimate(52.0, -670.0, true, 18.0)!!, 0.0)
    }

    @Test fun invalidFrameCannotBecomeAZeroOrStalePowerReading() {
        assertNull(ProvisionalPowerEstimator.estimate(48.0, 100.0, false, 18.0))
        assertNull(ProvisionalPowerEstimator.estimate(48.0, null, true, 18.0))
        for (voltage in listOf(0.0, 71.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(ProvisionalPowerEstimator.estimate(voltage, 100.0, true, 18.0))
        }
        for (speed in listOf(-1.0, 201.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(ProvisionalPowerEstimator.estimate(48.0, -100.0, true, speed))
        }
        for (load in listOf(Double.NaN, Double.POSITIVE_INFINITY, 32779.0)) {
            assertNull(ProvisionalPowerEstimator.estimate(48.0, load, true, 18.0))
        }
    }

    @Test fun signedLoadChangesDiagnosticPowerWithoutCreditingBatteryOrRange() {
        fun replay(load: Int): BatteryEstimateState {
            val tracker = BatteryObservationTracker()
            var state = BatteryEstimateState(60.0, 60)
            repeat(100) {
                val diagnostic = ProvisionalPowerEstimator.estimate(48.0, load.toDouble(), true, 18.0)!!
                assertEquals(load < 0, diagnostic < 0)
                state = tracker.observe(state, 60, .1, .001, 3.0, 48.0, load)
            }
            return state
        }
        val draw = replay(500)
        val braking = replay(-500)
        assertEquals(draw, braking)
        assertTrue(braking.percent < 60)
        assertEquals(BatteryCapacityEstimate(), braking.capacity)
        // The range estimator receives the same SOC and consumption history in either case.
        assertEquals(draw.percent / 3.0, braking.percent / 3.0, 0.0)
    }
}

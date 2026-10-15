package at.themrcodes.ridershub.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawLoadZeroEstimatorTest {
    @Test fun bootstrapIgnoresAbsoluteValuesFromMinusTenThroughTen() {
        val state = RawLoadZeroEstimate()
        (-10..10).forEach { assertEquals(0.0, state.effectiveRaw(it), 0.0) }
        assertEquals(1.0, state.effectiveRaw(11), 0.0)
        assertEquals(-1.0, state.effectiveRaw(-11), 0.0)
    }

    @Test fun stationarySamplesLearnSevenAsTheBoardSpecificZero() {
        var state = RawLoadZeroEstimate()
        repeat(50) {
            state = RawLoadZeroEstimator.observe(state, 7, true, 0.1)
        }
        assertFalse(state.calibrated) // First five seconds establish stationary eligibility.
        repeat(RawLoadZeroEstimator.MIN_BOOTSTRAP_SAMPLES) {
            state = RawLoadZeroEstimator.observe(state, 7, true, 0.1)
        }
        assertTrue(state.calibrated)
        assertEquals(7.0, state.zeroRaw!!, 0.0)
        assertEquals(1.0, state.deadbandRaw, 0.0)
        assertEquals(0.0, state.effectiveRaw(8), 0.0)
        assertEquals(1.0, state.effectiveRaw(9), 0.0)
    }

    @Test fun movementResetsDwellAndCannotBecomeCalibrationEvidence() {
        var state = RawLoadZeroEstimate()
        repeat(200) { state = RawLoadZeroEstimator.observe(state, 7, false, 0.1) }
        assertFalse(state.calibrated)
        assertEquals(0, state.acceptedSampleCount)
        assertEquals(0.0, state.stationarySeconds, 0.0)
        repeat(40) { state = RawLoadZeroEstimator.observe(state, 7, true, 0.1) }
        state = RawLoadZeroEstimator.observe(state, 7, false, 0.1)
        repeat(40) { state = RawLoadZeroEstimator.observe(state, 7, true, 0.1) }
        assertEquals(0, state.acceptedSampleCount)
    }

    @Test fun registryPersistsCalibrationAndKeepsBoardsIndependent() {
        val registry = RawLoadZeroRegistry()
        repeat(100) { registry.observe("board-a", 7, true, 0.1) }
        repeat(100) { registry.observe("board-b", -2, true, 0.1) }
        val restored = RawLoadZeroRegistry(registry.toJson().toString())
        assertEquals(7.0, restored.state("board-a")!!.zeroRaw!!, 0.0)
        assertEquals(-2.0, restored.state("board-b")!!.zeroRaw!!, 0.0)
        assertNull(restored.state("board-c"))
    }
}

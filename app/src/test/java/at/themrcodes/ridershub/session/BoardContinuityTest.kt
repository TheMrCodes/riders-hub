package at.themrcodes.ridershub.session

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BoardContinuityTest {
    private fun sample(
        state: BoardContinuityState?, at: Long, percent: Int = 50,
        odo: Double = 100.0, speed: Double = 18.0,
    ) = BoardContinuityTracker.observe(state, at, percent, odo, speed)

    @Test fun confirmsChargeAfterThirtyObservedSecondsEvenWhileRiding() {
        var state = sample(null, 1_000, 40).state
        for (second in 1..29) {
            val update = sample(state, 1_000L + second * 1_000, 50)
            assertNull(update.rechargeIncreasePercent)
            assertTrue(update.chargePending)
            state = update.state
        }
        val charge = sample(state, 31_000, 50)
        assertEquals(10, charge.rechargeIncreasePercent)
        assertFalse(charge.chargePending)
        assertNull(sample(charge.state, 32_000, 50).rechargeIncreasePercent)
    }

    @Test fun shortTenPointRiseAndSustainedFivePointRiseAreNotCharges() {
        var state = sample(null, 1_000, 40).state
        for (second in 1..20) state = sample(state, 1_000L + second * 1_000, 50).state
        val rebound = sample(state, 22_000, 40)
        assertFalse(rebound.chargePending)
        assertEquals(0.0, rebound.state.chargeCandidateSeconds, 0.0)
        state = rebound.state
        for (second in 1..60) {
            val update = sample(state, 22_000L + second * 1_000, 45)
            assertFalse(update.chargePending)
            assertNull(update.rechargeIncreasePercent)
            state = update.state
        }
    }

    @Test fun separateSettledReboundsDoNotAccumulateIntoACharge() {
        var state = sample(null, 1_000, 40).state
        for (second in 1..120) {
            val percent = if (second <= 60) 45 else 50
            val update = sample(state, 1_000L + second * 1_000, percent)
            assertNull(update.rechargeIncreasePercent)
            state = update.state
        }
        assertEquals(50, state.baselinePercent)
    }

    @Test fun briefDownwardSagDoesNotMakeRecoveryLookLikeACharge() {
        var state = sample(null, 1_000, 25).state
        for (second in 1..60) {
            val percent = if (second <= 10) 20 else 30
            val update = sample(state, 1_000L + second * 1_000, percent)
            assertNull(update.rechargeIncreasePercent)
            state = update.state
        }
        assertEquals(30, state.baselinePercent)
    }

    @Test fun missedTimeDoesNotCountAsChargeConfirmationOrSpeedCoverage() {
        val start = sample(null, 1_000, 40).state
        val pending = sample(start, 2_000, 55).state
        val resumed = sample(pending, 62_000, 55, odo = 100.4)
        assertEquals("missing_frames", resumed.gapReason)
        assertEquals(0.4, resumed.odometerDeltaKm!!, 0.0001)
        assertEquals(0.0, resumed.intervalDistanceKm, 0.0)
        assertEquals(0.0, resumed.state.chargeCandidateSeconds, 0.0)
        assertNull(resumed.rechargeIncreasePercent)
    }

    @Test fun sameMillisecondFramesPreserveOrderAndTheAccumulatedDepletionWindow() {
        val first = sample(null, 1_000, 50).state
        val pending = RangeCalibrationTracker.observe(
            null, "board", 50, "2030-01-01T00:00:00Z", 2.5, mapOf(15 to 2.5),
        )!!.activeWindow
        val next = sample(first, 1_000, 45, speed = 16.0)
        assertNull(next.gapReason)
        assertNull(next.calibrationGapReason)
        assertEquals(45, next.state.lastReportedPercent)
        assertEquals(16.0, next.state.speedKmh, 0.0)
        assertEquals(0.0, next.intervalDistanceKm, 0.0)
        val completed = RangeCalibrationTracker.observe(
            pending, "board", 45, "2030-01-01T00:00:00Z", next.intervalDistanceKm,
        )!!.completedWindow!!
        assertEquals(2.5, completed.recordedDistanceKm, 0.0)
        assertEquals(5, completed.observedDepletionPercent)
        val following = sample(next.state, 2_000, 45, speed = 20.0)
        assertEquals(18.0 / 3600.0, following.intervalDistanceKm, 0.000001)
    }

    @Test fun sameMillisecondChargeObservationsDoNotAdvanceConfirmation() {
        val first = sample(null, 1_000, 40).state
        val pending = sample(first, 2_000, 50)
        val repeated = sample(pending.state, 2_000, 50)
        assertNull(repeated.gapReason)
        assertNull(repeated.rechargeIncreasePercent)
        assertEquals(pending.state.chargeCandidateSeconds, repeated.state.chargeCandidateSeconds, 0.0)
    }

    @Test fun counterQuantizationIsAcceptedButResetAndJumpSplitTheProfile() {
        val first = sample(null, 1_000).state
        val tick = sample(first, 1_100, odo = 100.1)
        assertNull(tick.gapReason)
        assertEquals(0.0005, tick.intervalDistanceKm, 0.000001)
        assertEquals("odometer_jump", sample(first, 1_100, odo = 101.0).gapReason)
        assertEquals("odometer_reset", sample(first, 1_100, 80, 0.0).gapReason)
        assertFalse(sample(first, 1_100, 80, 0.0).chargePending)
        assertEquals("clock_discontinuity", sample(first, 900).gapReason)
        assertEquals("invalid_telemetry", sample(first.copy(interrupted = true), 1_100).gapReason)
    }

    @Test fun unchangedStationaryPausePreservesCalibrationButDoesNotInventSpeedData() {
        val first = sample(null, 1_000, speed = 0.0).state
        val pause = sample(first, 61_000, speed = 0.0)
        assertEquals("missing_frames", pause.gapReason)
        assertNull(pause.calibrationGapReason)
        assertEquals(0.0, pause.intervalDistanceKm, 0.0)
        assertNull(pause.intervalSpeedKmh)
        assertEquals(0, pause.batteryDeltaPercent)
    }

    @Test fun countersIdentifyUnobservedUseEvenWhenBothEndpointsAreStationary() {
        val first = sample(null, 1_000, speed = 0.0).state
        assertEquals("unobserved_distance",
            sample(first, 61_000, odo = 102.0, speed = 0.0).calibrationGapReason)
        for (percent in listOf(40, 60, 65)) {
            assertEquals("unobserved_battery_change",
                sample(first, 61_000, percent, speed = 0.0).calibrationGapReason)
        }
        assertNull(sample(first, 61_000).calibrationGapReason)
        assertEquals("unknown_prior_battery",
            sample(first.copy(lastReportedPercent = null), 61_000, speed = 0.0).calibrationGapReason)
    }

    @Test fun smallGapDistanceIsRetainedWithoutInventingASpeedBucket() {
        val first = sample(null, 1_000).state
        for (km in listOf(0.0, 0.1, 1.9)) {
            val gap = sample(first, 61_000, 45, 100.0 + km)
            assertNull(gap.calibrationGapReason)
            assertEquals(km, gap.unprofiledDistanceKm, 0.000001)
            assertNull(gap.intervalSpeedKmh)
            assertEquals(0.0, gap.intervalDistanceKm, 0.0)
        }
        for (km in listOf(2.0, 2.1)) {
            val gap = sample(first, 61_000, odo = 100.0 + km)
            assertEquals("unobserved_distance", gap.calibrationGapReason)
            assertEquals(0.0, gap.unprofiledDistanceKm, 0.0)
        }
    }

    @Test fun liveConsumptionIsNotConfusedWithMissingData() {
        val first = sample(null, 1_000).state
        val live = sample(first, 2_000, 45, 100.1)
        assertNull(live.gapReason)
        assertNull(live.calibrationGapReason)
        assertEquals(-5, live.batteryDeltaPercent)
    }

    @Test fun persistedBoardStateRetainsBaselineAcrossRestartAndGap() {
        val first = sample(null, 1_000, 40).state
        val saved = sample(first, 2_000, 45).state
        val restored = BoardContinuityState.fromJson(JSONObject(saved.toJson().toString()))
        assertEquals(saved, restored)
        val next = sample(restored, 70_000, 55)
        assertEquals(40, next.state.baselinePercent)
        assertTrue(next.chargePending)
        assertEquals("missing_frames", next.gapReason)
        // A different board initializes independently at its own reported percentage.
        assertEquals(55, sample(null, 70_000, 55).state.baselinePercent)
    }
}

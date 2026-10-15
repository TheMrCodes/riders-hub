package at.themrcodes.ridershub.session

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BatteryEstimateStateTest {
    private class Stream(initial: BatteryEstimateState = BatteryEstimateState(60.0, 60)) {
        var state = initial
        val tracker = BatteryObservationTracker()
        fun tick(raw: Int = 60, seconds: Double = .1, distance: Double = 0.0,
                 rate: Double? = null, voltage: Double = 44.0, input: Int = 7) {
            state = tracker.observe(state, raw, seconds, distance, rate, voltage, input)
        }
    }

    @Test fun newBoardsStartWithAnExplicitUncalibrated346WhPrior() {
        val state = BatteryEstimateState(60.0, 60)
        assertEquals(346.0, state.capacity.estimatedWh, 0.0)
        assertEquals(0, state.capacity.observationCount)
        assertEquals(0.0, state.capacity.qualifiedDepletionPercent, 0.0)
        assertEquals(BatteryCapacityStatus.PRIOR, state.capacity.status)
    }

    @Test fun substantialCapacityObservationsMoveThePriorSlowlyAndTrackEvidence() {
        val initial = BatteryCapacityEstimate()
        assertEquals(initial, BatteryCapacityEstimator.observe(initial, 500.0, 19.9))
        val first = BatteryCapacityEstimator.observe(initial, 500.0, 40.0)
        assertTrue(first.estimatedWh > 346.0)
        assertEquals(1, first.observationCount)
        assertEquals(40.0, first.qualifiedDepletionPercent, 0.0)
        assertEquals(BatteryCapacityStatus.LEARNING, first.status)
        val second = BatteryCapacityEstimator.observe(first, 500.0, 40.0)
        val third = BatteryCapacityEstimator.observe(second, 500.0, 40.0)
        assertEquals(BatteryCapacityStatus.TRACKING, third.status)
        assertTrue(third.estimatedWh < 500.0)
    }

    @Test fun transientReportedReboundDoesNotMoveTheEstimate() {
        val s = Stream()
        repeat(20) { s.tick(80) }
        repeat(50) { s.tick(60) }
        assertEquals(BatteryEstimateState(60.0, 60), s.state)
    }

    @Test fun confirmedChangesCorrectSmoothlyWithoutSnapping() {
        val s = Stream()
        repeat(50) { s.tick() } // Finish initial acquisition before a normal in-ride change.
        var largest = 0.0
        repeat(600) {
            val previous = s.state.percent
            s.tick(40)
            largest = maxOf(largest, kotlin.math.abs(s.state.percent - previous))
        }
        assertTrue(largest <= .100001)
        assertTrue(s.state.percent in 42.5..42.501)
        assertEquals(40, s.state.referencePercent)
    }

    @Test fun chargingAfterReconnectCatchesUpWithoutUsingOfflineTime() {
        val s = Stream(BatteryEstimateState(25.0, 25))
        s.tick(90, seconds = 3600.0, distance = 10.0)
        assertEquals(25.0, s.state.percent, 0.0)
        repeat(150) { s.tick(90) }
        assertTrue(s.state.percent in 87.4..87.5)
    }

    @Test fun loadAndChangingVoltageReduceCorrectionSpeed() {
        val settled = Stream(BatteryEstimateState(50.0, 50))
        val loaded = Stream(BatteryEstimateState(50.0, 50))
        val changing = Stream(BatteryEstimateState(50.0, 50))
        repeat(50) { settled.tick(50); loaded.tick(50); changing.tick(50) }
        repeat(200) {
            settled.tick(60)
            loaded.tick(60, input = 800)
            changing.tick(60, voltage = if (it % 2 == 0) 43.0 else 44.0)
        }
        assertTrue(settled.state.percent > loaded.state.percent)
        assertEquals(loaded.state.percent, changing.state.percent, .000001)
    }

    @Test fun interruptedEvidenceDoesNotConfirmANewReading() {
        val s = Stream()
        repeat(20) { s.tick(80) }
        s.tick(80, seconds = 0.0)
        repeat(20) { s.tick(80) }
        assertEquals(BatteryEstimateState(60.0, 60), s.state)
    }

    @Test fun estimateSurvivesSerializationButSettlingEvidenceDoesNot() {
        val capacity = BatteryCapacityEstimate(340.0, 2, 80.0)
        val s = Stream(BatteryEstimateState(58.125, 60, capacity))
        repeat(20) { s.tick(80) }
        val registry = BatteryEstimateRegistry()
        registry.record("board-a", s.state)
        val restored = BatteryEstimateRegistry(registry.toJson().toString())
        val resumed = Stream(requireNotNull(restored.state("board-a")))
        resumed.tick(80, seconds = 0.0)
        assertEquals(s.state, resumed.state)
        repeat(20) { resumed.tick(80) }
        assertEquals(60, resumed.state.referencePercent)
    }

    @Test fun versionOneSocStateMigratesToTheUncalibratedCapacityPrior() {
        val legacy = JSONObject().put("version", 1)
            .put("percent", 58.2).put("reference_percent", 60)
        val migrated = requireNotNull(BatteryEstimateState.fromJson(legacy))
        assertEquals(58.2, migrated.percent, 0.0)
        assertEquals(346.0, migrated.capacity.estimatedWh, 0.0)
        assertEquals(BatteryCapacityStatus.PRIOR, migrated.capacity.status)
    }

    @Test fun boardsNeverShareAnEstimate() {
        val registry = BatteryEstimateRegistry()
        registry.record("board-a", BatteryEstimateState(58.2, 60))
        registry.record("board-b", BatteryEstimateState(22.1, 25))
        val restored = BatteryEstimateRegistry(registry.toJson().toString())
        assertEquals(58.2, restored.state("board-a")!!.percent, 0.0)
        assertEquals(22.1, restored.state("board-b")!!.percent, 0.0)
        assertNull(restored.state("board-c"))
    }

    @Test fun corruptOrUnknownStateIsIgnoredWithoutLosingOtherBoards() {
        val json = JSONObject().put("board-a", BatteryEstimateState(58.2, 60).toJson())
            .put("board-b", JSONObject().put("version", 1).put("percent", -1).put("reference_percent", 60))
            .put("board-c", JSONObject().put("version", 2).put("percent", 40).put("reference_percent", 40))
        val registry = BatteryEstimateRegistry(json.toString())
        assertNotNull(registry.state("board-a"))
        assertNull(registry.state("board-b"))
        assertNull(registry.state("board-c"))
        assertNull(BatteryEstimateRegistry("invalid").state("board-a"))
    }
}

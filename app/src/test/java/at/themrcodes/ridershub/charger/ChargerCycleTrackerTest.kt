package at.themrcodes.ridershub.charger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargerCycleTrackerTest {
    @Test fun `active load requires thirty seconds of fresh evidence`() {
        val tracker = ChargerCycleTracker()
        assertEquals(ChargerAction.NONE, tracker.observe(sample(0, 80.0)))
        assertEquals(ChargerAction.NONE, tracker.observe(sample(15_000, 80.0)))
        assertEquals(ChargerAction.ACTIVE_CONFIRMED, tracker.observe(sample(30_000, 80.0)))
        assertTrue(tracker.activeConfirmed)
    }

    @Test fun `no load is switched off after five observed minutes`() {
        val tracker = ChargerCycleTracker()
        var action = tracker.observe(sample(0, 2.0))
        for (second in 10..300 step 10) action = tracker.observe(sample(second * 1_000L, 2.0))
        assertEquals(ChargerAction.NO_LOAD_OFF, action)
        assertFalse(tracker.activeConfirmed)
    }

    @Test fun `gap also resets active-load confirmation`() {
        val tracker = ChargerCycleTracker()
        tracker.observe(sample(0, 80.0)); tracker.observe(sample(15_000, 80.0))
        assertEquals(ChargerAction.NONE, tracker.observe(sample(45_000, 80.0)))
        assertEquals(0, tracker.activeEvidenceMs)
        assertEquals(ChargerAction.NONE, tracker.observe(sample(60_000, 80.0)))
        assertEquals(ChargerAction.ACTIVE_CONFIRMED, tracker.observe(sample(75_000, 80.0)))
    }

    @Test fun `completion needs twenty continuous minutes below both limits`() {
        val tracker = ChargerCycleTracker()
        tracker.observe(sample(0, 100.0)); tracker.observe(sample(15_000, 100.0))
        tracker.observe(sample(30_000, 100.0))
        var action = ChargerAction.NONE
        for (second in 40..1_230 step 10) action = tracker.observe(sample(second * 1_000L, 4.0))
        assertEquals(ChargerAction.COMPLETE_OFF, action)
    }

    @Test fun `observation gap resets completion evidence`() {
        val tracker = ChargerCycleTracker()
        tracker.observe(sample(0, 100.0)); tracker.observe(sample(15_000, 100.0))
        tracker.observe(sample(30_000, 100.0)); tracker.observe(sample(40_000, 4.0))
        tracker.observe(sample(50_000, 4.0)); tracker.observe(sample(90_000, 4.0))
        assertEquals(0, tracker.completionEvidenceMs)
    }

    @Test fun `protection state requests immediate off`() {
        assertEquals(
            ChargerAction.FAULT_OFF,
            ChargerCycleTracker().observe(sample(0, 20.0).copy(overTemperature = true)),
        )
    }

    private fun sample(atMs: Long, watts: Double) = ChargerObservation(atMs, true, watts)
}

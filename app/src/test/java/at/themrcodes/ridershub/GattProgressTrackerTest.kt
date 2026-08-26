package at.themrcodes.ridershub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattProgressTrackerTest {
    @Test
    fun advancingStageInvalidatesOlderDeadline() {
        val tracker = GattProgressTracker()
        val connecting = tracker.enter(GattStage.CONNECTING)
        val discovering = tracker.enter(GattStage.DISCOVERING_SERVICES)

        assertFalse(tracker.isCurrent(connecting))
        assertTrue(tracker.isCurrent(discovering))
    }

    @Test
    fun reenteringSameStageInvalidatesPreviousAttempt() {
        val tracker = GattProgressTracker()
        val firstAttempt = tracker.enter(GattStage.CONNECTING)
        val retry = tracker.enter(GattStage.CONNECTING)

        assertFalse(tracker.isCurrent(firstAttempt))
        assertTrue(tracker.isCurrent(retry))
    }

    @Test
    fun onlyBlockingGattStagesHaveTimeouts() {
        assertTrue(GattStage.CONNECTING.timeoutMs!! > 0)
        assertTrue(GattStage.DISCOVERING_SERVICES.timeoutMs!! > 0)
        assertTrue(GattStage.ENABLING_NOTIFICATIONS.timeoutMs!! > 0)
        assertNull(GattStage.IDLE.timeoutMs)
        assertNull(GattStage.LISTENING.timeoutMs)
    }
}

package at.themrcodes.ridershub.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantCadenceTest {
    @Test
    fun runningUpdateIsDueEveryTenSeconds() {
        val lastUpdate = 10_000L

        assertFalse(
            isRunningUpdateDue(
                lastUpdate + 9_999L,
                lastUpdate,
                lastUpdate,
                batteryChanged = false,
            ),
        )
        assertTrue(
            isRunningUpdateDue(
                lastUpdate + 10_000L,
                lastUpdate,
                lastUpdate,
                batteryChanged = false,
            ),
        )
    }

    @Test
    fun batteryChangeBypassesTimedCadence() {
        assertTrue(isRunningUpdateDue(10_001L, 10_000L, 10_000L, batteryChanged = true))
    }

    @Test
    fun requestDurationCountsTowardTenSecondCadence() {
        val requestStartedAt = 10_000L
        val requestFinishedAt = requestStartedAt + 2_000L

        assertEquals(
            8_000L,
            remainingRunningUpdateDelayMs(
                nowElapsedMs = requestFinishedAt,
                lastQueuedAtElapsedMs = requestStartedAt,
                lastRequestStartedAtElapsedMs = requestStartedAt,
            ),
        )
    }

    @Test
    fun delayedRequestStartMovesNextPeriodicUpdate() {
        val queuedAt = 10_000L
        val actualRequestStart = 12_000L

        assertFalse(isRunningUpdateDue(21_999L, queuedAt, actualRequestStart, batteryChanged = false))
        assertTrue(isRunningUpdateDue(22_000L, queuedAt, actualRequestStart, batteryChanged = false))
    }
}

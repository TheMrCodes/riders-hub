package at.themrcodes.ridershub

import org.junit.Assert.assertEquals
import org.junit.Test

class BleTelemetryWatchdogTest {
    @Test
    fun watchdogWaitsForTheRemainingSilenceWindow() {
        assertEquals(
            12_000L,
            nextTelemetryWatchdogDelayMs(
                nowElapsedMs = 28_000L,
                lastTelemetryElapsedMs = 10_000L,
                timeoutMs = 30_000L,
            ),
        )
    }

    @Test
    fun watchdogExpiresAtTheSilenceDeadline() {
        assertEquals(
            0L,
            nextTelemetryWatchdogDelayMs(
                nowElapsedMs = 40_000L,
                lastTelemetryElapsedMs = 10_000L,
                timeoutMs = 30_000L,
            ),
        )
    }
}

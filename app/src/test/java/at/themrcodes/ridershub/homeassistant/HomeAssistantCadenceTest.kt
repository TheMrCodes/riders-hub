package at.themrcodes.ridershub.homeassistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantCadenceTest {
    @Test
    fun runningUpdateIsDueEveryTenSeconds() {
        val lastUpdate = 10_000L

        assertFalse(isRunningUpdateDue(lastUpdate + 9_999L, lastUpdate, batteryChanged = false))
        assertTrue(isRunningUpdateDue(lastUpdate + 10_000L, lastUpdate, batteryChanged = false))
    }

    @Test
    fun batteryChangeBypassesTimedCadence() {
        assertTrue(isRunningUpdateDue(10_001L, 10_000L, batteryChanged = true))
    }
}

package at.themrcodes.ridershub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardTelemetryValidityTest {
    @Test
    fun acceptsCrcValidBoardVoltageAndPercentage() {
        assertTrue(isPlausibleBoardTelemetry(85, 48.352, crcValid = true))
    }

    @Test
    fun rejectsRemoteOnlyHundredPercentZeroVoltFrame() {
        assertFalse(isPlausibleBoardTelemetry(100, 0.0, crcValid = true))
    }

    @Test
    fun rejectsInvalidCrcEvenWithPlausibleValues() {
        assertFalse(isPlausibleBoardTelemetry(20, 41.5, crcValid = false))
    }
}

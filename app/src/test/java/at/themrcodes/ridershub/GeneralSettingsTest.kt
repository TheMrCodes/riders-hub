package at.themrcodes.ridershub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralSettingsTest {
    @Test
    fun storedThresholdsAreClampedAndRoundedToFivePercentSteps() {
        assertEquals(MIN_LOW_BATTERY_WARNING_PERCENT, normalizeLowBatteryWarningPercent(-1))
        assertEquals(MAX_LOW_BATTERY_WARNING_PERCENT, normalizeLowBatteryWarningPercent(101))
        assertEquals(25, normalizeLowBatteryWarningPercent(27))
        assertEquals(30, normalizeLowBatteryWarningPercent(28))
        assertEquals(25, normalizeLowBatteryWarningPercent(25))
    }

    @Test
    fun configuredThresholdControlsWarningBoundary() {
        assertFalse(isLowBoardBattery(null, 25))
        assertFalse(isLowBoardBattery(26, 25))
        assertTrue(isLowBoardBattery(25, 25))
        assertTrue(isLowBoardBattery(10, 25))
    }

    @Test
    fun notificationAlsoRequiresAValidBoardVoltage() {
        assertTrue(shouldNotifyLowBoardBattery(20, 40.0, 20))
        assertFalse(shouldNotifyLowBoardBattery(21, 40.0, 20))
        assertFalse(shouldNotifyLowBoardBattery(20, 0.0, 20))
    }

    @Test
    fun rideEndReminderUsesATwoHourCooldown() {
        val now = 10 * LOW_BATTERY_RIDE_END_REMINDER_COOLDOWN_MS

        assertTrue(shouldNotifyLowBatteryRideEndReminder(null, now))
        assertFalse(
            shouldNotifyLowBatteryRideEndReminder(
                now - LOW_BATTERY_RIDE_END_REMINDER_COOLDOWN_MS + 1,
                now,
            ),
        )
        assertTrue(
            shouldNotifyLowBatteryRideEndReminder(
                now - LOW_BATTERY_RIDE_END_REMINDER_COOLDOWN_MS,
                now,
            ),
        )
        assertFalse(shouldNotifyLowBatteryRideEndReminder(now + 1, now))
    }
}

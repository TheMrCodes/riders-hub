package at.themrcodes.ridershub

import android.content.Context

data class GeneralSettingsSnapshot(
    val lowBatteryWarningPercent: Int,
)

class GeneralSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun snapshot(): GeneralSettingsSnapshot = GeneralSettingsSnapshot(
        lowBatteryWarningPercent = normalizeLowBatteryWarningPercent(
            preferences.getInt(KEY_LOW_BATTERY_WARNING_PERCENT, DEFAULT_LOW_BATTERY_WARNING_PERCENT),
        ),
    )

    fun setLowBatteryWarningPercent(percent: Int) {
        require(
            percent in MIN_LOW_BATTERY_WARNING_PERCENT..MAX_LOW_BATTERY_WARNING_PERCENT &&
                percent % LOW_BATTERY_WARNING_STEP_PERCENT == 0,
        )
        preferences.edit().putInt(KEY_LOW_BATTERY_WARNING_PERCENT, percent).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "general_settings"
        const val KEY_LOW_BATTERY_WARNING_PERCENT = "low_battery_warning_percent"
    }
}

internal fun normalizeLowBatteryWarningPercent(percent: Int): Int {
    val clamped = percent.coerceIn(
        MIN_LOW_BATTERY_WARNING_PERCENT,
        MAX_LOW_BATTERY_WARNING_PERCENT,
    )
    return ((clamped + LOW_BATTERY_WARNING_STEP_PERCENT / 2) /
        LOW_BATTERY_WARNING_STEP_PERCENT) * LOW_BATTERY_WARNING_STEP_PERCENT
}

internal fun isLowBoardBattery(batteryPercent: Int?, warningPercent: Int): Boolean =
    batteryPercent != null && batteryPercent <= warningPercent

internal fun shouldNotifyLowBoardBattery(
    batteryPercent: Int,
    packVoltageV: Double,
    warningPercent: Int,
): Boolean = isLowBoardBattery(batteryPercent, warningPercent) && packVoltageV > 10.0

const val DEFAULT_LOW_BATTERY_WARNING_PERCENT = 20
const val MIN_LOW_BATTERY_WARNING_PERCENT = 5
const val MAX_LOW_BATTERY_WARNING_PERCENT = 50
const val LOW_BATTERY_WARNING_STEP_PERCENT = 5

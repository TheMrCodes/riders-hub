package at.themrcodes.ridershub

import android.content.Context
import at.themrcodes.ridershub.session.RiderBoardProfile

data class GeneralSettingsSnapshot(
    val lowBatteryWarningPercent: Int,
    val riderBoardProfile: RiderBoardProfile = RiderBoardProfile.DEFAULT,
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
        riderBoardProfile = riderBoardProfile(),
    )

    /** Local only: app backup is disabled and the profile never enters logs, exports or the API. */
    fun riderBoardProfile(): RiderBoardProfile = RiderBoardProfile(
        boardModelId = preferences.getString(KEY_BOARD_MODEL_ID, null) ?: RiderBoardProfile.DEFAULT.boardModelId,
        riderMassKg = optionalDouble(KEY_RIDER_MASS_KG) ?: RiderBoardProfile.DEFAULT_RIDER_MASS_KG,
        boardMassKgOverride = optionalDouble(KEY_BOARD_MASS_KG),
        batteryWhOverride = optionalDouble(KEY_BATTERY_WH),
    ).normalized()

    fun setRiderBoardProfile(profile: RiderBoardProfile) {
        val normalized = profile.normalized()
        preferences.edit().apply {
            putString(KEY_BOARD_MODEL_ID, normalized.boardModelId)
            putFloat(KEY_RIDER_MASS_KG, normalized.riderMassKg.toFloat())
            putOptional(KEY_BOARD_MASS_KG, normalized.boardMassKgOverride)
            putOptional(KEY_BATTERY_WH, normalized.batteryWhOverride)
        }.apply()
    }

    private fun optionalDouble(key: String): Double? =
        if (preferences.contains(key)) preferences.getFloat(key, Float.NaN).toDouble().takeIf { it.isFinite() } else null

    private fun android.content.SharedPreferences.Editor.putOptional(key: String, value: Double?) {
        if (value == null) remove(key) else putFloat(key, value.toFloat())
    }

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
        const val KEY_BOARD_MODEL_ID = "board_model_id"
        const val KEY_RIDER_MASS_KG = "rider_mass_kg"
        const val KEY_BOARD_MASS_KG = "board_mass_kg_override"
        const val KEY_BATTERY_WH = "battery_wh_override"
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

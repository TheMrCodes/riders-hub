package at.themrcodes.ridershub.session

import kotlin.math.min

/** Command-based display estimate, not a battery-current measurement.
 * Positive watts mean draw; negative watts mean estimated battery-side recovery.
 * Display only: never feed this estimate into SOC, range or capacity learning.
 */
internal object ProvisionalPowerEstimator {
    // Preserve the existing provisional drive display scale. Raw input is a command, not amperes.
    const val DRIVE_WATTS_PER_VOLT_RAW = 0.020497265957721715
    // Rounded provisional motor-braking envelope from the force-model experiment.
    // Force is a motor property here, not multiplied by the rider's current mass.
    const val MAX_BRAKING_FORCE_N = 480.0
    const val BRAKING_POWER_KNEE_MPS = 6.0
    const val FULL_BRAKE_COMMAND_RAW = 670.0
    // Assumption, not a measured or fitted efficiency; includes unreturned braking work.
    const val RECOVERY_EFFICIENCY = 0.40

    fun estimate(voltageV: Double, effectiveLoadRaw: Double?, crcValid: Boolean, speedKmh: Double): Double? {
        if (!crcValid || !voltageV.isFinite() || voltageV !in 10.0..70.0 ||
            effectiveLoadRaw == null || !effectiveLoadRaw.isFinite() ||
            effectiveLoadRaw !in -32778.0..32778.0 ||
            !speedKmh.isFinite() || speedKmh !in 0.0..200.0
        ) return null
        if (effectiveLoadRaw >= 0.0) return voltageV * effectiveLoadRaw * DRIVE_WATTS_PER_VOLT_RAW
        val speedMps = speedKmh / 3.6
        if (speedMps == 0.0) return 0.0 // Holding a stopped motor produces no recovered energy.
        val brakeFraction = (-effectiveLoadRaw / FULL_BRAKE_COMMAND_RAW).coerceIn(0.0, 1.0)
        val mechanicalBrakingWatts = MAX_BRAKING_FORCE_N * brakeFraction * min(speedMps, BRAKING_POWER_KNEE_MPS)
        return -RECOVERY_EFFICIENCY * mechanicalBrakingWatts
    }
}

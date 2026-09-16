package at.themrcodes.ridershub.session

import kotlin.math.exp

internal data class BatteryPercentStep(
    val percent: Double,
    val consumptionPercent: Double = 0.0,
    val observationCorrectionPercent: Double = 0.0,
)

/** Integrate learned consumption; coarse remote observations gently constrain long-term drift. */
internal object BatteryPercentEstimator {
    @JvmOverloads
    fun observe(
        previousPercent: Double?,
        reportedPercent: Int,
        elapsedSeconds: Double,
        distanceKm: Double,
        batteryPercentPerKm: Double?,
        settledObservation: Boolean = false,
        reacquiringObservation: Boolean = false,
    ): Double = step(previousPercent, reportedPercent, elapsedSeconds, distanceKm,
        batteryPercentPerKm, settledObservation, reacquiringObservation).percent

    fun step(
        previousPercent: Double?,
        reportedPercent: Int,
        elapsedSeconds: Double,
        distanceKm: Double,
        batteryPercentPerKm: Double?,
        settledObservation: Boolean,
        reacquiringObservation: Boolean,
    ): BatteryPercentStep {
        require(reportedPercent in 0..100)
        val previous = previousPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
        // With no prior estimate, the first observation is our only initialization evidence.
        if (previous == null) return BatteryPercentStep(reportedPercent.toDouble())
        if (!elapsedSeconds.isFinite() || elapsedSeconds <= 0.0 || elapsedSeconds > 2.0) return BatteryPercentStep(previous)
        val distance = distanceKm.takeIf { it.isFinite() && it >= 0.0 } ?: return BatteryPercentStep(previous)
        val predictedStep = predict(previous, reportedPercent, distance, batteryPercentPerKm)
        val predicted = predictedStep.percent
        val observation = RemoteBatteryObservationModel.interval(reportedPercent)
        val lower = observation.lowerPercent
        val upper = observation.upperPercent
        // Corrections never clip a retained estimate to a new observation. Settled low-input
        // telemetry permits faster recovery after missed use or charging, not a voltage/SOC map.
        val error = predicted.coerceIn(lower, upper) - predicted
        val timeConstant = when {
            reacquiringObservation -> 1.0
            settledObservation -> 3.0
            else -> 5.0
        }
        val limit = (if (reacquiringObservation) 10.0 else 1.0) * elapsedSeconds
        val correction = (error * (1.0 - exp(-elapsedSeconds / timeConstant)))
            .coerceIn(-limit, limit)
        val result = (predicted + correction).coerceIn(0.0, 100.0)
        return BatteryPercentStep(result, predictedStep.consumptionPercent, result - predicted)
    }

    /**
     * Advances the physical state across validated odometer distance without treating the first
     * frame after a telemetry gap as elapsed observation time.
     */
    fun propagateInferredDrive(
        previousPercent: Double,
        reportedPercent: Int,
        transition: InferredDriveTransition,
    ): BatteryPercentStep = predict(
        previousPercent = previousPercent,
        reportedPercent = reportedPercent,
        distanceKm = transition.distanceKm,
        batteryPercentPerKm = transition.effectivePercentPerKm,
    )

    private fun predict(
        previousPercent: Double,
        reportedPercent: Int,
        distanceKm: Double,
        batteryPercentPerKm: Double?,
    ): BatteryPercentStep {
        val distance = distanceKm.takeIf { it.isFinite() && it >= 0.0 }
            ?: return BatteryPercentStep(previousPercent)
        val rate = batteryPercentPerKm.validBatteryRate()
        val lower = RemoteBatteryObservationModel.interval(reportedPercent).lowerPercent
        // Spend only the remaining interpolation allowance. A stale/incorrect consumption rate
        // cannot continue draining the estimate past the observed level indefinitely.
        // Exponential taper is independent of frame count for constant rate/reference.
        val available = (previousPercent - lower).coerceAtLeast(0.0)
        val use = distance * (rate ?: 0.0)
        val fullRateUse = (available - 1.0).coerceAtLeast(0.0).coerceAtMost(use)
        val taperedAvailable = available - fullRateUse
        val consumption = fullRateUse + taperedAvailable * (1.0 - exp(-(use - fullRateUse)))
        val predicted = (previousPercent - consumption).coerceIn(0.0, 100.0)
        return BatteryPercentStep(predicted, previousPercent - predicted)
    }
}

internal fun Double?.validBatteryRate(): Double? {
    val minimum = 1.0 / 1.5
    val maximum = 1.0 / 0.03
    // Converting percent/km -> percent/100 km -> percent/km can round just outside a bound.
    return this?.takeIf { it.isFinite() && it in (minimum - 1e-9)..(maximum + 1e-9) }
        ?.coerceIn(minimum, maximum)
}

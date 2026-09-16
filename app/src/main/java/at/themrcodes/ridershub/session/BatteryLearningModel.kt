package at.themrcodes.ridershub.session

import org.json.JSONObject

/** Durable, board-scoped correction of the existing speed-bucket model. No raw frames retained. */
internal data class BatteryLearningModel(
    val correctionFactor: Double = 1.0,
    val learnedPercentPerKm: Double? = null,
    val acceptedWindows: Int = 0,
    val lastPredictionErrorPercent: Double? = null,
) {
    val hasActiveFallback: Boolean
        get() = acceptedWindows >= MIN_FALLBACK_WINDOWS && learnedPercentPerKm.validBatteryRate() != null

    fun rate(basePercentPerKm: Double?): Double? = basePercentPerKm.validBatteryRate()
        ?.let { (it * correctionFactor).coerceIn(1.0 / 1.5, 1.0 / 0.03) }
        ?: learnedPercentPerKm.validBatteryRate()?.takeIf { hasActiveFallback }

    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("correction_factor", correctionFactor)
        .put("learned_percent_per_km", learnedPercentPerKm ?: JSONObject.NULL)
        .put("accepted_windows", acceptedWindows)
        .put("last_prediction_error_percent", lastPredictionErrorPercent ?: JSONObject.NULL)

    companion object {
        private const val MIN_FALLBACK_WINDOWS = 3

        fun fromJson(value: JSONObject): BatteryLearningModel {
            if (value.optInt("version") != 1) return BatteryLearningModel()
            return BatteryLearningModel(
                correctionFactor = value.optDouble("correction_factor", 1.0)
                    .takeIf { it.isFinite() && it in 0.25..4.0 } ?: 1.0,
                learnedPercentPerKm = value.optDouble("learned_percent_per_km").validBatteryRate(),
                acceptedWindows = value.optInt("accepted_windows", 0).coerceAtLeast(0),
                lastPredictionErrorPercent = value.optDouble("last_prediction_error_percent")
                    .takeIf { it.isFinite() && it in -100.0..100.0 },
            )
        }
    }
}

/**
 * Learn only between observed downward transitions, never from a partially observed first plateau.
 * The battery observer must first establish low input and stable voltage, then the transition must
 * remain reliable for another short confirmation interval. Any rise or gap abandons the unfinished interval.
 * Boundary counters are captured at the transition, not at the end of its confirmation delay.
 * This tracker is deliberately transient: process recreation loses coverage, not learned models.
 */
internal class BatteryLearningTracker {
    private data class Boundary(
        val percent: Int,
        val distanceKm: Double,
        val baseUse: Double,
        val predictedUse: Double,
        val distanceWithoutBase: Double,
        val distanceWithoutPrediction: Double,
    )
    private var previousReported: Int? = null
    private var distanceKm = 0.0
    private var baseUse = 0.0
    private var predictedUse = 0.0
    private var distanceWithoutBase = 0.0
    private var distanceWithoutPrediction = 0.0
    private var anchor: Boundary? = null
    private var pending: Boundary? = null
    private var stableSeconds = 0.0

    fun observe(
        model: BatteryLearningModel,
        reportedPercent: Int,
        elapsedSeconds: Double,
        intervalDistanceKm: Double,
        basePercentPerKm: Double?,
        reliableObservation: Boolean = true,
    ): BatteryLearningModel {
        require(reportedPercent in 0..100)
        if (previousReported == null || !elapsedSeconds.isFinite() ||
            elapsedSeconds <= 0.0 || elapsedSeconds > 2.0 ||
            !intervalDistanceKm.isFinite() || intervalDistanceKm < 0.0
        ) {
            reset(reportedPercent)
            return model
        }
        distanceKm += intervalDistanceKm
        val baseRate = basePercentPerKm.validBatteryRate()
        val predictedRate = model.rate(basePercentPerKm)
        baseUse += intervalDistanceKm * (baseRate ?: 0.0)
        predictedUse += intervalDistanceKm * (predictedRate ?: 0.0)
        if (baseRate == null) distanceWithoutBase += intervalDistanceKm
        if (predictedRate == null) distanceWithoutPrediction += intervalDistanceKm
        val previous = requireNotNull(previousReported)
        previousReported = reportedPercent
        if (reportedPercent > previous) {
            anchor = null
            pending = null
            stableSeconds = 0.0
            return model
        }
        if (reportedPercent < previous) {
            pending = Boundary(reportedPercent, distanceKm, baseUse, predictedUse,
                distanceWithoutBase, distanceWithoutPrediction)
            stableSeconds = 0.0
            return model
        }
        val end = pending ?: return model
        // A stable printed value under load is still a sagged observation candidate. Train only
        // after the observer has seen low controller input and stable voltage continuously.
        if (!reliableObservation) {
            stableSeconds = 0.0
            return model
        }
        stableSeconds += elapsedSeconds
        if (stableSeconds < RELIABLE_CONFIRMATION_SECONDS) return model
        pending = null
        val start = anchor
        if (start == null) {
            anchor = end
            return model
        }
        val depletion = (start.percent - end.percent).toDouble()
        if (depletion < 5.0) return model
        anchor = end
        val distance = end.distanceKm - start.distanceKm
        if (distance < 0.02) return model
        val observedRate = (depletion / distance).validBatteryRate() ?: return model
        val expectedBaseUse = end.baseUse - start.baseUse
        val ratio = (depletion / expectedBaseUse).takeIf {
            end.distanceWithoutBase == start.distanceWithoutBase &&
                expectedBaseUse > 0.0 && it.isFinite() && it in (0.25 - 1e-9)..(4.0 + 1e-9)
        }?.coerceIn(0.25, 2.0)
        // Gradual, bounded adaptation; recent accepted intervals have more influence than old ones.
        val gain = 0.05
        return model.copy(
            correctionFactor = ratio?.let { model.correctionFactor * (1.0 - gain) + it * gain }
                ?: model.correctionFactor,
            learnedPercentPerKm = model.learnedPercentPerKm?.let {
                it * (1.0 - gain) + observedRate * gain
            } ?: observedRate,
            acceptedWindows = (model.acceptedWindows.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            lastPredictionErrorPercent = if (end.distanceWithoutPrediction == start.distanceWithoutPrediction) {
                depletion - (end.predictedUse - start.predictedUse)
            } else null,
        )
    }

    private fun reset(reportedPercent: Int) {
        previousReported = reportedPercent
        distanceKm = 0.0
        baseUse = 0.0
        predictedUse = 0.0
        distanceWithoutBase = 0.0
        distanceWithoutPrediction = 0.0
        anchor = null
        pending = null
        stableSeconds = 0.0
    }

    companion object {
        private const val RELIABLE_CONFIRMATION_SECONDS = 3.0
    }
}

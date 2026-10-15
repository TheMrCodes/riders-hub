package at.themrcodes.ridershub.session

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

internal enum class BatteryCapacityStatus {
    PRIOR,
    LEARNING,
    TRACKING,
}

/**
 * Per-board usable-energy state. The initial 346 Wh is an uncertain prior, not a
 * claim that the installed pack is stock or that its current capacity was measured.
 */
internal data class BatteryCapacityEstimate(
    val estimatedWh: Double = BatteryCapacityEstimator.INITIAL_CAPACITY_WH,
    val observationCount: Int = 0,
    val qualifiedDepletionPercent: Double = 0.0,
) {
    init {
        require(estimatedWh.isFinite() && estimatedWh > 0.0)
        require(observationCount >= 0)
        require(qualifiedDepletionPercent.isFinite() && qualifiedDepletionPercent >= 0.0)
    }

    val status: BatteryCapacityStatus
        get() = when {
            observationCount == 0 -> BatteryCapacityStatus.PRIOR
            observationCount >= BatteryCapacityEstimator.TRACKING_OBSERVATIONS &&
                qualifiedDepletionPercent >= BatteryCapacityEstimator.TRACKING_DEPLETION_PERCENT ->
                BatteryCapacityStatus.TRACKING
            else -> BatteryCapacityStatus.LEARNING
        }

    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("estimated_wh", estimatedWh)
        .put("observation_count", observationCount)
        .put("qualified_depletion_percent", qualifiedDepletionPercent)

    companion object {
        fun fromJson(value: JSONObject): BatteryCapacityEstimate? = runCatching {
            if (value.optInt("version") != 1) return null
            BatteryCapacityEstimate(
                estimatedWh = value.getDouble("estimated_wh"),
                observationCount = value.getInt("observation_count"),
                qualifiedDepletionPercent = value.getDouble("qualified_depletion_percent"),
            )
        }.getOrNull()
    }
}

/**
 * Slow robust update contract for future qualified energy observations.
 *
 * No live caller should manufacture [observedCapacityWh] from the unverified raw
 * current field. Until an independently defensible observation source is wired,
 * states remain at the explicit 346 Wh PRIOR with zero observations.
 */
internal object BatteryCapacityEstimator {
    const val INITIAL_CAPACITY_WH = 346.0
    const val MIN_OBSERVATION_DEPLETION_PERCENT = 20.0
    const val TRACKING_OBSERVATIONS = 3
    const val TRACKING_DEPLETION_PERCENT = 100.0
    private const val FILTER_ALPHA = 0.02
    private const val LOG_INNOVATION_CLIP = 0.10
    private const val FULL_SUPPORT_DEPLETION_PERCENT = 50.0

    fun observe(
        current: BatteryCapacityEstimate,
        observedCapacityWh: Double,
        observedDepletionPercent: Double,
    ): BatteryCapacityEstimate {
        if (
            !observedCapacityWh.isFinite() || observedCapacityWh <= 0.0 ||
            !observedDepletionPercent.isFinite() ||
            observedDepletionPercent < MIN_OBSERVATION_DEPLETION_PERCENT
        ) return current

        val support = min(1.0, observedDepletionPercent / FULL_SUPPORT_DEPLETION_PERCENT)
        val innovation = ln(observedCapacityWh / current.estimatedWh)
            .coerceIn(-LOG_INNOVATION_CLIP, LOG_INNOVATION_CLIP)
        return BatteryCapacityEstimate(
            estimatedWh = current.estimatedWh * exp(FILTER_ALPHA * support * innovation),
            observationCount = current.observationCount + 1,
            qualifiedDepletionPercent =
                current.qualifiedDepletionPercent + observedDepletionPercent,
        )
    }
}

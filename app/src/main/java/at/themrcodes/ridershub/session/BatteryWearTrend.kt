package at.themrcodes.ridershub.session

import java.time.Duration

/** A relative range trend, not remaining lifespan or original cell-capacity measurement. */
data class BatteryWearTrend(
    val retentionPercent: Double,
    val changePercentPer1000Km: Double,
    val distanceSpanKm: Double,
    val baselineFullRangeKm: Double,
    val recentFullRangeKm: Double,
    val observationCount: Int,
)

internal object BatteryWearTrendEstimator {
    const val MIN_OBSERVATIONS = 6
    const val MIN_DAYS = 60L
    const val MIN_DISTANCE_KM = 500.0

    fun estimate(observations: List<BatteryCapacityObservation>): BatteryWearTrend? {
        val points = observations.filter {
            it.completed && it.odometerKm?.let { km -> km.isFinite() && km >= 0.0 } == true &&
                it.normalizedFullRangeKm.isFinite() && it.normalizedFullRangeKm > 0.0
        }.distinctBy { it.cycleId }.sortedBy { it.observedAt }
        if (points.size < MIN_OBSERVATIONS) return null
        if (points.zipWithNext().any { (a, b) -> b.odometerKm!! < a.odometerKm!! }) return null
        if (Duration.between(points.first().observedAt, points.last().observedAt).toDays() < MIN_DAYS) return null
        val first = points.take(3)
        val recent = points.takeLast(3)
        val distance = median(recent.map { it.odometerKm!! }) - median(first.map { it.odometerKm!! })
        if (distance < MIN_DISTANCE_KM) return null
        val baseline = median(first.map { it.normalizedFullRangeKm })
        val latest = median(recent.map { it.normalizedFullRangeKm })
        val retention = latest / baseline * 100.0
        return BatteryWearTrend(retention, (retention - 100.0) * 1000.0 / distance,
            distance, baseline, latest, points.size)
    }

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]
}

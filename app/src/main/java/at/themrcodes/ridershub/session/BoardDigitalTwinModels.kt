package at.themrcodes.ridershub.session

/** Interval-valued evidence from the remote's five-point battery display. */
internal data class BatteryObservationInterval(
    val reportedPercent: Int,
    val lowerPercent: Double,
    val upperPercent: Double,
) {
    init {
        require(reportedPercent in 0..100)
        require(lowerPercent.isFinite() && upperPercent.isFinite())
        require(lowerPercent in 0.0..100.0 && upperPercent in 0.0..100.0)
        require(lowerPercent <= reportedPercent && reportedPercent <= upperPercent)
    }
}

/** Observation model only; load, voltage and learned board behavior adjust likelihood elsewhere. */
internal object RemoteBatteryObservationModel {
    const val HALF_STEP_PERCENT = 2.5

    fun interval(reportedPercent: Int): BatteryObservationInterval {
        require(reportedPercent in 0..100)
        return BatteryObservationInterval(
            reportedPercent = reportedPercent,
            lowerPercent = (reportedPercent - HALF_STEP_PERCENT).coerceAtLeast(0.0),
            upperPercent = (reportedPercent + HALF_STEP_PERCENT).coerceAtMost(100.0),
        )
    }
}

/**
 * A driving transition whose distance is observed but whose speed mix was reconstructed.
 * The transition remains inferred even when every bucket has a usable consumption rate.
 */
internal data class InferredDriveTransition(
    val distanceKm: Double,
    val expectedBatteryUsePercent: Double,
    val effectivePercentPerKm: Double,
) {
    init {
        require(distanceKm.isFinite() && distanceKm > 0.0)
        require(expectedBatteryUsePercent.isFinite() && expectedBatteryUsePercent >= 0.0)
        require(effectivePercentPerKm.isFinite() && effectivePercentPerKm >= 0.0)
    }
}

internal object DigitalTwinDriveModel {
    /**
     * Converts an inferred speed profile into a state transition. Missing or unsupported bucket
     * rates fall back to the board-wide rate. Without that fallback, energy remains unresolved.
     */
    fun inferredTransition(
        inferredSpeedBucketDistancesKm: Map<Int, Double>,
        bucketPercentPer100Km: Map<Int, Double>,
        fallbackKmPerPercent: Double?,
    ): InferredDriveTransition? {
        val profile = inferredSpeedBucketDistancesKm.filter { (bucket, distance) ->
            bucket >= 0 && bucket % SPEED_BUCKET_WIDTH_KMH == 0 &&
                distance.isFinite() && distance > 0.0
        }
        val distance = profile.values.sum()
        if (!distance.isFinite() || distance <= 0.0) return null
        val fallbackRate = fallbackKmPerPercent
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { 1.0 / it }
            .validBatteryRate()
        var expectedUse = 0.0
        for ((bucket, bucketDistance) in profile) {
            val bucketRate = bucketPercentPer100Km[bucket]
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.div(100.0)
                .validBatteryRate()
            val rate = bucketRate ?: fallbackRate ?: return null
            expectedUse += bucketDistance * rate
        }
        return InferredDriveTransition(distance, expectedUse, expectedUse / distance)
    }
}

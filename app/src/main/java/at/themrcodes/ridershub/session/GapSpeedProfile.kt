package at.themrcodes.ridershub.session

import org.json.JSONObject

/** Allocates gaps from accumulated measured distances, with retained history as a fallback. */
internal object GapSpeedProfile {
    @JvmOverloads
    fun estimate(
        distanceKm: Double,
        localBoardId: String,
        newestFirstSessions: List<RideSummary>,
        speedBucketStatistics: BoardSpeedBucketStatistics? = null,
    ): Map<Int, Double> {
        if (!distanceKm.isFinite() || distanceKm <= 0.0) return emptyMap()
        return distribute(
            distanceKm,
            speedBucketStatistics?.takeIf { it.localBoardId == localBoardId }?.measuredProfile().orEmpty()
                .ifEmpty { RangeEstimator.observedCommonProfile(
                newestFirstSessions.filter { it.localBoardId == localBoardId }.distinctBy { it.id },
            ) },
        )
    }

    fun distribute(distanceKm: Double, measuredProfileKm: Map<Int, Double>): Map<Int, Double> {
        if (!distanceKm.isFinite() || distanceKm <= 0.0) return emptyMap()
        val usable = measuredProfileKm.filter { (bucket, km) ->
            bucket >= 0 && bucket % 5 == 0 && km.isFinite() && km > 0.0
        }
        val total = usable.values.sum()
        if (!total.isFinite() || total <= 0.0) return emptyMap()
        return usable.mapValues { (_, km) -> distanceKm * (km / total) }
    }
}

internal fun bucketMapJson(values: Map<Int, Double>) = JSONObject().apply {
    values.toSortedMap().forEach { (bucket, km) -> put(bucket.toString(), km) }
}

internal fun JSONObject.readBucketMap(key: String): Map<Int, Double> =
    optJSONObject(key)?.let { value ->
        buildMap {
            value.keys().forEach { key ->
                val bucket = key.toIntOrNull()
                val km = value.optDouble(key, Double.NaN)
                if (bucket != null && bucket >= 0 && bucket % 5 == 0 && km.isFinite() && km > 0.0) {
                    put(bucket, km)
                }
            }
        }
    } ?: emptyMap()

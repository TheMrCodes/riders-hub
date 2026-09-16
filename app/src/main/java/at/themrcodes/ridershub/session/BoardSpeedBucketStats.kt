package at.themrcodes.ridershub.session

import org.json.JSONObject

/** Cumulative observations, not a probability that a consumption estimate is correct. */
data class SpeedBucketStatistics(
    val startKmh: Int,
    val measuredDistanceKm: Double,
    val distanceSharePercent: Double,
    val inferredDistanceKm: Double,
    val calibrationMeasuredKm: Double,
    val calibrationInferredKm: Double,
    val contributingWindows: Long,
    val batteryPercentPer100Km: Double?,
    val fullBatteryRangeKm: Double?,
    val consumptionSharePercent: Double?,
    val modelLimitReached: Boolean,
) {
    // Measured distance is deliberately the simple confidence proxy, not a confidence percentage.
    val confidenceKm: Double get() = measuredDistanceKm
    // Presentation gate: measured kilometres remain the confidence proxy; inferred gaps do not count.
    val hasUsableConsumptionSupport: Boolean
        get() = confidenceKm >= 1.0 && calibrationMeasuredKm > 0.0 && contributingWindows > 0 &&
            !modelLimitReached && batteryPercentPer100Km?.let { it.isFinite() && it > 0.0 } == true
}

data class BoardSpeedBucketStatistics(
    val localBoardId: String,
    val buckets: List<SpeedBucketStatistics>,
    val unattributedDistanceKm: Double,
    val completedWindows: Long,
    val observedDepletionPercent: Double,
    val seededFromRetainedHistory: Boolean,
    val observedDistanceKm: Double = 0.0,
) {
    val measuredDistanceKm: Double get() = buckets.sumOf { it.measuredDistanceKm }
    val inferredDistanceKm: Double get() = buckets.sumOf { it.inferredDistanceKm }
    /** Distance evidence ledger; unattributed kilometres remain known without fabricated buckets. */
    val attributedDistanceKm: Double get() = measuredDistanceKm + inferredDistanceKm
    val accountedDistanceKm: Double get() = attributedDistanceKm + unattributedDistanceKm

    internal fun measuredProfile(): Map<Int, Double> = buckets.filter {
        it.startKmh >= 0 && it.startKmh % SPEED_BUCKET_WIDTH_KMH == 0 &&
            it.measuredDistanceKm.isFinite() && it.measuredDistanceKm > 0.0
    }.associate { it.startKmh to it.measuredDistanceKm }

    /** Only supported rates may influence the battery twin or remaining-range forecast. */
    internal fun consumptionRates(): Map<Int, Double> = buckets.filter {
        it.startKmh >= 0 && it.startKmh % SPEED_BUCKET_WIDTH_KMH == 0 &&
            it.hasUsableConsumptionSupport
    }.associate { it.startKmh to requireNotNull(it.batteryPercentPer100Km) / 100.0 }
}

/**
 * Board-scoped lifetime-since-collection aggregates. Window sufficient statistics retain the
 * weighted least-squares fit after source windows are trimmed, without retaining raw telemetry.
 * Shared by range and capacity analytics; recent-window fitting remains a legacy fallback.
 */
internal class BoardSpeedBucketStatsRegistry(encoded: String = "{}") {
    private class Totals {
        val measured = mutableMapOf<Int, Double>()
        val inferred = mutableMapOf<Int, Double>()
        val support = mutableMapOf<Int, Double>()
        val inferredSupport = mutableMapOf<Int, Double>()
        val counts = mutableMapOf<Int, Long>()
        val cross = mutableMapOf<Int, MutableMap<Int, Double>>()
        val target = mutableMapOf<Int, Double>()
        var unattributed = 0.0
        var distance = 0.0
        var depletion = 0.0
        var windows = 0L
        var seeded = false
        var lastWindowId: String? = null
    }
    private val boards = mutableMapOf<String, Totals>()

    init {
        val json = runCatching { JSONObject(encoded) }.getOrDefault(JSONObject())
        json.keys().forEach { board ->
            runCatching {
                val j = json.getJSONObject(board)
                require(j.getInt("schema_version") == 1)
                val t = Totals()
                fun read(key: String, into: MutableMap<Int, Double>) {
                    val map = j.getJSONObject(key)
                    map.keys().forEach { k ->
                        val bucket = k.toInt()
                        val value = map.getDouble(k)
                        require(validBucket(bucket) && value.isFinite() && value >= 0.0)
                        into[bucket] = value
                    }
                }
                read("measured_km", t.measured); read("inferred_km", t.inferred)
                read("calibration_measured_km", t.support); read("calibration_inferred_km", t.inferredSupport)
                read("fit_target", t.target)
                val cross = j.getJSONObject("fit_cross")
                cross.keys().forEach { key ->
                    val bucket = key.toInt()
                    require(validBucket(bucket))
                    val row = cross.getJSONObject(key)
                    t.cross[bucket] = mutableMapOf<Int, Double>().apply {
                        row.keys().forEach { column ->
                            val other = column.toInt(); val value = row.getDouble(column)
                            require(validBucket(other) && value.isFinite() && value >= 0.0)
                            put(other, value)
                        }
                    }
                }
                val counts = j.getJSONObject("contributing_windows")
                counts.keys().forEach { key ->
                    val bucket = key.toInt(); val count = counts.getLong(key)
                    require(validBucket(bucket) && count >= 0)
                    t.counts[bucket] = count
                }
                fun number(key: String) = j.getDouble(key).also { require(it.isFinite() && it >= 0) }
                // The durable key predates the clearer "unattributed" terminology.
                t.unattributed = number("unassigned_km"); t.distance = number("window_distance_km")
                t.depletion = number("observed_depletion_percent")
                t.windows = j.getLong("completed_windows").also { require(it >= 0) }
                t.seeded = j.getBoolean("seeded_from_retained_history")
                t.lastWindowId = if (j.isNull("last_window_id")) null else j.getString("last_window_id")
                boards[board] = t
            }
        }
    }

    /** Seed once, using only this board's retained records. Missing older history is not invented. */
    fun seed(board: String, rides: List<RideSummary>, windows: List<RangeDepletionWindow>) {
        if (boards.containsKey(board)) return
        val ownRides = rides.filter { it.localBoardId == board }.distinctBy { it.id }
        val ownWindows = windows.filter { it.localBoardId == board }.distinctBy { it.id }
        boards[board] = Totals().apply { seeded = ownRides.isNotEmpty() || ownWindows.isNotEmpty() }
        ownRides.forEach { recordDistance(board, it.speedBucketDistancesKm) }
        // Gap profiles are not in legacy ride summaries. Windows preserve the known allocation.
        ownWindows.forEach { recordDistance(board, inferred = it.estimatedSpeedBucketDistancesKm) }
        ownWindows.filter { it.closedAt != null }.sortedBy { it.closedAt }.forEach { recordWindow(board, it) }
    }

    fun recordDistance(
        board: String,
        measured: Map<Int, Double> = emptyMap(),
        inferred: Map<Int, Double> = emptyMap(),
        unattributedKm: Double = 0.0,
    ) {
        val t = boards.getOrPut(board) { Totals() }
        add(t.measured, measured); add(t.inferred, inferred)
        if (unattributedKm.isFinite() && unattributedKm > 0) t.unattributed += unattributedKm
    }

    /** Called once on each newly completed stream window, before history retention runs. */
    fun recordWindow(board: String, window: RangeDepletionWindow) {
        if (window.localBoardId != board || window.closedAt == null ||
            window.observedDepletionPercent < RangeCalibrationTracker.DEPLETION_STEP_PERCENT ||
            !window.recordedDistanceKm.isFinite() || window.recordedDistanceKm <= 0) return
        val t = boards.getOrPut(board) { Totals() }
        if (t.lastWindowId == window.id) return
        t.lastWindowId = window.id
        t.windows++
        t.distance += window.recordedDistanceKm
        t.depletion += window.observedDepletionPercent
        val measured = clean(window.speedBucketDistancesKm)
        val inferred = clean(window.estimatedSpeedBucketDistancesKm)
        val effective = measured.toMutableMap().apply { add(this, inferred) }
        val weight = (measured.values.sum() / window.recordedDistanceKm).coerceIn(0.0, 1.0)
        if (weight <= 0 || effective.values.sum() < window.recordedDistanceKm * 0.95) return
        add(t.support, measured); add(t.inferredSupport, inferred)
        effective.forEach { (b, x) ->
            t.counts[b] = (t.counts[b] ?: 0) + 1
            t.target[b] = (t.target[b] ?: 0.0) + weight * x * window.observedDepletionPercent
            val row = t.cross.getOrPut(b) { mutableMapOf() }
            effective.forEach { (c, y) -> row[c] = (row[c] ?: 0.0) + weight * x * y }
        }
    }

    private fun rates(t: Totals): Map<Int, Double> {
        if (t.distance < 1 || t.depletion < 5 || t.distance / t.depletion !in 0.01..1.5) return emptyMap()
        val prior = t.depletion / t.distance
        val keys = t.target.keys.sorted()
        if (keys.size == 1) return mapOf(keys.single() to prior)
        val result = keys.associateWith { prior }.toMutableMap()
        repeat(40) {
            keys.forEach { b ->
                val row = t.cross[b].orEmpty()
                val other = keys.sumOf { c -> if (c == b) 0.0 else (row[c] ?: 0.0) * result.getValue(c) }
                result[b] = ((prior + t.target.getValue(b) - other) / (1.0 + (row[b] ?: 0.0)))
                    .coerceIn(1.0 / 1.5, 1.0 / 0.01)
            }
        }
        return result
    }

    fun snapshot(board: String): BoardSpeedBucketStatistics? {
        val t = boards[board] ?: return null
        val rates = rates(t)
        val totalDistance = t.measured.toSortedMap().values.sum()
        // Shares use measured riding only, conditional on buckets with fitted consumption.
        val modeledUse = rates.entries.sumOf { (b, rate) -> rate * (t.measured[b] ?: 0.0) }
        val keys = (t.measured.keys + t.inferred.keys + rates.keys).sorted()
        return BoardSpeedBucketStatistics(board, keys.map { b ->
            val km = t.measured[b] ?: 0.0
            val rate = rates[b]
            SpeedBucketStatistics(b, km, if (totalDistance > 0) 100 * km / totalDistance else 0.0,
                t.inferred[b] ?: 0.0, t.support[b] ?: 0.0, t.inferredSupport[b] ?: 0.0,
                t.counts[b] ?: 0L, rate?.times(100), rate?.let { 100 / it },
                rate?.takeIf { modeledUse > 0 }?.let { 100 * it * km / modeledUse },
                rate != null && (rate <= 1.0 / 1.5 + 1e-9 || rate >= 100.0 - 1e-9))
        }, t.unattributed, t.windows, t.depletion, t.seeded, t.distance)
    }

    fun toJson() = JSONObject().apply {
        boards.forEach { (board, t) ->
            put(board, JSONObject().put("schema_version", 1)
                .put("measured_km", bucketJson(t.measured)).put("inferred_km", bucketJson(t.inferred))
                .put("calibration_measured_km", bucketJson(t.support))
                .put("calibration_inferred_km", bucketJson(t.inferredSupport))
                .put("contributing_windows", bucketJson(t.counts))
                .put("fit_target", bucketJson(t.target))
                .put("fit_cross", JSONObject().apply { t.cross.forEach { (b, row) -> put(b.toString(), bucketJson(row)) } })
                .put("unassigned_km", t.unattributed).put("window_distance_km", t.distance)
                .put("observed_depletion_percent", t.depletion).put("completed_windows", t.windows)
                .put("seeded_from_retained_history", t.seeded)
                .put("last_window_id", t.lastWindowId ?: JSONObject.NULL))
        }
    }

    companion object {
        private fun bucketJson(values: Map<Int, *>) = JSONObject().apply {
            values.toSortedMap().forEach { (bucket, value) -> put(bucket.toString(), value) }
        }
        private fun validBucket(b: Int) = b >= 0 && b % SPEED_BUCKET_WIDTH_KMH == 0
        private fun clean(values: Map<Int, Double>) = values.filter { (b, v) -> validBucket(b) && v.isFinite() && v > 0 }
        private fun add(target: MutableMap<Int, Double>, source: Map<Int, Double>) {
            clean(source).forEach { (b, v) -> target[b] = (target[b] ?: 0.0) + v }
        }
    }
}

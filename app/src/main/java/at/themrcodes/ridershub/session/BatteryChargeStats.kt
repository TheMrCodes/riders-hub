package at.themrcodes.ridershub.session

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

/** Reported scale diagnostics; an observed maximum is never automatically mapped to 100% SOC. */
data class BatteryChargeStats(
    val highestReportedPercent: Int? = null,
    val highestSettledPercent: Int? = null,
    val highestObservedAt: String? = null,
    val highestObservedOdometerKm: Double? = null,
    val monthlyMaxima: Map<String, Int> = emptyMap(),
    val candidatePercent: Int? = null,
    val candidateSeconds: Double = 0.0,
    val lastAtMs: Long? = null,
) {
    fun observe(percent: Int, atMs: Long, odometerKm: Double): BatteryChargeStats {
        require(percent in 0..100 && odometerKm.isFinite() && odometerKm >= 0.0)
        val elapsed = lastAtMs?.let { (atMs - it) / 1000.0 }
        val seconds = if (candidatePercent == percent && elapsed != null && elapsed in 0.0..2.0) {
            (candidateSeconds + elapsed).coerceAtMost(30.0)
        } else 0.0
        val newPeak = highestReportedPercent == null || percent > highestReportedPercent
        val month = Instant.ofEpochMilli(atMs).atZone(ZoneOffset.UTC).toLocalDate().toString().take(7)
        return copy(
            highestReportedPercent = maxOf(highestReportedPercent ?: percent, percent),
            highestSettledPercent = if (seconds >= 30.0) maxOf(highestSettledPercent ?: percent, percent)
                else highestSettledPercent,
            highestObservedAt = if (newPeak) Instant.ofEpochMilli(atMs).toString() else highestObservedAt,
            highestObservedOdometerKm = if (newPeak) odometerKm else highestObservedOdometerKm,
            monthlyMaxima = if ((monthlyMaxima[month] ?: -1) >= percent) monthlyMaxima else
                (monthlyMaxima + (month to percent)).toSortedMap().entries.toList()
                    .takeLast(240).associate { it.toPair() },
            candidatePercent = percent, candidateSeconds = seconds, lastAtMs = atMs,
        )
    }

    fun interrupted() = copy(candidatePercent = null, candidateSeconds = 0.0, lastAtMs = null)

    fun toJson() = JSONObject()
        .put("highest_reported_percent", highestReportedPercent ?: JSONObject.NULL)
        .put("highest_settled_percent", highestSettledPercent ?: JSONObject.NULL)
        .put("highest_observed_at", highestObservedAt ?: JSONObject.NULL)
        .put("highest_observed_odometer_km", highestObservedOdometerKm ?: JSONObject.NULL)
        .put("monthly_maxima", JSONObject(monthlyMaxima))
        .put("candidate_percent", candidatePercent ?: JSONObject.NULL)
        .put("candidate_seconds", candidateSeconds)
        .put("last_at_ms", lastAtMs ?: JSONObject.NULL)

    companion object {
        fun fromJson(value: JSONObject): BatteryChargeStats {
            fun percent(key: String) = value.takeUnless { it.isNull(key) }?.getInt(key)?.also { require(it in 0..100) }
            val months = value.optJSONObject("monthly_maxima")
            val result = BatteryChargeStats(
                percent("highest_reported_percent"), percent("highest_settled_percent"),
                value.takeUnless { it.isNull("highest_observed_at") }?.getString("highest_observed_at"),
                value.takeUnless { it.isNull("highest_observed_odometer_km") }?.getDouble("highest_observed_odometer_km"),
                buildMap {
                    months?.keys()?.forEach { key ->
                        val p = months.optInt(key, -1)
                        if (Regex("\\d{4}-\\d{2}").matches(key) && p in 0..100) put(key, p)
                    }
                },
                percent("candidate_percent"), value.optDouble("candidate_seconds", 0.0),
                value.takeUnless { it.isNull("last_at_ms") }?.getLong("last_at_ms"),
            )
            require(result.candidateSeconds.isFinite() && result.candidateSeconds in 0.0..30.0)
            require(result.highestObservedOdometerKm?.let { it.isFinite() && it >= 0.0 } != false)
            return result
        }
    }
}

internal class BatteryChargeStatsRegistry(encoded: String = "{}") {
    private val values = mutableMapOf<String, BatteryChargeStats>()
    init {
        val json = runCatching { JSONObject(encoded) }.getOrDefault(JSONObject())
        json.keys().forEach { board ->
            runCatching { BatteryChargeStats.fromJson(json.getJSONObject(board)) }
                .getOrNull()?.let { values[board] = it }
        }
    }
    fun state(board: String): BatteryChargeStats? = values[board]
    fun seedMaximum(board: String, percent: Int) {
        if (percent !in 0..100) return
        val current = values[board] ?: BatteryChargeStats()
        if (percent > (current.highestReportedPercent ?: -1)) {
            // Legacy summaries preserve the maximum, not its exact occurrence or settling time.
            values[board] = current.copy(highestReportedPercent = percent,
                highestObservedAt = null, highestObservedOdometerKm = null)
        }
    }
    fun observe(board: String, percent: Int, atMs: Long, odometerKm: Double): BatteryChargeStats =
        (values[board] ?: BatteryChargeStats()).observe(percent, atMs, odometerKm).also { values[board] = it }
    fun interrupted(board: String) { values[board]?.let { values[board] = it.interrupted() } }
    fun toJson() = JSONObject().apply { values.forEach { (board, stats) -> put(board, stats.toJson()) } }
}

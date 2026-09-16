package at.themrcodes.ridershub.session

import org.json.JSONObject
import kotlin.math.max

/** Per-board evidence, independent of connection segments and UI ride boundaries. */
internal data class BoardContinuityState(
    val atMs: Long,
    val odometerKm: Double,
    val speedKmh: Double,
    val baselinePercent: Int,
    val chargeCandidateSeconds: Double = 0.0,
    val interrupted: Boolean = false,
    val reboundSeconds: Double = 0.0,
    val reboundPercent: Int? = null,
    val lastReportedPercent: Int? = null,
) {
    fun toJson() = JSONObject().put("at_ms", atMs).put("odometer_km", odometerKm)
        .put("speed_kmh", speedKmh).put("baseline_percent", baselinePercent)
        .put("charge_candidate_seconds", chargeCandidateSeconds).put("interrupted", interrupted).put("rebound_seconds", reboundSeconds)
        .put("reference_candidate_percent", reboundPercent ?: JSONObject.NULL)
        .put("last_reported_percent", lastReportedPercent ?: JSONObject.NULL)

    companion object {
        fun fromJson(value: JSONObject): BoardContinuityState {
            val result = BoardContinuityState(
                value.getLong("at_ms"), value.getDouble("odometer_km"),
                value.getDouble("speed_kmh"), value.getInt("baseline_percent"),
                value.optDouble("charge_candidate_seconds", 0.0),
                value.optBoolean("interrupted", false),
                value.optDouble("rebound_seconds", 0.0),
                value.takeUnless { it.isNull("reference_candidate_percent") }?.getInt("reference_candidate_percent"),
                value.takeUnless { it.isNull("last_reported_percent") }?.getInt("last_reported_percent"),
            )
            require(result.odometerKm.isFinite() && result.odometerKm >= 0)
            require(result.speedKmh.isFinite() && result.speedKmh >= 0)
            require(result.baselinePercent in 0..100)
            require(result.chargeCandidateSeconds.isFinite() && result.chargeCandidateSeconds >= 0)
            require(result.reboundSeconds.isFinite() && result.reboundSeconds >= 0)
            require(result.reboundPercent == null || result.reboundPercent in 0..100)
            require(result.lastReportedPercent == null || result.lastReportedPercent in 0..100)
            return result
        }
    }
}

internal data class BoardContinuityUpdate(
    val state: BoardContinuityState,
    val gapReason: String?,
    val odometerDeltaKm: Double?,
    val intervalDistanceKm: Double,
    val intervalSpeedKmh: Double?,
    val rechargeIncreasePercent: Int?,
    val chargePending: Boolean,
    val calibrationGapReason: String?,
    val batteryDeltaPercent: Int?,
) {
    val unprofiledDistanceKm: Double
        get() = if (gapReason != null && calibrationGapReason == null &&
            !chargePending && rechargeIncreasePercent == null
        ) odometerDeltaKm ?: 0.0 else 0.0

    fun toJson() = JSONObject().put("schema_version", 2)
        .put("gap_reason", gapReason ?: JSONObject.NULL)
        .put("calibration_gap_reason", calibrationGapReason ?: JSONObject.NULL)
        .put("battery_delta_percent", batteryDeltaPercent ?: JSONObject.NULL)
        .put("odometer_delta_km", odometerDeltaKm ?: JSONObject.NULL)
        .put("profiled_interval_km", intervalDistanceKm)
        .put("unprofiled_distance_km", unprofiledDistanceKm)
        .put("recharge_increase_percent", rechargeIncreasePercent ?: JSONObject.NULL)
        .put("charge_pending", chargePending)
        .put("charge_candidate_seconds", state.chargeCandidateSeconds)
}

/**
 * Odometer is the total-distance evidence. Speed integration supplies the finer 5 km/h profile;
 * a missing interval is never filled with the arrival speed. Charge inference needs a sustained
 * >=10 point rise, wherever it occurs in the telemetry stream.
 */
internal object BoardContinuityTracker {
    const val CHARGE_CONFIRMATION_SECONDS = 30.0
    const val ODOMETER_GAP_KM = 2.0

    fun observe(
        previous: BoardContinuityState?,
        atMs: Long,
        percent: Int,
        odometerKm: Double,
        speedKmh: Double,
    ): BoardContinuityUpdate {
        require(percent in 0..100 && odometerKm.isFinite() && odometerKm >= 0)
        require(speedKmh.isFinite() && speedKmh >= 0)
        val elapsed = previous?.let { (atMs - it.atMs) / 1000.0 }
        val delta = previous?.let { odometerKm - it.odometerKm }
        val gap = when {
            previous == null -> "first_observation"
            // Delivery order is authoritative. Multiple decoded frames can share the same
            // millisecond; keep their observations and add zero integration/confirmation time.
            elapsed == null || elapsed < 0 -> "clock_discontinuity"
            delta != null && delta < -0.001 -> "odometer_reset"
            previous.interrupted -> "invalid_telemetry"
            elapsed > 2.0 -> "missing_frames"
            // Counter has 0.1 km granularity. Allow one tick plus a generous speed bound.
            delta != null && delta > 0.11 + 100.0 * elapsed / 3600.0 -> "odometer_jump"
            else -> null
        }
        val batteryDelta = previous?.lastReportedPercent?.let { percent - it }
        // Timing interruptions alone are not consumption boundaries. Small counter changes
        // remain in the same window, without inventing their missing speed profile.
        val calibrationGap = when {
            gap == null -> null
            gap !in setOf("missing_frames", "invalid_telemetry", "odometer_jump") -> gap
            delta != null && delta + 1e-9 >= ODOMETER_GAP_KM -> "unobserved_distance"
            batteryDelta == null -> "unknown_prior_battery"
            kotlin.math.abs(batteryDelta) >= ChargeCycleTracker.MIN_INFERRED_RECHARGE_PERCENT -> "unobserved_battery_change"
            else -> null
        }
        val averageSpeed = if (gap == null) (requireNotNull(previous).speedKmh + speedKmh) / 2 else null
        val distance = averageSpeed?.let { it * requireNotNull(elapsed) / 3600.0 } ?: 0.0
        // A reset can indicate another battery/counter: do not infer charging across it.
        val baseline = if (previous == null || gap == "odometer_reset" || gap == "clock_discontinuity") {
            percent
        } else previous.baselinePercent
        val rise = percent - baseline
        val pending = rise >= ChargeCycleTracker.MIN_INFERRED_RECHARGE_PERCENT
        val candidateSeconds = if (pending && gap == null) {
            (previous?.chargeCandidateSeconds ?: 0.0) + (elapsed ?: 0.0)
        } else 0.0
        // Both decreases and small rebounds must settle before becoming a charge reference.
        // Otherwise a brief load-induced low reading can turn its recovery into a false charge.
        val referencePending = percent != baseline && rise < ChargeCycleTracker.MIN_INFERRED_RECHARGE_PERCENT
        val reboundSeconds = if (referencePending && gap == null) {
            (previous?.reboundSeconds?.takeIf { previous.reboundPercent == percent } ?: 0.0) +
                (elapsed ?: 0.0)
        } else 0.0
        val charge = rise.takeIf { candidateSeconds >= CHARGE_CONFIRMATION_SECONDS }
        return BoardContinuityUpdate(
            BoardContinuityState(atMs, odometerKm, speedKmh, if (charge != null || reboundSeconds >= CHARGE_CONFIRMATION_SECONDS) percent else baseline,
                if (charge != null) 0.0 else candidateSeconds, false,
                if (reboundSeconds >= CHARGE_CONFIRMATION_SECONDS) 0.0 else reboundSeconds,
                percent.takeIf { referencePending && reboundSeconds < CHARGE_CONFIRMATION_SECONDS }, percent),
            gap, delta?.takeIf { it >= 0 }, max(0.0, distance), averageSpeed,
            charge, pending && charge == null, calibrationGap, batteryDelta,
        )
    }
}

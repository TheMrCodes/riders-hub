package at.themrcodes.ridershub.session

import org.json.JSONObject
import java.util.Locale

internal data class BatteryObservationResult(
    val state: BatteryEstimateState,
    val diagnostics: BatteryObservationDiagnostics,
)

internal data class BatteryObservationDiagnostics(
    val status: String,
    val previousPercent: Double?,
    val previousReferencePercent: Int?,
    val referencePercent: Int,
    val reportedPercent: Int,
    val candidatePercent: Int?,
    val candidateSeconds: Double,
    val settledSeconds: Double,
    val settledVoltageV: Double?,
    val confirmed: Boolean,
    val settled: Boolean,
    val reacquiring: Boolean,
    val reacquiringAfter: Boolean,
    val consumptionPercent: Double,
    val correctionPercent: Double,
) {
    fun toJson(): JSONObject {
        val interval = RemoteBatteryObservationModel.interval(reportedPercent)
        return JSONObject()
        .put("status", status)
        .put("previous_percent", previousPercent ?: JSONObject.NULL)
        .put("previous_reference_percent", previousReferencePercent ?: JSONObject.NULL)
        .put("reference_percent", referencePercent)
        .put("reported_percent", reportedPercent)
        .put("observation_lower_percent", interval.lowerPercent)
        .put("observation_upper_percent", interval.upperPercent)
        .put("candidate_percent", candidatePercent ?: JSONObject.NULL)
        .put("candidate_seconds", candidateSeconds)
        .put("settled_seconds", settledSeconds)
        .put("settled_voltage_v", settledVoltageV ?: JSONObject.NULL)
        .put("observation_confirmed", confirmed)
        .put("settled_observation_used", settled)
        .put("reacquiring_used", reacquiring)
        .put("reacquiring_after", reacquiringAfter)
        .put("consumption_percent", consumptionPercent)
        .put("observation_correction_percent", correctionPercent)
    }
}

/** Immutable result from processing one frame, never a later read of mutable UI/session state. */
internal data class BatteryFrameDiagnostics(
    val status: String,
    val estimatedPercent: Double? = null,
    val observation: BatteryObservationDiagnostics? = null,
    val elapsedSeconds: Double? = null,
    val intervalDistanceKm: Double? = null,
    val speedBucketKmh: Int? = null,
    val basePercentPerKm: Double? = null,
    val appliedPercentPerKm: Double? = null,
    val learningBefore: BatteryLearningModel? = null,
    val learningAfter: BatteryLearningModel? = null,
    val continuity: BoardContinuityUpdate? = null,
    val estimatedGapSpeedBuckets: Map<Int, Double> = emptyMap(),
    val inferredGapDistanceKm: Double = 0.0,
    val inferredGapBasePercentPerKm: Double? = null,
    val inferredGapAppliedPercentPerKm: Double? = null,
    val inferredGapConsumptionPercent: Double = 0.0,
    val highestReportedBatteryPercent: Int? = null,
    val highestSettledBatteryPercent: Int? = null,
    val estimatedCapacityWh: Double? = null,
    val capacityStatus: BatteryCapacityStatus? = null,
    val capacityObservationCount: Int? = null,
    val capacityQualifiedDepletionPercent: Double? = null,
    val loadRaw: Int? = null,
    val loadEffectiveRaw: Double? = null,
    val loadZeroRaw: Double? = null,
    val loadDeadbandRaw: Double? = null,
    val loadZeroSampleCount: Int? = null,
    val loadZeroCalibrated: Boolean? = null,
    val bucketModelSource: String? = null,
    val bucketEvidence: SpeedBucketStatistics? = null,
    val bucketRollingPercentPerKm: Double? = null,
    val bucketAerodynamicPercentPerKmPerKmhSquared: Double? = null,
    val bucketUnconstrainedRollingCoefficient: Double? = null,
    val bucketUnconstrainedAerodynamicCoefficientPerKmhSquared: Double? = null,
    val bucketRollingCoefficientStandardError: Double? = null,
    val bucketAerodynamicCoefficientStandardErrorPerKmhSquared: Double? = null,
    val bucketShapeReferenceSpeedKmh: Double? = null,
    val bucketAerodynamicShareAtReference: Double? = null,
    val bucketShapeConditionNumber: Double? = null,
    val bucketShapeRSquared: Double? = null,
    val bucketShapeEvidenceKm: Double? = null,
    val bucketShapeSupportedBuckets: Int? = null,
) {
    fun toJson(): JSONObject = (observation?.toJson() ?: JSONObject())
        .put("schema_version", 2)
        .put("algorithm", "persistent-observer-v2")
        .put("status", status)
        .put("estimated_percent", estimatedPercent ?: JSONObject.NULL)
        .put("elapsed_seconds", elapsedSeconds?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("interval_distance_km", intervalDistanceKm?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("speed_bucket_kmh", speedBucketKmh ?: JSONObject.NULL)
        .put("base_percent_per_km", basePercentPerKm?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("applied_percent_per_km", appliedPercentPerKm?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("learning_before", learningBefore?.toJson() ?: JSONObject.NULL)
        .put("learning_after", learningAfter?.toJson() ?: JSONObject.NULL)
        .put("continuity", continuity?.toJson() ?: JSONObject.NULL)
        .put("estimated_gap_speed_bucket_distances_km", bucketMapJson(estimatedGapSpeedBuckets))
        .put("inferred_gap_distance_km", inferredGapDistanceKm.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("inferred_gap_base_percent_per_km", inferredGapBasePercentPerKm?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("inferred_gap_applied_percent_per_km", inferredGapAppliedPercentPerKm?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("inferred_gap_consumption_percent", inferredGapConsumptionPercent.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("highest_reported_battery_percent", highestReportedBatteryPercent ?: JSONObject.NULL)
        .put("highest_settled_battery_percent", highestSettledBatteryPercent ?: JSONObject.NULL)
        .put("estimated_capacity_wh",
            estimatedCapacityWh?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("capacity_status", capacityStatus?.name?.lowercase(Locale.ROOT) ?: JSONObject.NULL)
        .put("capacity_observation_count", capacityObservationCount ?: JSONObject.NULL)
        .put("capacity_qualified_depletion_percent",
            capacityQualifiedDepletionPercent?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("load_raw", loadRaw ?: JSONObject.NULL)
        .put("load_effective_raw",
            loadEffectiveRaw?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("load_zero_raw", loadZeroRaw?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("load_deadband_raw", loadDeadbandRaw?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("load_zero_sample_count", loadZeroSampleCount ?: JSONObject.NULL)
        .put("load_zero_calibrated", loadZeroCalibrated ?: JSONObject.NULL)
        .put("bucket_model_source", bucketModelSource ?: JSONObject.NULL)
        .put("bucket_quadratic_rolling_percent_per_km",
            bucketRollingPercentPerKm?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("bucket_quadratic_aerodynamic_percent_per_km_per_kmh_squared",
            bucketAerodynamicPercentPerKmPerKmhSquared?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("bucket_quadratic_unconstrained_rolling",
            bucketUnconstrainedRollingCoefficient?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("bucket_quadratic_unconstrained_aerodynamic_per_kmh_squared",
            bucketUnconstrainedAerodynamicCoefficientPerKmhSquared?.takeIf { it.isFinite() }
                ?: JSONObject.NULL)
        .put("bucket_quadratic_rolling_standard_error",
            bucketRollingCoefficientStandardError?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("bucket_quadratic_aerodynamic_standard_error_per_kmh_squared",
            bucketAerodynamicCoefficientStandardErrorPerKmhSquared?.takeIf { it.isFinite() }
                ?: JSONObject.NULL)
        .put("bucket_quadratic_reference_speed_kmh",
            bucketShapeReferenceSpeedKmh?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("bucket_quadratic_aerodynamic_share_at_reference",
            bucketAerodynamicShareAtReference?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("bucket_quadratic_condition_number",
            bucketShapeConditionNumber?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("bucket_quadratic_r_squared",
            bucketShapeRSquared?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("bucket_quadratic_evidence_km",
            bucketShapeEvidenceKm?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("bucket_quadratic_supported_buckets", bucketShapeSupportedBuckets ?: JSONObject.NULL)
        .put("bucket_model_evidence", bucketEvidence?.let {
            JSONObject().put("speed_bucket_kmh", it.startKmh)
                .put("confidence_km", it.confidenceKm)
                .put("inferred_distance_km", it.inferredDistanceKm)
                .put("calibration_measured_km", it.calibrationMeasuredKm)
                .put("calibration_inferred_km", it.calibrationInferredKm)
                .put("contributing_windows", it.contributingWindows)
                .put("model_limit_reached", it.modelLimitReached)
        } ?: JSONObject.NULL)
}

package at.themrcodes.ridershub.session

data class RideSummary(
    val id: String,
    val startedAt: String,
    val endedAt: String?,
    val lastFrameAt: String?,
    val distanceKm: Double,
    val movingSeconds: Double,
    val maxSpeedKmh: Double,
    val boardBatteryStart: Int?,
    val boardBatteryEnd: Int?,
    val boardBatteryMin: Int?,
    val packVoltageStart: Double?,
    val packVoltageEnd: Double?,
    val packVoltageMin: Double?,
    val packVoltageMax: Double?,
    val restingVoltageStart: Double?,
    val restingVoltageEnd: Double?,
    val odometerStartKm: Double?,
    val odometerEndKm: Double?,
    val frameCount: Long,
    val boardFrameCount: Long,
    val crcErrorCount: Long,
    val segmentCount: Int,
    val modes: Set<String>,
    val logFile: String,
    val active: Boolean,
) {
    val isTrack: Boolean
        get() = distanceKm >= 0.02 || movingSeconds >= 10.0
}

data class CalibrationPoint(
    val wallTime: String,
    val batteryPercent: Int,
    val restingVoltageV: Double,
)

data class RideSegment(
    val sessionId: String,
    val deviceAddress: String,
    val deviceName: String?,
    val logFile: String,
    val initialSequence: Long,
    val segmentNumber: Int,
    val newSession: Boolean,
)

data class RideStoreSnapshot(
    val activeRide: RideSummary?,
    val recentTracks: List<RideSummary>,
    val rangeEstimate: RangeEstimate,
    val reconnectGraceEndsAt: String?,
)

object SessionContinuity {
    fun shouldResume(
        activeDeviceAddress: String?,
        arrivingDeviceAddress: String,
        lastActivityEpochMs: Long?,
        nowEpochMs: Long,
        graceMs: Long,
    ): Boolean = activeDeviceAddress?.equals(arrivingDeviceAddress, ignoreCase = true) == true &&
        lastActivityEpochMs != null &&
        nowEpochMs >= lastActivityEpochMs &&
        nowEpochMs - lastActivityEpochMs <= graceMs
}

enum class RangeEstimateStatus {
    COLLECTING_DATA,
    PROVISIONAL,
    CALIBRATED,
}

data class RangeEstimate(
    val status: RangeEstimateStatus,
    val remainingKm: Double?,
    val kmPerPercent: Double?,
    val confidencePercent: Int,
    val observedDistanceKm: Double,
    val observedDepletionPercent: Double,
    val message: String,
)

package at.themrcodes.ridershub

import android.content.Context
import at.themrcodes.ridershub.wear.shared.WearConnectionStatus
import at.themrcodes.ridershub.wear.shared.WearTelemetryState
import at.themrcodes.ridershub.session.RideStore
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import java.util.Locale

internal class WearTelemetryPublisher(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val applicationContext = context.applicationContext
    private val dataClient by lazy { Wearable.getDataClient(applicationContext) }
    private val rideStore by lazy { RideStore.get(applicationContext) }
    private val remoteLauncher by lazy { WearRemoteLauncher(applicationContext) }
    private var lastLivePublishAtMs = 0L

    @Synchronized
    fun publish(snapshot: AppSnapshot, force: Boolean) {
        val now = clock()
        val telemetryWithoutRange = snapshot.toWearTelemetryState(now)
        remoteLauncher.onTelemetry(snapshot, telemetryWithoutRange)
        if (!force && lastLivePublishAtMs != 0L && now - lastLivePublishAtMs < LIVE_UPDATE_INTERVAL_MS) {
            return
        }
        lastLivePublishAtMs = now
        val telemetry = telemetryWithoutRange.copy(estimatedRangeKm = estimatedRangeKm(snapshot))
        val request = PutDataRequest.create(WearTelemetryState.DATA_PATH)
            .setData(telemetry.encode())
            .setUrgent()
        runCatching { dataClient.putDataItem(request) }
            // A phone without the Wearable Play services API remains fully supported.
            .getOrNull()
            ?.addOnFailureListener { /* The next state change retries synchronization. */ }
    }

    private fun estimatedRangeKm(snapshot: AppSnapshot): Double? {
        val restingVoltage = snapshot.packVoltageV
            ?.takeIf { (snapshot.speedKmh ?: Float.MAX_VALUE) <= 0.5f }
            ?.toDouble()
        return runCatching {
            rideStore.snapshot(snapshot.boardBatteryPercent, restingVoltage)
                .rangeEstimate
                .remainingKm
        }.getOrNull()
    }

    companion object {
        private const val LIVE_UPDATE_INTERVAL_MS = 2_000L
    }
}

internal fun AppSnapshot.toWearTelemetryState(
    nowEpochMs: Long,
    estimatedRangeKm: Double? = null,
): WearTelemetryState {
    val normalizedConnection = connection.lowercase(Locale.ROOT)
    val status = when {
        serviceActive && "listening" in normalizedConnection -> WearConnectionStatus.LIVE
        "retry" in normalizedConnection || "disconnected" in normalizedConnection ->
            WearConnectionStatus.RECONNECTING
        serviceActive && (
            present || "connecting" in normalizedConnection || "connected" in normalizedConnection
            ) -> WearConnectionStatus.CONNECTING
        else -> WearConnectionStatus.STANDBY
    }
    return WearTelemetryState(
        connection = status,
        updatedAtEpochMs = nowEpochMs,
        speedKmh = speedKmh?.toDouble()?.takeIf { it.isFinite() && it in 0.0..200.0 },
        boardBatteryPercent = boardBatteryPercent?.takeIf { it in 0..100 },
        tripKm = tripKm?.toDouble()?.takeIf { it.isFinite() && it in 0.0..1_000_000.0 },
        estimatedRangeKm = estimatedRangeKm?.takeIf {
            it.isFinite() && it in 0.0..1_000_000.0
        },
        mode = mode?.take(32),
    )
}

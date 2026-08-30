package at.themrcodes.ridershub.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import at.themrcodes.ridershub.wear.shared.WearConnectionStatus
import at.themrcodes.ridershub.wear.shared.WearTelemetryState
import java.util.Locale

/** ADB-only synthetic telemetry inlet compiled into debug APKs, never release artifacts. */
class SyntheticTelemetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_CLEAR, false)) {
            WearTelemetryRepository.accept(context, null)
            return
        }
        val ageMs = intent.getLongExtra(EXTRA_AGE_MS, 0L).coerceAtLeast(0L)
        val state = runCatching {
            WearTelemetryState(
                connection = WearConnectionStatus.valueOf(
                    intent.getStringExtra(EXTRA_CONNECTION)
                        ?.uppercase(Locale.ROOT)
                        ?: WearConnectionStatus.LIVE.name,
                ),
                updatedAtEpochMs = System.currentTimeMillis() - ageMs,
                speedKmh = intent.optionalDouble(EXTRA_SPEED_KMH),
                boardBatteryPercent = intent.optionalInt(EXTRA_BATTERY_PERCENT),
                tripKm = intent.optionalDouble(EXTRA_TRIP_KM),
                estimatedRangeKm = intent.optionalDouble(EXTRA_ESTIMATED_RANGE_KM),
                mode = intent.getStringExtra(EXTRA_MODE),
            )
        }.getOrNull() ?: return
        WearTelemetryRepository.accept(context, state)
    }

    companion object {
        private const val EXTRA_CLEAR = "clear"
        private const val EXTRA_AGE_MS = "age_ms"
        private const val EXTRA_CONNECTION = "connection"
        private const val EXTRA_SPEED_KMH = "speed_kmh"
        private const val EXTRA_BATTERY_PERCENT = "battery_percent"
        private const val EXTRA_TRIP_KM = "trip_km"
        private const val EXTRA_ESTIMATED_RANGE_KM = "estimated_range_km"
        private const val EXTRA_MODE = "mode"
    }
}

private fun Intent.optionalDouble(name: String): Double? =
    if (hasExtra(name)) getFloatExtra(name, 0f).toDouble() else null

private fun Intent.optionalInt(name: String): Int? =
    if (hasExtra(name)) getIntExtra(name, 0) else null

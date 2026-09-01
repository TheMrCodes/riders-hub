package at.themrcodes.ridershub.session

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Distance and speed mix observed while the board loses at least five battery percentage points.
 *
 * Unlike a ride summary, one window can span several rides. This allows short rides whose rounded
 * battery percentage does not change to contribute to the next measurable depletion observation.
 */
data class RangeDepletionWindow(
    val id: String,
    val localBoardId: String,
    val startedAt: String,
    val lastObservedAt: String,
    val closedAt: String?,
    val startBatteryPercent: Int,
    val endBatteryPercent: Int,
    val minBatteryPercent: Int,
    val recordedDistanceKm: Double,
    val speedBucketDistancesKm: Map<Int, Double>,
) {
    val observedDepletionPercent: Int
        get() = max(0, startBatteryPercent - minBatteryPercent)
}

data class RangeCalibrationUpdate(
    val activeWindow: RangeDepletionWindow,
    val completedWindow: RangeDepletionWindow?,
)

object RangeCalibrationTracker {
    const val DEPLETION_STEP_PERCENT = 5
    const val RECHARGE_RESET_PERCENT = 5

    fun observe(
        current: RangeDepletionWindow?,
        localBoardId: String,
        batteryPercent: Int,
        observedAt: String,
        distanceKm: Double = 0.0,
        speedBucketDistancesKm: Map<Int, Double> = emptyMap(),
        allowRechargeReset: Boolean = false,
    ): RangeCalibrationUpdate? {
        if (batteryPercent !in 0..100) return null
        if (!distanceKm.isFinite() || distanceKm < 0.0) return null

        var window = current
            ?.takeIf { it.localBoardId == localBoardId && it.closedAt == null }
            ?: newWindow(localBoardId, batteryPercent, observedAt)

        if (
            allowRechargeReset &&
            batteryPercent - window.minBatteryPercent >= RECHARGE_RESET_PERCENT
        ) {
            window = newWindow(localBoardId, batteryPercent, observedAt)
        }

        window = window.copy(
            lastObservedAt = observedAt,
            endBatteryPercent = batteryPercent,
            minBatteryPercent = min(window.minBatteryPercent, batteryPercent),
            recordedDistanceKm = window.recordedDistanceKm + distanceKm,
            speedBucketDistancesKm = mergeSpeedBuckets(
                window.speedBucketDistancesKm,
                speedBucketDistancesKm.filterValues { it.isFinite() && it > 0.0 },
            ),
        )

        if (window.observedDepletionPercent < DEPLETION_STEP_PERCENT) {
            return RangeCalibrationUpdate(window, null)
        }

        val completed = window.copy(closedAt = observedAt)
        return RangeCalibrationUpdate(
            activeWindow = newWindow(localBoardId, batteryPercent, observedAt),
            completedWindow = completed,
        )
    }

    fun observeRide(
        current: RangeDepletionWindow?,
        localBoardId: String,
        ride: RideSummary,
    ): RangeCalibrationUpdate? {
        val startBattery = ride.boardBatteryStart?.takeIf { it in 0..100 } ?: return null
        val endBattery = ride.boardBatteryEnd?.takeIf { it in 0..100 } ?: return null
        val start = if (
            current == null ||
            current.localBoardId != localBoardId ||
            startBattery - current.minBatteryPercent >= RECHARGE_RESET_PERCENT
        ) {
            observe(
                current = current,
                localBoardId = localBoardId,
                batteryPercent = startBattery,
                observedAt = ride.startedAt,
                allowRechargeReset = true,
            ) ?: return null
        } else {
            RangeCalibrationUpdate(current, null)
        }
        return observe(
            current = start.activeWindow,
            localBoardId = localBoardId,
            batteryPercent = endBattery,
            observedAt = ride.endedAt ?: ride.lastFrameAt ?: ride.startedAt,
            distanceKm = ride.distanceKm.coerceAtLeast(0.0),
            speedBucketDistancesKm = ride.speedBucketDistancesKm,
        )
    }

    private fun newWindow(
        localBoardId: String,
        batteryPercent: Int,
        observedAt: String,
    ): RangeDepletionWindow = RangeDepletionWindow(
        id = "range-$localBoardId-$observedAt-$batteryPercent",
        localBoardId = localBoardId,
        startedAt = observedAt,
        lastObservedAt = observedAt,
        closedAt = null,
        startBatteryPercent = batteryPercent,
        endBatteryPercent = batteryPercent,
        minBatteryPercent = batteryPercent,
        recordedDistanceKm = 0.0,
        speedBucketDistancesKm = emptyMap(),
    )
}

internal fun RangeDepletionWindow.toJson(): JSONObject = JSONObject()
    .put("schema_version", 1)
    .put("id", id)
    .put("local_board_id", localBoardId)
    .put("started_at", startedAt)
    .put("last_observed_at", lastObservedAt)
    .put("closed_at", closedAt ?: JSONObject.NULL)
    .put("start_battery_percent", startBatteryPercent)
    .put("end_battery_percent", endBatteryPercent)
    .put("min_battery_percent", minBatteryPercent)
    .put("recorded_distance_km", recordedDistanceKm)
    .put(
        "speed_bucket_distances_km",
        JSONObject().apply {
            speedBucketDistancesKm.toSortedMap().forEach { (bucketStartKmh, distanceKm) ->
                put(bucketStartKmh.toString(), distanceKm)
            }
        },
    )

internal fun rangeDepletionWindowFromJson(value: JSONObject): RangeDepletionWindow =
    RangeDepletionWindow(
        id = value.getString("id"),
        localBoardId = value.getString("local_board_id"),
        startedAt = value.getString("started_at"),
        lastObservedAt = value.getString("last_observed_at"),
        closedAt = value.takeUnless { it.isNull("closed_at") }?.optString("closed_at"),
        startBatteryPercent = value.getInt("start_battery_percent"),
        endBatteryPercent = value.getInt("end_battery_percent"),
        minBatteryPercent = value.getInt("min_battery_percent"),
        recordedDistanceKm = value.optDouble("recorded_distance_km", 0.0),
        speedBucketDistancesKm = value.optJSONObject("speed_bucket_distances_km")?.let { buckets ->
            buildMap {
                buckets.keys().forEach { key ->
                    key.toIntOrNull()?.takeIf { it >= 0 && it % SPEED_BUCKET_WIDTH_KMH == 0 }
                        ?.let { put(it, buckets.optDouble(key, 0.0)) }
                }
            }.filterValues { it.isFinite() && it > 0.0 }
        } ?: emptyMap(),
    )

package at.themrcodes.ridershub.session

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

enum class VoltageOperatingState {
    RESTING,
    MOVING,
}

data class VoltageCorrelationSample(
    val observedAt: String,
    val batteryPercent: Int,
    val packVoltageV: Double,
    val speedKmh: Double,
    val loadRaw: Int,
    val odometerKm: Double,
    val rideDistanceKm: Double,
) {
    val speedBucketStartKmh: Int
        get() = speedBucketStartKmh(speedKmh)

    val operatingState: VoltageOperatingState
        get() = if (speedKmh <= RESTING_SPEED_KMH) {
            VoltageOperatingState.RESTING
        } else {
            VoltageOperatingState.MOVING
        }

    companion object {
        private const val RESTING_SPEED_KMH = 0.5
    }
}

data class VoltageCorrelationKey(
    val batteryPercent: Int,
    val speedBucketStartKmh: Int,
    val operatingState: VoltageOperatingState,
)

/**
 * Compact sufficient statistics for voltage correlation work.
 *
 * Samples are grouped within one ride by battery percentage, 5 km/h speed bucket, and resting or
 * moving state. Sums and squared sums preserve means, variance, covariance inputs, and weighting
 * without retaining every high-frequency BLE notification.
 */
data class VoltageCorrelationBin(
    val batteryPercent: Int,
    val speedBucketStartKmh: Int,
    val operatingState: VoltageOperatingState,
    val firstObservedAt: String,
    val lastObservedAt: String,
    val sampleCount: Long,
    val packVoltageSumV: Double,
    val packVoltageSquaredSumV2: Double,
    val packVoltageMinV: Double,
    val packVoltageMaxV: Double,
    val speedSumKmh: Double,
    val speedSquaredSumKmh2: Double,
    val packVoltageSpeedProductSum: Double,
    val loadRawSum: Long,
    val loadRawSquaredSum: Double,
    val packVoltageLoadRawProductSum: Double,
    val speedLoadRawProductSum: Double,
    val loadRawMin: Int,
    val loadRawMax: Int,
    val odometerMinKm: Double,
    val odometerMaxKm: Double,
    val rideDistanceMinKm: Double,
    val rideDistanceMaxKm: Double,
) {
    val key: VoltageCorrelationKey
        get() = VoltageCorrelationKey(batteryPercent, speedBucketStartKmh, operatingState)

    val meanPackVoltageV: Double
        get() = packVoltageSumV / sampleCount

    val meanSpeedKmh: Double
        get() = speedSumKmh / sampleCount

    val meanLoadRaw: Double
        get() = loadRawSum.toDouble() / sampleCount

    fun add(sample: VoltageCorrelationSample): VoltageCorrelationBin {
        require(sample.batteryPercent == batteryPercent)
        require(sample.speedBucketStartKmh == speedBucketStartKmh)
        require(sample.operatingState == operatingState)
        return copy(
            lastObservedAt = sample.observedAt,
            sampleCount = sampleCount + 1,
            packVoltageSumV = packVoltageSumV + sample.packVoltageV,
            packVoltageSquaredSumV2 =
                packVoltageSquaredSumV2 + sample.packVoltageV * sample.packVoltageV,
            packVoltageMinV = min(packVoltageMinV, sample.packVoltageV),
            packVoltageMaxV = max(packVoltageMaxV, sample.packVoltageV),
            speedSumKmh = speedSumKmh + sample.speedKmh,
            speedSquaredSumKmh2 = speedSquaredSumKmh2 + sample.speedKmh * sample.speedKmh,
            packVoltageSpeedProductSum =
                packVoltageSpeedProductSum + sample.packVoltageV * sample.speedKmh,
            loadRawSum = loadRawSum + sample.loadRaw,
            loadRawSquaredSum = loadRawSquaredSum + sample.loadRaw.toDouble() * sample.loadRaw,
            packVoltageLoadRawProductSum =
                packVoltageLoadRawProductSum + sample.packVoltageV * sample.loadRaw,
            speedLoadRawProductSum = speedLoadRawProductSum + sample.speedKmh * sample.loadRaw,
            loadRawMin = min(loadRawMin, sample.loadRaw),
            loadRawMax = max(loadRawMax, sample.loadRaw),
            odometerMinKm = min(odometerMinKm, sample.odometerKm),
            odometerMaxKm = max(odometerMaxKm, sample.odometerKm),
            rideDistanceMinKm = min(rideDistanceMinKm, sample.rideDistanceKm),
            rideDistanceMaxKm = max(rideDistanceMaxKm, sample.rideDistanceKm),
        )
    }

    companion object {
        fun from(sample: VoltageCorrelationSample): VoltageCorrelationBin = VoltageCorrelationBin(
            batteryPercent = sample.batteryPercent,
            speedBucketStartKmh = sample.speedBucketStartKmh,
            operatingState = sample.operatingState,
            firstObservedAt = sample.observedAt,
            lastObservedAt = sample.observedAt,
            sampleCount = 1,
            packVoltageSumV = sample.packVoltageV,
            packVoltageSquaredSumV2 = sample.packVoltageV * sample.packVoltageV,
            packVoltageMinV = sample.packVoltageV,
            packVoltageMaxV = sample.packVoltageV,
            speedSumKmh = sample.speedKmh,
            speedSquaredSumKmh2 = sample.speedKmh * sample.speedKmh,
            packVoltageSpeedProductSum = sample.packVoltageV * sample.speedKmh,
            loadRawSum = sample.loadRaw.toLong(),
            loadRawSquaredSum = sample.loadRaw.toDouble() * sample.loadRaw,
            packVoltageLoadRawProductSum = sample.packVoltageV * sample.loadRaw,
            speedLoadRawProductSum = sample.speedKmh * sample.loadRaw,
            loadRawMin = sample.loadRaw,
            loadRawMax = sample.loadRaw,
            odometerMinKm = sample.odometerKm,
            odometerMaxKm = sample.odometerKm,
            rideDistanceMinKm = sample.rideDistanceKm,
            rideDistanceMaxKm = sample.rideDistanceKm,
        )
    }
}

internal fun addVoltageCorrelationSample(
    bins: Map<VoltageCorrelationKey, VoltageCorrelationBin>,
    sample: VoltageCorrelationSample,
): Map<VoltageCorrelationKey, VoltageCorrelationBin> {
    if (
        sample.batteryPercent !in 0..100 ||
        sample.packVoltageV !in 10.0..70.0 ||
        !sample.speedKmh.isFinite() ||
        !sample.odometerKm.isFinite() ||
        !sample.rideDistanceKm.isFinite()
    ) {
        return bins
    }
    val key = VoltageCorrelationKey(
        batteryPercent = sample.batteryPercent,
        speedBucketStartKmh = sample.speedBucketStartKmh,
        operatingState = sample.operatingState,
    )
    return bins + (key to (bins[key]?.add(sample) ?: VoltageCorrelationBin.from(sample)))
}

internal fun isVoltageSampleDue(nowEpochMs: Long, lastSampleEpochMs: Long?): Boolean =
    lastSampleEpochMs == null ||
        nowEpochMs < lastSampleEpochMs ||
        nowEpochMs - lastSampleEpochMs >= VOLTAGE_SAMPLE_INTERVAL_MS

internal const val VOLTAGE_SAMPLE_INTERVAL_MS = 10_000L

internal fun VoltageCorrelationBin.toJson(): JSONObject = JSONObject()
    .put("battery_percent", batteryPercent)
    .put("speed_bucket_start_kmh", speedBucketStartKmh)
    .put("operating_state", operatingState.name)
    .put("first_observed_at", firstObservedAt)
    .put("last_observed_at", lastObservedAt)
    .put("sample_count", sampleCount)
    .put("pack_voltage_sum_v", packVoltageSumV)
    .put("pack_voltage_squared_sum_v2", packVoltageSquaredSumV2)
    .put("pack_voltage_min_v", packVoltageMinV)
    .put("pack_voltage_max_v", packVoltageMaxV)
    .put("speed_sum_kmh", speedSumKmh)
    .put("speed_squared_sum_kmh2", speedSquaredSumKmh2)
    .put("pack_voltage_speed_product_sum", packVoltageSpeedProductSum)
    .put("load_raw_sum", loadRawSum)
    .put("load_raw_squared_sum", loadRawSquaredSum)
    .put("pack_voltage_load_raw_product_sum", packVoltageLoadRawProductSum)
    .put("speed_load_raw_product_sum", speedLoadRawProductSum)
    .put("load_raw_min", loadRawMin)
    .put("load_raw_max", loadRawMax)
    .put("odometer_min_km", odometerMinKm)
    .put("odometer_max_km", odometerMaxKm)
    .put("ride_distance_min_km", rideDistanceMinKm)
    .put("ride_distance_max_km", rideDistanceMaxKm)

internal fun voltageCorrelationBinFromJson(value: JSONObject): VoltageCorrelationBin =
    VoltageCorrelationBin(
        batteryPercent = value.getInt("battery_percent"),
        speedBucketStartKmh = value.getInt("speed_bucket_start_kmh"),
        operatingState = enumValues<VoltageOperatingState>().firstOrNull {
            it.name == value.optString("operating_state")
        } ?: VoltageOperatingState.MOVING,
        firstObservedAt = value.getString("first_observed_at"),
        lastObservedAt = value.getString("last_observed_at"),
        sampleCount = value.getLong("sample_count"),
        packVoltageSumV = value.getDouble("pack_voltage_sum_v"),
        packVoltageSquaredSumV2 = value.getDouble("pack_voltage_squared_sum_v2"),
        packVoltageMinV = value.getDouble("pack_voltage_min_v"),
        packVoltageMaxV = value.getDouble("pack_voltage_max_v"),
        speedSumKmh = value.getDouble("speed_sum_kmh"),
        speedSquaredSumKmh2 = value.getDouble("speed_squared_sum_kmh2"),
        packVoltageSpeedProductSum = value.getDouble("pack_voltage_speed_product_sum"),
        loadRawSum = value.getLong("load_raw_sum"),
        loadRawSquaredSum = value.getDouble("load_raw_squared_sum"),
        packVoltageLoadRawProductSum = value.getDouble("pack_voltage_load_raw_product_sum"),
        speedLoadRawProductSum = value.getDouble("speed_load_raw_product_sum"),
        loadRawMin = value.getInt("load_raw_min"),
        loadRawMax = value.getInt("load_raw_max"),
        odometerMinKm = value.getDouble("odometer_min_km"),
        odometerMaxKm = value.getDouble("odometer_max_km"),
        rideDistanceMinKm = value.getDouble("ride_distance_min_km"),
        rideDistanceMaxKm = value.getDouble("ride_distance_max_km"),
    )

internal fun voltageBinsToJson(bins: Collection<VoltageCorrelationBin>): JSONArray =
    JSONArray(bins.map { it.toJson() })

internal fun voltageBinsFromJson(array: JSONArray?): MutableMap<VoltageCorrelationKey, VoltageCorrelationBin> =
    if (array == null) {
        mutableMapOf()
    } else {
        buildMap {
            repeat(array.length()) {
                val bin = voltageCorrelationBinFromJson(array.getJSONObject(it))
                put(bin.key, bin)
            }
        }.toMutableMap()
    }

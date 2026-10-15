package at.themrcodes.ridershub.session

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sign

/** Board/ESC-specific zero and noise estimate for the signed raw load field. */
internal data class RawLoadZeroEstimate(
    val zeroRaw: Double? = null,
    val noiseDeviationRaw: Double? = null,
    val stationarySeconds: Double = 0.0,
    val acceptedSampleCount: Int = 0,
    val bootstrapHistogram: List<Int> = List(RawLoadZeroEstimator.BOOTSTRAP_BIN_COUNT) { 0 },
) {
    init {
        require(zeroRaw?.isFinite() != false)
        require(noiseDeviationRaw?.let { it.isFinite() && it >= 0.0 } != false)
        require(stationarySeconds.isFinite() && stationarySeconds >= 0.0)
        require(acceptedSampleCount >= 0)
        require(bootstrapHistogram.size == RawLoadZeroEstimator.BOOTSTRAP_BIN_COUNT)
        require(bootstrapHistogram.all { it >= 0 })
    }

    val calibrated: Boolean get() = zeroRaw != null
    val deadbandRaw: Double
        get() = if (zeroRaw == null) {
            RawLoadZeroEstimator.BOOTSTRAP_ABSOLUTE_LIMIT.toDouble()
        } else {
            ceil(3.0 * (noiseDeviationRaw ?: 0.0)).coerceIn(1.0, 10.0)
        }

    fun effectiveRaw(raw: Int): Double {
        val center = zeroRaw ?: 0.0
        val delta = raw - center
        val deadband = deadbandRaw
        return sign(delta) * max(0.0, abs(delta) - deadband)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("zero_raw", zeroRaw ?: JSONObject.NULL)
        .put("noise_deviation_raw", noiseDeviationRaw ?: JSONObject.NULL)
        .put("stationary_seconds", stationarySeconds)
        .put("accepted_sample_count", acceptedSampleCount)
        .put("bootstrap_histogram", JSONArray(bootstrapHistogram))

    companion object {
        fun fromJson(value: JSONObject): RawLoadZeroEstimate? = runCatching {
            if (value.optInt("version") != 1) return null
            val histogram = value.getJSONArray("bootstrap_histogram")
            RawLoadZeroEstimate(
                zeroRaw = value.takeUnless { it.isNull("zero_raw") }?.getDouble("zero_raw"),
                noiseDeviationRaw = value.takeUnless { it.isNull("noise_deviation_raw") }
                    ?.getDouble("noise_deviation_raw"),
                stationarySeconds = value.getDouble("stationary_seconds"),
                acceptedSampleCount = value.getInt("accepted_sample_count"),
                bootstrapHistogram = List(histogram.length(), histogram::getInt),
            )
        }.getOrNull()
    }
}

internal object RawLoadZeroEstimator {
    const val BOOTSTRAP_ABSOLUTE_LIMIT = 10
    const val BOOTSTRAP_BIN_COUNT = BOOTSTRAP_ABSOLUTE_LIMIT * 2 + 1
    const val MIN_BOOTSTRAP_SAMPLES = 50
    private const val MIN_STATIONARY_SECONDS = 5.0
    private const val MAX_STATIONARY_SECONDS = 60.0
    private const val TRACKING_ACCEPTANCE_RAW = 10.0
    private const val TRACKING_ALPHA = 0.01

    fun observe(
        current: RawLoadZeroEstimate,
        raw: Int,
        stationaryEligible: Boolean,
        elapsedSeconds: Double,
    ): RawLoadZeroEstimate {
        if (!stationaryEligible || !elapsedSeconds.isFinite() || elapsedSeconds <= 0.0 ||
            elapsedSeconds > 2.0
        ) return current.copy(stationarySeconds = 0.0)

        val stationarySeconds = (current.stationarySeconds + elapsedSeconds)
            .coerceAtMost(MAX_STATIONARY_SECONDS)
        if (stationarySeconds < MIN_STATIONARY_SECONDS) {
            return current.copy(stationarySeconds = stationarySeconds)
        }

        val zero = current.zeroRaw
        if (zero == null) {
            if (raw !in -BOOTSTRAP_ABSOLUTE_LIMIT..BOOTSTRAP_ABSOLUTE_LIMIT) {
                return current.copy(stationarySeconds = stationarySeconds)
            }
            val histogram = current.bootstrapHistogram.toMutableList()
            histogram[raw + BOOTSTRAP_ABSOLUTE_LIMIT]++
            val count = current.acceptedSampleCount + 1
            if (count < MIN_BOOTSTRAP_SAMPLES) {
                return current.copy(
                    stationarySeconds = stationarySeconds,
                    acceptedSampleCount = count,
                    bootstrapHistogram = histogram,
                )
            }
            val samples = histogram.flatMapIndexed { index, occurrences ->
                List(occurrences) { (index - BOOTSTRAP_ABSOLUTE_LIMIT).toDouble() }
            }.sorted()
            val median = median(samples)
            val deviation = median(samples.map { abs(it - median) }.sorted())
            return current.copy(
                zeroRaw = median,
                noiseDeviationRaw = deviation,
                stationarySeconds = stationarySeconds,
                acceptedSampleCount = count,
                bootstrapHistogram = histogram,
            )
        }

        val residual = raw - zero
        if (abs(residual) > TRACKING_ACCEPTANCE_RAW) {
            return current.copy(stationarySeconds = stationarySeconds)
        }
        val nextZero = zero + TRACKING_ALPHA * residual.coerceIn(-1.0, 1.0)
        val absoluteDeviation = abs(raw - nextZero)
        val previousDeviation = current.noiseDeviationRaw ?: absoluteDeviation
        return current.copy(
            zeroRaw = nextZero,
            noiseDeviationRaw = previousDeviation +
                TRACKING_ALPHA * (absoluteDeviation - previousDeviation),
            stationarySeconds = stationarySeconds,
            acceptedSampleCount = if (current.acceptedSampleCount == Int.MAX_VALUE) {
                Int.MAX_VALUE
            } else current.acceptedSampleCount + 1,
        )
    }

    private fun median(sorted: List<Double>): Double {
        require(sorted.isNotEmpty())
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else sorted[middle]
    }
}

internal class RawLoadZeroRegistry(encoded: String = "{}") {
    private val values = mutableMapOf<String, RawLoadZeroEstimate>()

    init {
        val json = runCatching { JSONObject(encoded) }.getOrDefault(JSONObject())
        json.keys().forEach { board ->
            json.optJSONObject(board)?.let(RawLoadZeroEstimate::fromJson)?.let {
                values[board] = it
            }
        }
    }

    fun state(board: String): RawLoadZeroEstimate? = values[board]

    fun observe(
        board: String,
        raw: Int,
        stationaryEligible: Boolean,
        elapsedSeconds: Double,
    ): RawLoadZeroEstimate = RawLoadZeroEstimator.observe(
        values[board] ?: RawLoadZeroEstimate(),
        raw,
        stationaryEligible,
        elapsedSeconds,
    ).also { values[board] = it }

    fun toJson(): JSONObject = JSONObject().apply {
        values.forEach { (board, estimate) -> put(board, estimate.toJson()) }
    }
}

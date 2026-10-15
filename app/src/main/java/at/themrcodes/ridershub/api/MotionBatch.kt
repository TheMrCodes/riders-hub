package at.themrcodes.ridershub.api

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Retain at most 50 Hz per sensor using monotonic timestamps; never synthesize samples. */
internal class MotionBatch(
    private val sourceId: String,
    private val boardId: String,
    private val rideId: String,
    private val startedAt: String,
    private val originNanos: Long,
    private val sensors: List<String>,
    private val streamId: String = UUID.randomUUID().toString(),
) {
    private val pending = ArrayList<JSONObject>()
    private val latest = mutableMapOf<String, Long>()
    private var sequence = 0L
    val size get() = pending.size
    fun add(sensor: String, timestampNanos: Long, values: FloatArray, accuracy: Int): Boolean {
        val elapsed = timestampNanos - originNanos
        if (sensor !in sensors || values.size < 3 || values.take(3).any { !it.isFinite() } ||
            elapsed < 0 || elapsed <= (latest[sensor] ?: -1) || accuracy !in -1..3) return false
        if (latest[sensor]?.let { elapsed - it < SAMPLE_PERIOD_US * 1_000L } == true) return false
        check(pending.size < MAX_SAMPLES) { "Motion batch buffer is full" }
        pending.add(JSONObject().put("sensor", sensor).put("elapsed_ns", elapsed)
            .put("x", values[0].toDouble()).put("y", values[1].toDouble()).put("z", values[2].toDouble())
            .put("accuracy", accuracy))
        latest[sensor] = elapsed
        return true
    }
    fun drain(): JSONObject? {
        if (pending.isEmpty()) return null
        val batch = JSONObject().put("schema_version", 1).put("batch_id", UUID.randomUUID().toString())
            .put("source_id", sourceId).put("board_id", boardId).put("ride_id", rideId)
            .put("stream_id", streamId).put("sequence", ++sequence).put("started_at", startedAt)
            .put("device", "phone").put("coordinate_system", "android_device")
            .put("accelerometer_unit", "m/s2").put("gyroscope_unit", "rad/s")
            .put("sample_period_us", SAMPLE_PERIOD_US).put("sensors", JSONArray(sensors))
            .put("samples", JSONArray(pending))
        pending.clear()
        return batch
    }
    companion object {
        const val SAMPLE_PERIOD_US = 20_000
        const val MAX_SAMPLES = 4096
    }
}

package at.themrcodes.ridershub.log

import android.os.SystemClock
import at.themrcodes.ridershub.BuildConfig
import at.themrcodes.ridershub.session.RideSegment
import at.themrcodes.ridershub.session.RideSummary
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class JsonlSessionLog(
    private val segment: RideSegment,
) {
    private val accepting = AtomicBoolean(true)
    private val sequence = AtomicLong(maxOf(segment.initialSequence, findLastSequence(File(segment.logFile))))
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "riders-hub-jsonl-writer").apply { priority = Thread.MIN_PRIORITY }
    }
    val file: File = File(segment.logFile).also { it.parentFile?.mkdirs() }
    private val writer = BufferedWriter(
        OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8),
        64 * 1024,
    )
    private var linesSinceFlush = 0
    private var lastFlushElapsedMs = SystemClock.elapsedRealtime()

    init {
        if (segment.newSession) {
            record(
                "session_start",
                JSONObject()
                    .put("schema_version", 3)
                    .put("session_id", segment.sessionId)
                    .put("app_version", BuildConfig.VERSION_NAME)
                    .put("device_name", segment.deviceName ?: JSONObject.NULL)
                    .put("device_address", segment.deviceAddress)
                    .put("transport", "BLE GATT via CompanionDeviceService")
                    .put(
                        "write_policy",
                        "notifications plus explicit user-confirmed child-limiter writes; no automatic commands",
                    )
                    .put("disconnect_grace_ms", 120_000),
            )
        }
        record(
            "connection_segment_start",
            JSONObject()
                .put("session_id", segment.sessionId)
                .put("segment_number", segment.segmentNumber)
                .put("resumed_session", !segment.newSession),
        )
    }

    fun record(type: String, fields: JSONObject = JSONObject()) {
        if (!accepting.get()) return
        val envelope = envelope(type, fields)
        executor.execute {
            write(envelope, forceFlush = false)
        }
    }

    fun closeSegment(reason: String, fields: JSONObject = JSONObject()): Long {
        if (!accepting.compareAndSet(true, false)) return sequence.get()
        val envelope = envelope(
            "connection_segment_end",
            fields
                .put("session_id", segment.sessionId)
                .put("segment_number", segment.segmentNumber)
                .put("reason", reason),
        )
        executor.execute {
            try {
                write(envelope, forceFlush = true)
            } finally {
                writer.close()
            }
        }
        executor.shutdown()
        return sequence.get()
    }

    fun awaitClosed(timeoutSeconds: Long = 5): Boolean =
        executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)

    private fun envelope(type: String, fields: JSONObject): JSONObject {
        val result = JSONObject()
            .put("seq", sequence.incrementAndGet())
            .put("type", type)
            .put("wall_time", Instant.now().toString())
            .put("elapsed_realtime_ns", SystemClock.elapsedRealtimeNanos())
        fields.keys().forEach { key -> result.put(key, fields.get(key)) }
        return result
    }

    private fun write(value: JSONObject, forceFlush: Boolean) {
        synchronized(FILE_LOCK) {
            writer.write(value.toString())
            writer.newLine()
            linesSinceFlush += 1
            val now = SystemClock.elapsedRealtime()
            if (forceFlush || linesSinceFlush >= 100 || now - lastFlushElapsedMs >= 5_000) {
                writer.flush()
                linesSinceFlush = 0
                lastFlushElapsedMs = now
            }
        }
    }

    companion object {
        private val FILE_LOCK = Any()

        fun appendSessionEnd(
            file: File,
            initialSequence: Long,
            reason: String,
            summary: RideSummary,
        ): Long {
            val sequence = maxOf(initialSequence, findLastSequence(file)) + 1
            val value = JSONObject()
                .put("seq", sequence)
                .put("type", "session_end")
                .put("wall_time", Instant.now().toString())
                .put("elapsed_realtime_ns", SystemClock.elapsedRealtimeNanos())
                .put("session_id", summary.id)
                .put("reason", reason)
                .put("distance_km", summary.distanceKm)
                .put("moving_seconds", summary.movingSeconds)
                .put("max_speed_kmh", summary.maxSpeedKmh)
                .put("board_battery_start", summary.boardBatteryStart ?: JSONObject.NULL)
                .put("board_battery_end", summary.boardBatteryEnd ?: JSONObject.NULL)
                .put("pack_voltage_start", summary.packVoltageStart ?: JSONObject.NULL)
                .put("pack_voltage_end", summary.packVoltageEnd ?: JSONObject.NULL)
                .put("frame_count", summary.frameCount)
                .put("crc_error_count", summary.crcErrorCount)
                .put("segment_count", summary.segmentCount)
            synchronized(FILE_LOCK) {
                file.parentFile?.mkdirs()
                file.appendText(value.toString() + "\n", Charsets.UTF_8)
            }
            return sequence
        }

        private fun findLastSequence(file: File): Long {
            if (!file.exists() || file.length() == 0L) return 0
            return runCatching {
                RandomAccessFile(file, "r").use { input ->
                    var position = input.length() - 1
                    while (position >= 0) {
                        input.seek(position)
                        if (input.readByte().toInt() !in listOf('\n'.code, '\r'.code)) break
                        position -= 1
                    }
                    val reversed = StringBuilder()
                    while (position >= 0) {
                        input.seek(position)
                        val byte = input.readByte().toInt() and 0xFF
                        if (byte == '\n'.code || byte == '\r'.code) break
                        reversed.append(byte.toChar())
                        position -= 1
                    }
                    val lastLine = reversed.reverse().toString()
                    JSONObject(lastLine).optLong("seq", 0)
                }
            }.getOrDefault(0)
        }
    }
}

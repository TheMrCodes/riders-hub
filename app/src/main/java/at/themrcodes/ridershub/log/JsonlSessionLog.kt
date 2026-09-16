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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException

/** A BLE-segment handle over the serialized writer owned by the logical ride. */
class JsonlSessionLog(private val segment: RideSegment) {
    private val segmentAccepting = AtomicBoolean(true)
    private val shared = sharedWriter(File(segment.logFile), segment.initialSequence)
    @Volatile private var closeBarrier: CountDownLatch? = null
    val file: File = shared.file

    init {
        if (segment.newSession) {
            record(
                "session_start",
                JSONObject()
                    .put("schema_version", 5)
                    .put("session_id", segment.sessionId)
                    .put("app_version", BuildConfig.VERSION_NAME)
                    .put("device_name", segment.deviceName ?: JSONObject.NULL)
                    .put("device_address", segment.deviceAddress)
                    .put("transport", "BLE GATT via CompanionDeviceService")
                    .put("write_policy", "serialized ride-owned record stream")
                    .put("disconnect_grace_ms", 120_000),
            )
        }
        record(
            "connection_segment_start",
            JSONObject()
                .put("session_id", segment.sessionId)
                .put("segment_number", segment.segmentNumber)
                .put("telemetry_schema_version", 5)
                .put("resumed_session", !segment.newSession),
        )
    }

    @Synchronized
    fun record(
        type: String,
        fields: JSONObject = JSONObject(),
        wallTime: Instant = Instant.now(),
        elapsedRealtimeNs: Long = SystemClock.elapsedRealtimeNanos(),
    ) {
        if (segmentAccepting.get()) shared.record(type, fields, wallTime, elapsedRealtimeNs)
    }

    @Synchronized
    fun closeSegment(reason: String, fields: JSONObject = JSONObject()): Long {
        if (!segmentAccepting.compareAndSet(true, false)) return shared.sequence.get()
        val barrier = CountDownLatch(1).also { closeBarrier = it }
        shared.record(
            "connection_segment_end",
            fields.put("session_id", segment.sessionId)
                .put("segment_number", segment.segmentNumber)
                .put("reason", reason),
            forceFlush = true,
            afterWrite = barrier::countDown,
        )
        return shared.sequence.get()
    }

    fun awaitClosed(timeoutSeconds: Long = 5): Boolean =
        closeBarrier?.await(timeoutSeconds, TimeUnit.SECONDS) ?: true

    private class SharedWriter(val file: File, initialSequence: Long) {
        val sequence = AtomicLong(initialSequence)
        private val accepting = AtomicBoolean(true)
        private val failure = AtomicReference<Throwable?>()
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "riders-hub-ride-writer").apply { priority = Thread.MIN_PRIORITY }
        }
        private val writer: BufferedWriter
        private var linesSinceFlush = 0
        private var lastFlushElapsedMs = SystemClock.elapsedRealtime()

        init {
            file.parentFile?.mkdirs()
            recoverTail(file)
            sequence.set(maxOf(initialSequence, findLastSequence(file)))
            check(lastRecordType(file) != "session_end") { "Cannot reopen a finalized ride" }
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), 64 * 1024)
        }

        @Synchronized
        fun record(
            type: String,
            fields: JSONObject,
            wallTime: Instant = Instant.now(),
            elapsedRealtimeNs: Long = SystemClock.elapsedRealtimeNanos(),
            forceFlush: Boolean = false,
            afterWrite: () -> Unit = {},
        ): Long {
            if (!accepting.get()) { afterWrite(); return sequence.get() }
            // Freeze mutable caller-owned JSON before crossing the producer/writer boundary.
            val value = sessionLogEnvelope(sequence.incrementAndGet(), type, JSONObject(fields.toString()), wallTime, elapsedRealtimeNs)
            executor.execute {
                try {
                    if (failure.get() == null) write(value, forceFlush)
                } catch (error: Exception) { failure.compareAndSet(null, error) }
                finally { afterWrite() }
            }
            return sequence.get()
        }

        @Synchronized
        fun finish(reason: String, summary: RideSummary, timeoutSeconds: Long): Long {
            if (!accepting.compareAndSet(true, false)) {
                check(executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) { "Ride writer is still draining" }
                failure.get()?.let { throw IOException("Ride log could not be finalized", it) }
                return sequence.get()
            }
            val value = sessionLogEnvelope(
                sequence.incrementAndGet(), "session_end",
                JSONObject()
                    .put("session_id", summary.id)
                    .put("reason", reason)
                    .put("distance_km", summary.distanceKm)
                    .put("moving_seconds", summary.movingSeconds)
                    .put("max_speed_kmh", summary.maxSpeedKmh)
                    .put("speed_bucket_distances_km", JSONObject().apply {
                        summary.speedBucketDistancesKm.toSortedMap().forEach { (bucket, distance) ->
                            put(bucket.toString(), distance)
                        }
                    })
                    .put("board_battery_start", summary.boardBatteryStart ?: JSONObject.NULL)
                    .put("board_battery_end", summary.boardBatteryEnd ?: JSONObject.NULL)
                    .put("estimated_battery_percent", summary.estimatedBatteryPercent ?: JSONObject.NULL)
                    .put("pack_voltage_start", summary.packVoltageStart ?: JSONObject.NULL)
                    .put("pack_voltage_end", summary.packVoltageEnd ?: JSONObject.NULL)
                    .put("frame_count", summary.frameCount)
                    .put("crc_error_count", summary.crcErrorCount)
                    .put("segment_count", summary.segmentCount),
                Instant.now(), SystemClock.elapsedRealtimeNanos(),
            )
            executor.execute {
                try {
                    if (failure.get() == null) write(value, true)
                } catch (error: Exception) { failure.compareAndSet(null, error) }
                finally {
                    try { writer.close() } catch (error: Exception) { failure.compareAndSet(null, error) }
                }
            }
            executor.shutdown()
            check(executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                "Timed out draining the ride record stream"
            }
            failure.get()?.let { throw IOException("Ride log could not be finalized", it) }
            return sequence.get()
        }

        private fun write(value: JSONObject, forceFlush: Boolean) {
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
        private val REGISTRY_LOCK = Any()
        private val WRITERS = mutableMapOf<String, SharedWriter>()

        private fun sharedWriter(file: File, initialSequence: Long): SharedWriter = synchronized(REGISTRY_LOCK) {
            WRITERS.getOrPut(file.absolutePath) { SharedWriter(file, initialSequence) }
        }

        fun recordRide(
            file: File,
            type: String,
            fields: JSONObject,
            wallTime: Instant = Instant.now(),
            elapsedRealtimeNs: Long = SystemClock.elapsedRealtimeNanos(),
        ): Long {
            // A late GPS callback must never recreate the writer after finalization/archive.
            val writer = synchronized(REGISTRY_LOCK) { WRITERS[file.absolutePath] } ?: return 0
            return writer.record(type, fields, wallTime, elapsedRealtimeNs)
        }

        /** Explicitly recover a still-active ride; callbacks cannot open arbitrary log paths. */
        fun resumeRide(file: File) { sharedWriter(file, 0) }

        fun finishSession(file: File, initialSequence: Long, reason: String, summary: RideSummary): Long {
            val existing = synchronized(REGISTRY_LOCK) { WRITERS[file.absolutePath] }
            if (existing == null) {
                recoverTail(file)
                if (lastRecordType(file) == "session_end") return findLastSequence(file)
            }
            val writer = existing ?: sharedWriter(file, initialSequence)
            val result = writer.finish(reason, summary, 10)
            synchronized(REGISTRY_LOCK) { WRITERS.remove(file.absolutePath, writer) }
            return result
        }

        fun appendSessionEnd(file: File, initialSequence: Long, reason: String, summary: RideSummary): Long =
            finishSession(file, initialSequence, reason, summary)

        private fun findLastSequence(file: File): Long = lastJsonObject(file)?.optLong("seq", 0) ?: 0

        private fun lastRecordType(file: File): String? = lastJsonObject(file)?.optString("type")

        private fun lastJsonObject(file: File): JSONObject? {
            if (!file.exists() || file.length() == 0L) return null
            return runCatching {
                RandomAccessFile(file, "r").use { input ->
                    var position = input.length() - 1
                    while (position >= 0) {
                        input.seek(position)
                        if (input.readByte().toInt() !in listOf('\n'.code, '\r'.code)) break
                        position -= 1
                    }
                    val end = position + 1
                    while (position >= 0) {
                        input.seek(position)
                        val byte = input.readByte().toInt() and 0xFF
                        if (byte == '\n'.code || byte == '\r'.code) break
                        position -= 1
                    }
                    val bytes = ByteArray((end - position - 1).toInt())
                    input.seek(position + 1)
                    input.readFully(bytes)
                    JSONObject(bytes.toString(Charsets.UTF_8))
                }
            }.getOrNull()
        }

        /** Discard only an interrupted trailing record before appending after process death. */
        internal fun recoverTail(file: File) {
            if (!file.exists() || file.length() == 0L) return
            RandomAccessFile(file, "rw").use { input ->
                while (input.length() > 0) {
                    if (lastJsonObject(file) != null) {
                        input.seek(input.length() - 1)
                        if (input.readByte().toInt() != '\n'.code) {
                            input.seek(input.length())
                            input.write('\n'.code)
                        }
                        return
                    }
                    var position = input.length() - 1
                    while (position >= 0) {
                        input.seek(position)
                        if (input.readByte().toInt() !in listOf('\n'.code, '\r'.code)) break
                        position -= 1
                    }
                    while (position >= 0) {
                        input.seek(position)
                        if (input.readByte().toInt() == '\n'.code) break
                        position -= 1
                    }
                    input.setLength(position + 1)
                }
            }
        }
    }
}

internal fun sessionLogEnvelope(
    sequence: Long,
    type: String,
    fields: JSONObject,
    wallTime: Instant,
    elapsedRealtimeNs: Long,
): JSONObject = JSONObject().apply {
    fields.keys().forEach { key -> put(key, fields.get(key)) }
    put("seq", sequence)
    put("type", type)
    put("wall_time", wallTime.toString())
    put("elapsed_realtime_ns", elapsedRealtimeNs)
}

package at.themrcodes.ridershub.api

import java.time.Instant

internal data class ApiFrame(
    val rideId: String,
    val captureSegmentId: String,
    val frameNumber: Long,
    val frameAt: Instant,
    val segmentElapsedMs: Long,
    val speedKmh: Double,
    val receivedAtNanos: Long = 0,
    val raw: ByteArray? = null,
)

/** Bounded handoff; the producer never performs IO or waits for network processing. */
internal class ApiFrameBuffer(private val capacity: Int = 2048) {
    internal data class Batch(val frames: List<ApiFrame>, val generation: Long, val overflow: Boolean)
    private val pending = ArrayDeque<ApiFrame>()
    private var generation = 0L
    private var overflow = false
    @Synchronized fun offer(frame: ApiFrame) {
        if (overflow) return
        if (pending.size == capacity) { pending.clear(); overflow = true; return }
        pending.addLast(frame)
    }
    @Synchronized fun take(): Batch {
        val batch = Batch(pending.toList(), generation, overflow)
        pending.clear(); overflow = false
        return batch
    }
    @Synchronized fun isCurrent(batch: Batch): Boolean = generation == batch.generation
    @Synchronized fun clear() { pending.clear(); overflow = false; generation++ }
}

package at.themrcodes.ridershub.api

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ApiFrameBufferTest {
    private fun frame(number: Long) = ApiFrame("synthetic-ride", "synthetic-segment", number,
        Instant.EPOCH.plusSeconds(number), number * 1000, 12.0)

    @Test fun retainsEveryFrameInOrderUntilDrained() {
        val buffer = ApiFrameBuffer()
        for (i in 1L..1000L) buffer.offer(frame(i))
        assertEquals((1L..1000L).toList(), buffer.take().frames.map { it.frameNumber })
        assertTrue(buffer.take().frames.isEmpty())
    }
    @Test fun overflowIsBoundedAndReportedInsteadOfSilentlyDroppingFrames() {
        val buffer = ApiFrameBuffer(2)
        for (i in 1L..100L) buffer.offer(frame(i))
        val result = buffer.take()
        assertTrue(result.overflow)
        assertTrue(result.frames.isEmpty())
        buffer.offer(frame(101))
        assertEquals(101L, buffer.take().frames.single().frameNumber)
    }
    @Test fun resetInvalidatesBothQueuedAndAlreadyDrainedFrames() {
        val buffer = ApiFrameBuffer()
        buffer.offer(frame(1))
        val taken = buffer.take()
        buffer.offer(frame(2))
        buffer.clear()
        assertFalse(buffer.isCurrent(taken))
        assertTrue(buffer.take().frames.isEmpty())
    }
}

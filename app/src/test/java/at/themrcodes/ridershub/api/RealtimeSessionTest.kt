package at.themrcodes.ridershub.api

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RealtimeSessionTest {
    @Test fun gpsRevocationRemovesLocationFromAnAlreadyPendingFrame() {
        val fixture = Fixture()
        fixture.open()
        fixture.offer(1)
        fixture.now = 3_000_000_000; fixture.session.flushBatch()
        fixture.fix = RealtimeLocation("2030-01-01T00:00:00Z", 0.0, 0.0, null, fixture.now)
        fixture.now = 5_000_000_000
        fixture.gpsAllowed = false
        fixture.session.flushBatch()
        assertFalse(fixture.telemetry().single().has("location"))
        fixture.session.close()
    }

    @Test fun lateLocationCallbackCannotRestoreClearedGpsState() {
        val fix = RealtimeLocation("2030-01-01T00:00:00Z", 0.0, 0.0, null, 0)
        LocationFixState.begin("synthetic-ride-one")
        LocationFixState.stop()
        var recorded = false
        assertFalse(LocationFixState.accept("synthetic-ride-one", fix) { recorded = true })
        assertFalse(recorded)
        assertNull(LocationFixState.latest())
        LocationFixState.begin("synthetic-ride-two")
        assertFalse(LocationFixState.accept("synthetic-ride-one", fix) { recorded = true })
        LocationFixState.stop()
    }
    @Test fun batchesEveryFrameAtFiveSecondsAndRequestsOnlyOneFixBeforeSend() {
        val fixture = Fixture()
        fixture.open()
        fixture.offer(1); fixture.offer(2); fixture.session.flushBatch()
        assertTrue(fixture.telemetry().isEmpty())
        assertEquals(0, fixture.requests)
        fixture.now = 3_000_000_000; fixture.session.flushBatch()
        fixture.now = 4_000_000_000; fixture.session.flushBatch()
        assertEquals(1, fixture.requests)
        fixture.fix = RealtimeLocation("2030-01-01T00:00:04Z", 0.0, 0.0, null, fixture.now)
        fixture.offer(3)
        fixture.now = 4_999_999_999; fixture.session.flushBatch()
        assertTrue(fixture.telemetry().isEmpty())
        fixture.now = 5_000_000_000; fixture.session.flushBatch()
        assertEquals(listOf(1L, 2L, 3L), fixture.telemetry().map { it.getLong("frame_number") })
        assertEquals(listOf(1L, 2L, 3L), fixture.telemetry().map { it.getLong("stream_sequence") })
        assertEquals(1, fixture.telemetry().count { it.has("location") })
        assertTrue(fixture.telemetry().last().has("location"))
        fixture.offer(3); fixture.offer(4)
        fixture.now = 8_000_000_000; fixture.session.flushBatch()
        assertEquals(2, fixture.requests)
        fixture.fix = null
        fixture.now = 10_000_000_000; fixture.session.flushBatch()
        assertEquals(listOf(1L, 2L, 3L, 4L), fixture.telemetry().map { it.getLong("frame_number") })
        assertFalse(fixture.telemetry().last().has("location"))
        fixture.session.close()
    }

    @Test fun disablingStreamDiscardsBufferedFramesAndCancelsSocket() {
        val fixture = Fixture()
        fixture.open(); fixture.offer(1); fixture.allowed = false
        fixture.now = 5_000_000_000; fixture.session.flushBatch()
        assertTrue(fixture.telemetry().isEmpty())
        assertTrue(fixture.socket.cancelled)
    }

    @Test fun firstDeadlineIncludesTimeAlreadySpentInTheWorkerHandoff() {
        val fixture = Fixture()
        fixture.now = 1_000_000_000
        fixture.open()
        fixture.session.offer("segment", 1, "2030-01-01T00:00:00Z", 0, 12.0, receivedAtNanos = 0)
        fixture.now = 5_000_000_000; fixture.session.flushBatch()
        assertEquals(1, fixture.telemetry().size)
        fixture.session.close()
    }

    @Test fun emptyBatchesAndDisabledGpsNeverRequestLocation() {
        val fixture = Fixture()
        fixture.open(); fixture.now = 3_000_000_000; fixture.session.flushBatch()
        assertEquals(0, fixture.requests)
        fixture.offer(1); fixture.gpsAllowed = false
        fixture.now = 4_000_000_000; fixture.session.flushBatch()
        fixture.now = 5_000_000_000; fixture.session.flushBatch()
        assertEquals(0, fixture.requests)
        assertEquals(1, fixture.telemetry().size)
        fixture.session.close()
    }

    @Test fun upgradeDelayRetainsFramesButOverflowCancelsRatherThanGrowing() {
        val fixture = Fixture()
        fixture.session.open(); fixture.offer(1); fixture.offer(2); fixture.upgrade()
        fixture.now = 5_000_000_000; fixture.session.flushBatch()
        assertEquals(listOf(1L, 2L), fixture.telemetry().map { it.getLong("frame_number") })
        for (frame in 3L..2051L) fixture.offer(frame)
        assertTrue(fixture.socket.cancelled)
        assertEquals(1, fixture.disconnections)
    }

    @Test fun closeDuringUpgradeRejectsLateOpenAndNeverReplaysQueuedSample() {
        val fixture = Fixture()
        fixture.session.open()
        fixture.offer(1)
        fixture.session.close()
        fixture.upgrade()
        fixture.session.flushBatch()
        assertTrue(fixture.socket.cancelled)
        assertTrue(fixture.socket.messages.isEmpty())
        assertEquals(0, fixture.disconnections)
    }

    @Test fun remoteClosingClearsSamplesAndNotifiesOnlyOnce() {
        val fixture = Fixture()
        fixture.open(); fixture.offer(1)
        fixture.listener.onClosing(fixture.socket, 1000, "")
        fixture.listener.onClosed(fixture.socket, 1000, "")
        fixture.now = 2_000_000_000; fixture.session.flushBatch()
        assertEquals(1, fixture.disconnections)
        assertTrue(fixture.telemetry().isEmpty())
    }

    @Test fun stalledSocketIsCancelledEvenWithoutNewFrames() {
        val fixture = Fixture()
        fixture.open(); fixture.socket.queuedBytes = 100
        fixture.session.flushBatch()
        fixture.now = 5_000_000_000; fixture.session.flushBatch()
        assertTrue(fixture.socket.cancelled)
        assertEquals(1, fixture.disconnections)
    }

    @Test fun subprotocolMismatchAndIncomingCommandsPauseTheConnection() {
        val mismatch = Fixture()
        mismatch.session.open(); mismatch.upgrade("wrong")
        assertTrue(mismatch.paused)
        assertTrue(mismatch.socket.messages.isEmpty())
        val command = Fixture()
        command.open(); command.listener.onMessage(command.socket, "{}")
        assertTrue(command.paused)
        assertTrue(command.socket.cancelled)
    }

    @Test fun delayedSocketUpgradeCannotOpenAStreamAfterAuthorizationRevoked() {
        val fixture = Fixture()
        fixture.session.open()
        fixture.allowed = false
        fixture.upgrade()
        assertTrue(fixture.socket.cancelled)
        assertTrue(fixture.socket.messages.isEmpty())
    }

    @Test fun trackingPauseKeepsTheSocketAndResumesTheSameStream() {
        val fixture = Fixture()
        fixture.open(); fixture.offer(1)
        fixture.now = 5_000_000_000; fixture.session.flushBatch()
        fixture.offer(2)
        fixture.now = 8_000_000_000; fixture.session.flushBatch()
        assertEquals(1, fixture.requests)
        fixture.tracking = false
        fixture.now = 10_000_000_000; fixture.session.flushBatch()
        fixture.offer(3) // Paused frames must not leak into the resumed stream.
        fixture.now = 60_000_000_000; fixture.session.flushBatch()
        assertFalse(fixture.socket.cancelled)
        assertEquals(0, fixture.disconnections)
        assertEquals(1, fixture.telemetry().size)
        assertEquals(1, fixture.requests)
        fixture.tracking = true
        fixture.offer(4)
        fixture.now = 65_000_000_000; fixture.session.flushBatch()
        val telemetry = fixture.telemetry()
        assertEquals(2, telemetry.size)
        assertEquals(2L, telemetry.last().getLong("stream_sequence"))
        assertEquals(telemetry.first().getString("stream_id"), telemetry.last().getString("stream_id"))
        assertEquals(3, fixture.socket.messages.size) // Exactly one stream.opened.
        fixture.session.close()
        assertTrue(fixture.socket.cancelled)
    }

    private class Fixture {
        val socket = FakeSocket()
        lateinit var listener: WebSocketListener
        var now = 0L
        var disconnections = 0
        var paused = false
        var gpsAllowed = true
        var allowed = true
        var tracking = true
        var requests = 0
        var fix: RealtimeLocation? = null
        val session = RealtimeSession(
            ApiConnection("https://api.example.test", "synthetic", "destination"),
            RealtimeSession.Context("source", "board", "ride"),
            onState = { _, _ -> }, onDisconnected = { disconnections++; paused = it },
            allowed = { _, _ -> allowed }, trackingAllowed = { tracking }, locationAllowed = { gpsAllowed },
            requestLocation = { requests++ }, latestLocation = { fix },
            nowNanos = { now }, openSocket = { _, value -> listener = value; socket }, automaticFlush = false)
        fun open() { session.open(); upgrade() }
        fun upgrade(protocol: String = REALTIME_SUBPROTOCOL) = listener.onOpen(socket, Response.Builder()
            .request(socket.request()).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols")
            .header("Sec-WebSocket-Protocol", protocol).build())
        fun offer(frame: Long) = session.offer("segment", frame, "2030-01-01T00:00:00Z", frame * 100, 12.0)
        fun telemetry() = socket.messages.map(::JSONObject).filter { it.getString("type") == "telemetry" }
    }

    private class FakeSocket : WebSocket {
        val messages = mutableListOf<String>()
        var cancelled = false
        var queuedBytes = 0L
        override fun request() = Request.Builder().url("https://api.example.test").build()
        override fun queueSize() = queuedBytes
        override fun send(text: String): Boolean { if (cancelled) return false; messages.add(text); return true }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }
}

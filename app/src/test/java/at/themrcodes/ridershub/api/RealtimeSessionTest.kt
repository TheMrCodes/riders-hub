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
        fixture.session.offer("segment", 1, "2030-01-01T00:00:00Z", 0, 12.0,
            RealtimeLocation("2030-01-01T00:00:00Z", 0.0, 0.0, null, 0))
        fixture.gpsAllowed = false
        fixture.session.flushLatest()
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
    @Test fun coalescesNewestFrameAndEnforcesActualElapsedTimeWithoutDuplicates() {
        val fixture = Fixture()
        fixture.open()
        fixture.offer(1); fixture.offer(2); fixture.session.flushLatest()
        assertEquals(2L, fixture.telemetry().single().getLong("frame_number"))
        fixture.offer(3); fixture.now = 999_999_999; fixture.session.flushLatest()
        assertEquals(1, fixture.telemetry().size)
        fixture.now = 1_000_000_000; fixture.session.flushLatest()
        fixture.offer(3); fixture.now = 2_000_000_000; fixture.session.flushLatest()
        assertEquals(listOf(2L, 3L), fixture.telemetry().map { it.getLong("frame_number") })
        fixture.session.close()
    }

    @Test fun closeDuringUpgradeRejectsLateOpenAndNeverReplaysQueuedSample() {
        val fixture = Fixture()
        fixture.session.open()
        fixture.offer(1)
        fixture.session.close()
        fixture.upgrade()
        fixture.session.flushLatest()
        assertTrue(fixture.socket.cancelled)
        assertTrue(fixture.socket.messages.isEmpty())
        assertEquals(0, fixture.disconnections)
    }

    @Test fun remoteClosingClearsSamplesAndNotifiesOnlyOnce() {
        val fixture = Fixture()
        fixture.open(); fixture.offer(1)
        fixture.listener.onClosing(fixture.socket, 1000, "")
        fixture.listener.onClosed(fixture.socket, 1000, "")
        fixture.now = 2_000_000_000; fixture.session.flushLatest()
        assertEquals(1, fixture.disconnections)
        assertTrue(fixture.telemetry().isEmpty())
    }

    @Test fun stalledSocketIsCancelledEvenWithoutNewFrames() {
        val fixture = Fixture()
        fixture.open(); fixture.socket.queuedBytes = 100
        fixture.session.flushLatest()
        fixture.now = 5_000_000_000; fixture.session.flushLatest()
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

    private class Fixture {
        val socket = FakeSocket()
        lateinit var listener: WebSocketListener
        var now = 0L
        var disconnections = 0
        var paused = false
        var gpsAllowed = true
        val session = RealtimeSession(
            ApiConnection("https://api.example.test", "synthetic", "destination"),
            RealtimeSession.Context("source", "board", "ride"),
            onState = { _, _ -> }, onDisconnected = { disconnections++; paused = it },
            locationAllowed = { gpsAllowed },
            nowNanos = { now }, openSocket = { _, value -> listener = value; socket }, automaticFlush = false)
        fun open() { session.open(); upgrade() }
        fun upgrade(protocol: String = REALTIME_SUBPROTOCOL) = listener.onOpen(socket, Response.Builder()
            .request(socket.request()).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols")
            .header("Sec-WebSocket-Protocol", protocol).build())
        fun offer(frame: Long) = session.offer("segment", frame, "2030-01-01T00:00:00Z", frame * 100, 12.0, null)
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

package at.themrcodes.ridershub.api

import android.os.SystemClock
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class RealtimeSession(
    private val connection: ApiConnection,
    private val context: Context,
    private val onState: (String, String?) -> Unit,
    private val onDisconnected: (authorizationPaused: Boolean) -> Unit,
    allowed: (ApiConnection, String) -> Boolean = { _, _ -> true },
    private val locationAllowed: () -> Boolean = { true },
    private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val openSocket: (ApiConnection, WebSocketListener) -> WebSocket = ApiClient(allowed)::openRealtime,
    private val automaticFlush: Boolean = true,
) {
    data class Context(val sourceId: String, val boardId: String, val rideId: String)

    private val lock = Any()
    private val streamId = UUID.randomUUID().toString()
    private var sequence = 0L
    private var pending: RealtimeSample? = null
    private var lastFrame: Pair<String, Long>? = null
    private var lastSentAt: Long? = null
    private var queuedSince: Long? = null
    private var terminal = false
    private val sender = Executors.newSingleThreadScheduledExecutor()
    private var socket: WebSocket? = null
    private var opened = false

    fun open() {
        synchronized(lock) {
            if (terminal || socket != null) return
            if (automaticFlush) sender.scheduleWithFixedDelay(::flushLatest, 1, 1, TimeUnit.SECONDS)
        }
        try {
            val created = openSocket(connection, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (response.header("Sec-WebSocket-Protocol") != REALTIME_SUBPROTOCOL) {
                        terminate(true, "Protocol rejected")
                        webSocket.cancel()
                        return
                    }
                    val accepted = synchronized(lock) {
                        if (terminal) false else {
                            socket = webSocket
                            opened = webSocket.send(streamOpened(context.sourceId, context.boardId, context.rideId, streamId).toString())
                            opened
                        }
                    }
                    if (accepted) onState("Live", null)
                    else { webSocket.cancel(); terminate(false, "Disconnected") }
                }

                override fun onMessage(webSocket: WebSocket, text: String) = rejectApplicationMessage(webSocket)
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = rejectApplicationMessage(webSocket)
                private fun rejectApplicationMessage(webSocket: WebSocket) {
                    webSocket.close(1003, "Incoming application messages are not supported")
                    terminate(true, "Protocol rejected")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    terminate(false, "Disconnected")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = terminate(false, "Disconnected")
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val paused = response?.code == 401 || response?.code == 403
                    terminate(paused, if (paused) "Authorization paused" else "Disconnected")
                }
            })
            synchronized(lock) {
                if (terminal) created.cancel() else socket = created
            }
        } catch (_: ApiDeliveryCancelled) {
            terminate(false, "Disconnected")
        } catch (_: Exception) {
            terminate(false, "Disconnected")
        }
    }

    fun offer(captureSegmentId: String, frameNumber: Long, frameAt: String, segmentElapsedMs: Long,
              speedKmh: Double, location: RealtimeLocation?): Unit = synchronized(lock) {
        if (!opened || terminal || !speedKmh.isFinite() || speedKmh < 0 ||
            frameNumber < 1 || segmentElapsedMs < 0 || lastFrame == captureSegmentId to frameNumber) return
        pending = RealtimeSample(context.sourceId, context.boardId, context.rideId, streamId,
            captureSegmentId, 0, frameNumber, frameAt, segmentElapsedMs, speedKmh, location)
    }

    /** Intentional close invalidates callbacks, even when the upgrade has not completed yet. */
    fun close() {
        val old = synchronized(lock) {
            terminal = true
            opened = false
            pending = null
            sender.shutdownNow()
            socket.also { socket = null }
        }
        // Cancel discards buffered data immediately, including data on a stalled connection.
        old?.cancel()
    }

    internal fun flushLatest() {
        var stalled = false
        synchronized(lock) {
            if (!opened || terminal) return
            val webSocket = socket ?: return
            val now = nowNanos()
            val queued = webSocket.queueSize()
            if (queued > 0) {
                if (queuedSince == null) queuedSince = now
                stalled = queued >= MAX_OUTGOING_BYTES || now - requireNotNull(queuedSince) >= STALL_NANOS
            } else queuedSince = null
            if (!stalled) {
                val sample = pending ?: return
                if (lastSentAt?.let { now - it < INTERVAL_NANOS } == true) return
                val encoded = sample.copy(streamSequence = sequence + 1,
                    location = sample.location.takeIf { locationAllowed() }).toJson(now).toString()
                if (queued + encoded.toByteArray(Charsets.UTF_8).size > MAX_OUTGOING_BYTES) {
                    stalled = true
                } else {
                    pending = null
                    if (webSocket.send(encoded)) {
                        sequence += 1
                        lastSentAt = now
                        lastFrame = sample.captureSegmentId to sample.frameNumber
                    } else stalled = true
                }
            }
        }
        if (stalled) terminate(false, "Outgoing buffer stalled")
    }

    private fun terminate(paused: Boolean, status: String) {
        val old = synchronized(lock) {
            if (terminal) return
            terminal = true
            opened = false
            pending = null
            sender.shutdownNow()
            socket.also { socket = null }
        }
        old?.cancel()
        onState(status, null)
        onDisconnected(paused)
    }

    companion object {
        private const val MAX_OUTGOING_BYTES = 64L * 1024L
        private const val INTERVAL_NANOS = 1_000_000_000L
        private const val STALL_NANOS = 5_000_000_000L
    }
}

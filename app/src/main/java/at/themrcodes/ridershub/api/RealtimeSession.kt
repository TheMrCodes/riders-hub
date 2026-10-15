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
    private val allowed: (ApiConnection, String) -> Boolean = { _, _ -> true },
    private val trackingAllowed: () -> Boolean = { true },
    private val locationAllowed: () -> Boolean = { true },
    private val requestLocation: () -> Unit = {},
    private val latestLocation: () -> RealtimeLocation? = { null },
    private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val openSocket: (ApiConnection, WebSocketListener) -> WebSocket = ApiClient(allowed)::openRealtime,
    private val automaticFlush: Boolean = true,
) {
    data class Context(val sourceId: String, val boardId: String, val rideId: String)

    private val lock = Any()
    private val streamId = UUID.randomUUID().toString()
    private var sequence = 0L
    private val pending = ArrayDeque<RealtimeSample>()
    private var nextSendAt = 0L
    private var locationRequested = false
    private var lastFrame: Pair<String, Long>? = null
    private var queuedSince: Long? = null
    private var terminal = false
    private val sender = Executors.newSingleThreadScheduledExecutor()
    private var socket: WebSocket? = null
    private var opened = false

    fun open() {
        synchronized(lock) {
            if (terminal || socket != null) return
            nextSendAt = nowNanos() + INTERVAL_NANOS
            if (automaticFlush) sender.scheduleAtFixedRate({
                runCatching { flushBatch() }.onFailure { terminate(false, "Batch preparation failed") }
            }, 1, 1, TimeUnit.SECONDS)
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
                        if (terminal || !allowed(connection, "realtime")) false else {
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
              speedKmh: Double, receivedAtNanos: Long = nowNanos(), rawHex: String? = null) {
        var overflow = false
        synchronized(lock) {
            if (terminal || !trackingAllowed() || !speedKmh.isFinite() || speedKmh < 0 || frameNumber < 1 || segmentElapsedMs < 0 ||
                lastFrame?.let { it.first == captureSegmentId && frameNumber <= it.second } == true) return
            // Include time spent in the BLE-to-worker handoff in the first batch deadline.
            if (sequence == 0L) nextSendAt = minOf(nextSendAt, receivedAtNanos + INTERVAL_NANOS)
            if (pending.size >= MAX_BATCH_FRAMES) overflow = true else {
                pending.addLast(RealtimeSample(context.sourceId, context.boardId, context.rideId, streamId,
                    captureSegmentId, 0, frameNumber, frameAt, segmentElapsedMs, speedKmh, null, rawHex))
                lastFrame = captureSegmentId to frameNumber
            }
        }
        if (overflow) terminate(false, "Realtime batch overflow")
    }

    /** Intentional close invalidates callbacks, even when the upgrade has not completed yet. */
    fun close() {
        val old = synchronized(lock) {
            terminal = true
            opened = false
            pending.clear()
            sender.shutdownNow()
            socket.also { socket = null }
        }
        // Cancel discards buffered data immediately, including data on a stalled connection.
        old?.cancel()
    }

    internal fun flushBatch() {
        if (!allowed(connection, "realtime")) { terminate(false, "Streaming disabled"); return }
        var stalled = false
        var requestFix = false
        synchronized(lock) {
            if (!trackingAllowed()) {
                pending.clear()
                locationRequested = false
                nextSendAt = nowNanos() + INTERVAL_NANOS
                return
            }
            if (!opened || terminal) return
            val webSocket = socket ?: return
            val now = nowNanos()
            val queued = webSocket.queueSize()
            if (queued > 0) {
                if (queuedSince == null) queuedSince = now
                stalled = queued >= MAX_OUTGOING_BYTES || now - requireNotNull(queuedSince) >= STALL_NANOS
            } else queuedSince = null
            if (!stalled && pending.isNotEmpty()) {
                if (now < nextSendAt) {
                    if (!locationRequested && now >= nextSendAt - LOCATION_LEAD_NANOS) {
                        locationRequested = true
                        requestFix = locationAllowed()
                    }
                } else {
                    // A missing/late GPS fix never delays the five-second send deadline.
                    val location = latestLocation().takeIf { locationRequested && locationAllowed() }
                    val messages = pending.mapIndexed { index, sample ->
                        sample.copy(streamSequence = sequence + index + 1,
                            location = location.takeIf { index == pending.size - 1 }).toJson(now).toString()
                    }
                    if (queued + messages.sumOf { it.toByteArray(Charsets.UTF_8).size } > MAX_OUTGOING_BYTES) {
                        stalled = true
                    } else {
                        pending.clear()
                        for (message in messages) {
                            if (!trackingAllowed()) break
                            if (!allowed(connection, "realtime") || !webSocket.send(message)) { stalled = true; break }
                            sequence++
                        }
                        nextSendAt = now + INTERVAL_NANOS
                        locationRequested = false
                    }
                }
            } else if (!stalled && now >= nextSendAt) {
                nextSendAt = now + INTERVAL_NANOS
                locationRequested = false
            }
        }
        if (requestFix && trackingAllowed()) requestLocation()
        if (stalled) terminate(false, "Outgoing buffer stalled")
    }

    private fun terminate(paused: Boolean, status: String) {
        val old = synchronized(lock) {
            if (terminal) return
            terminal = true
            opened = false
            pending.clear()
            sender.shutdownNow()
            socket.also { socket = null }
        }
        old?.cancel()
        onState(status, null)
        onDisconnected(paused)
    }

    companion object {
        private const val MAX_OUTGOING_BYTES = 1024L * 1024L
        private const val MAX_BATCH_FRAMES = 2048
        private const val LOCATION_LEAD_NANOS = 2_000_000_000L
        private const val INTERVAL_NANOS = 5_000_000_000L
        private const val STALL_NANOS = 5_000_000_000L
    }
}

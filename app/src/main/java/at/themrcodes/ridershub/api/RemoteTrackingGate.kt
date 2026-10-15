package at.themrcodes.ridershub.api

/** Process-local tracking window: usable remote telemetry plus a bounded disconnect grace. */
internal class RemoteTrackingGate(private val nowMs: () -> Long = android.os.SystemClock::elapsedRealtime) {
    data class Window(val generation: Long, val owner: String, val rideId: String, val logPath: String)
    private var owner: String? = null
    private var generation = 0L
    private var window: Window? = null
    private var stopAtMs: Long? = null
    @Synchronized fun connected(owner: String) {
        this.owner = owner; generation++; window = null; stopAtMs = null
    }
    @Synchronized fun observe(owner: String, rideId: String, logPath: String): Window? {
        if (this.owner != owner) return null
        if (window?.let { it.rideId != rideId || it.logPath != logPath } == true) { generation++; window = null }
        return window ?: Window(generation, owner, rideId, logPath).also { window = it }
    }
    @Synchronized fun disconnected(owner: String): Boolean {
        if (this.owner != owner) return false
        this.owner = null; stopAtMs = nowMs() + GRACE_MS
        return true
    }
    @Synchronized fun endRide(rideId: String) {
        if (window?.rideId != rideId) return
        owner = null; window = null; stopAtMs = null; generation++
    }
    @Synchronized fun current(): Window? = window.takeIf { stopAtMs?.let { nowMs() < it } != false }
    @Synchronized fun isCurrent(candidate: Window?): Boolean = candidate != null && candidate == current()
    companion object {
        const val GRACE_MS = 5_000L
        val shared = RemoteTrackingGate()
    }
}

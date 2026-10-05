package at.themrcodes.ridershub.api

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import at.themrcodes.ridershub.AppStateStore
import at.themrcodes.ridershub.BuildConfig
import at.themrcodes.ridershub.protocol.TelemetryFrame
import at.themrcodes.ridershub.session.RangeEstimate
import at.themrcodes.ridershub.session.RideStore
import at.themrcodes.ridershub.session.RideSummary
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class ApiIntegration private constructor(context: Context) {
    private val app = context.applicationContext
    private val store = ApiStore(app)
    private val database = store.database()
    private val client = ApiClient(store::allows)
    private val state = AppStateStore(app)
    private val rideStore get() = RideStore.get(app)
    private val network = Executors.newSingleThreadExecutor { Thread(it, "riders-hub-api") }
    private val main = Handler(Looper.getMainLooper())
    private val cadence = Executors.newSingleThreadScheduledExecutor { Thread(it, "riders-hub-api-cadence") }
    private val snapshotInFlight = AtomicBoolean(false)
    private val lock = Any()
    private var realtime: RealtimeSession? = null
    private var realtimeRide: String? = null
    private var reconnectAttempt = 0
    private var nextRealtimeAttemptElapsedMs = 0L
    private var realtimeGeneration = 0L
    private val lastSnapshotElapsedMs = AtomicLong(-SNAPSHOT_INTERVAL_MS)
    private val discoveryGeneration = AtomicLong(0)
    @Volatile private var finalizingRide: String? = null

    init {
        cadence.scheduleWithFixedDelay({
            // One malformed observation must not permanently terminate the scheduler.
            runCatching { tick() }.onFailure { state.setError("API live update failed") }
        }, 1, 1, TimeUnit.SECONDS)
    }

    fun snapshot(): ApiSnapshot = store.snapshot()

    fun prepareOidcEnrollment(baseUrl: String, result: (Result<OidcAuthConfig>) -> Unit) {
        val normalized = apiUrls(baseUrl).base.also {
            require(it.isHttps) { "SSO enrollment requires HTTPS" }
        }.toString().removeSuffix("/")
        val generation = discoveryGeneration.incrementAndGet()
        state.clearError()
        network.execute {
            val outcome = runCatching { client.authConfig(normalized) }
            main.post {
                if (generation == discoveryGeneration.get()) result(outcome)
            }
        }
    }

    fun completeOidcEnrollment(baseUrl: String, accessToken: String) {
        val normalized = apiUrls(baseUrl).base.also {
            require(it.isHttps) { "SSO enrollment requires HTTPS" }
        }.toString().removeSuffix("/")
        val generation = discoveryGeneration.incrementAndGet()
        state.clearError()
        network.execute {
            runCatching {
                val registration = client.registerDevice(normalized, accessToken, database.sourceId())
                synchronized(ApiStore.CONFIG_LOCK) {
                    if (generation != discoveryGeneration.get()) return@execute
                    ApiClient.cancelAllTransports()
                    store.configureOidc(normalized, registration.deviceToken)
                }
                val candidate = requireNotNull(store.connection())
                val (json, capabilities) = client.capabilities(candidate)
                synchronized(ApiStore.CONFIG_LOCK) {
                    if (generation != discoveryGeneration.get() || store.connection() != candidate) return@execute
                    store.saveCapabilities(candidate.destinationId, json, capabilities)
                }
                resetLiveTransports()
                store.connection()?.let { acceptCapabilities(it, capabilities) }
            }.onFailure {
                if (generation == discoveryGeneration.get()) {
                    state.setError("SSO device enrollment failed; sign in again or check the server configuration")
                }
            }
        }
    }

    fun refreshCapabilities() {
        val connection = store.connection() ?: return
        val generation = discoveryGeneration.incrementAndGet()
        network.execute {
            runCatching { client.capabilities(connection) }
                .onSuccess { (json, capabilities) ->
                    synchronized(ApiStore.CONFIG_LOCK) {
                        if (generation != discoveryGeneration.get() || store.connection() != connection) return@onSuccess
                        ApiClient.cancelAllTransports()
                        store.saveCapabilities(connection.destinationId, json, capabilities)
                    }
                    resetLiveTransports()
                    store.connection()?.let { acceptCapabilities(it, capabilities) }
                }
                .onFailure {
                    if (store.connection() == connection) database.setStatus(connection.destinationId, "metrics_status", "Discovery failed")
                }
        }
    }

    private fun acceptCapabilities(
        connection: ApiConnection,
        capabilities: ApiCapabilities,
    ) {
        if (connection.serverId != capabilities.serverId || store.connection() != connection) return
        database.resumeUnauthorized(connection.deliveryScope)
        database.setStatus(connection.destinationId, "metrics_status",
            if (capabilities.metrics != null) "Ready" else "Unsupported")
        database.setStatus(connection.destinationId, "realtime_status",
            if (capabilities.realtime != null) "Ready" else "Unsupported")
        database.setStatus(connection.destinationId, "analytics_status", when {
            capabilities.analytics == null -> "Unsupported"
            store.snapshot().analyticsApproved -> "Ready"
            else -> "Approval required"
        })
        database.setStatus(connection.destinationId, "charger_analytics_status", when {
            1 !in (capabilities.analytics?.coaFormatVersions ?: emptySet()) -> "Unsupported"
            store.snapshot().analyticsApproved -> "Ready"
            else -> "Approval required"
        })
        if (capabilities.realtime?.gps != true) stopGps("Server does not advertise GPS")
        ApiDeliveryWorker.enqueue(app, connection.destinationId)
        if (store.snapshot().analyticsApproved) ApiArchiveWorker.enqueue(app)
        lastSnapshotElapsedMs.set(-SNAPSHOT_INTERVAL_MS)
    }

    fun setEnabled(enabled: Boolean) {
        discoveryGeneration.incrementAndGet()
        synchronized(ApiStore.CONFIG_LOCK) {
            store.setEnabled(enabled)
            if (!enabled) ApiClient.cancelAllTransports()
        }
        if (!enabled) {
            resetLiveTransports()
            stopGps("API disabled")
        } else recoverDelivery()
    }

    fun clearConfiguration() {
        setEnabled(false)
        store.clearConfiguration()
    }

    fun approveAnalytics(approved: Boolean) {
        synchronized(ApiStore.CONFIG_LOCK) {
            store.approveAnalytics(approved)
            if (!approved) ApiClient.cancelAllTransports()
        }
        if (approved) ApiArchiveWorker.enqueue(app)
    }

    fun resyncArchives() {
        synchronized(ApiStore.CONFIG_LOCK) {
            ApiClient.cancelAllTransports()
            store.resyncArchives()
        }
        ApiArchiveWorker.enqueue(app)
    }

    fun setGpsEnabled(enabled: Boolean) {
        store.setGpsEnabled(enabled)
        if (!enabled) stopGps("GPS disabled")
    }

    fun reportGpsUnavailable(reason: String) {
        store.setGpsEnabled(false)
        stopGps(reason)
    }

    fun onRideStarted(ride: RideSummary) {
        val snapshot = store.snapshot()
        val connection = store.connection() ?: return
        if (!snapshot.enabled || snapshot.capabilities?.metrics == null) return
        if (connection.serverId == null) return
        val ids = exportIds(ride)
        val observedAt = ride.lastFrameAt ?: ride.startedAt
        // Build outside the SQLite transaction: ride locks must never be acquired from a DB transaction.
        val data = metricSnapshot(ride, active = true).copy(startedAt = observedAt)
        database.enqueueLifecycle(
            connection.deliveryScope,
            "ride.started:${ride.id}",
        ) { sequence, messageId ->
            metricsEnvelope(
                database.sourceId(), ids.first, ids.second, BuildConfig.VERSION_NAME,
                sequence, MetricsKind.RIDE_STARTED, observedAt, data, messageId,
            )
        }
        ApiDeliveryWorker.enqueue(app, connection.destinationId)
    }

    fun onFrame(
        ride: RideSummary,
        frame: TelemetryFrame,
        captureSegmentId: String,
        frameNumber: Long,
        frameAt: Instant,
        segmentElapsedMs: Long,
    ) {
        if (!frame.crcValid) return
        val config = store.snapshot()
        val connection = store.connection() ?: return
        if (!config.enabled || connection.serverId == null || finalizingRide == ride.id) return
        val ids = exportIds(ride)
        if (store.allows(connection, "realtime")) {
            ensureRealtime(connection, ids.first, ids.second)
            synchronized(lock) {
                realtime?.offer(
                    captureSegmentId, frameNumber, frameAt.toString(), segmentElapsedMs,
                    frame.speedKmh, if (config.gpsEnabled) LocationFixState.latest() else null,
                )
            }
        }
        sendDueSnapshot(connection, ride, ids)
        if (config.gpsEnabled && config.capabilities?.realtime?.gps == true) {
            RideLocationService.start(app, ride.logFile)
        }
    }

    /** Stops live producers before the ride writer is drained and archived. */
    fun onRideFinalizing(rideId: String) {
        finalizingRide = rideId
        synchronized(lock) {
            realtimeGeneration += 1
            realtime?.close()
            realtime = null
            realtimeRide = null
        }
        stopGps("Ride finalized")
    }

    fun onRideEnded(ride: RideSummary, range: RangeEstimate?) {
        val config = store.snapshot()
        val connection = store.connection()
        val ids = exportIds(ride)
        onRideFinalizing(ride.id)
        if (ride.boardFrameCount > 0 && connection?.serverId != null && config.enabled && config.capabilities?.metrics != null) {
            val occurred = ride.endedAt ?: Instant.now().toString()
            val data = metricSnapshot(ride, active = false, range = range, finalizedAt = occurred).copy(endedAt = occurred)
            database.enqueueLifecycle(
                connection.deliveryScope,
                "ride.ended:${ride.id}",
            ) { sequence, messageId ->
                metricsEnvelope(
                    database.sourceId(), ids.first, ids.second, BuildConfig.VERSION_NAME,
                    sequence, MetricsKind.RIDE_ENDED, occurred,
                    data,
                    messageId,
                )
            }
            ApiDeliveryWorker.enqueue(app, connection.destinationId)
        }
        // The archive finalizer enqueues after the updated partition is committed.
    }

    fun manualSync() {
        val config = store.snapshot()
        val connection = store.connection() ?: return
        val ride = rideStore.activeRide() ?: return
        if (config.enabled && config.capabilities?.metrics != null) {
            val ids = exportIds(ride)
            sendSnapshot(connection, ride, ids.first, ids.second)
        }
    }

    /** Reattaches persistent WorkManager delivery after process death around a transition. */
    fun recoverDelivery() {
        // Application startup and the settings toggle run on the main thread. Recovery can
        // drain a ride writer, so it must not block either caller.
        network.execute {
            runCatching {
                rideStore.finalizeExpired()
                val connection = store.connection() ?: return@execute
                val config = store.snapshot()
                if (!config.enabled) return@execute
                ApiDeliveryWorker.enqueue(app, connection.destinationId)
                if (config.analyticsApproved) ApiArchiveWorker.enqueue(app)
            }.onFailure { state.setError("API delivery recovery failed; retained data will be retried") }
        }
    }

    private fun sendSnapshot(connection: ApiConnection, ride: RideSummary, boardId: String, rideId: String) {
        if (!store.allows(connection, "metrics") || ride.boardFrameCount == 0L || finalizingRide == ride.id) return
        if (!snapshotInFlight.compareAndSet(false, true)) return
        val payload = try {
            // Assign sequence while the observed ride is still active. Finalization cannot race
            // a stale live snapshot into a higher sequence than ride.ended.
            rideStore.withActiveRide(ride.id) { current, range ->
                metricsEnvelope(database.sourceId(), boardId, rideId, BuildConfig.VERSION_NAME,
                    database.nextSequence(), MetricsKind.SNAPSHOT,
                    current.lastFrameAt ?: Instant.now().toString(),
                    metricSnapshot(current, active = true, range = range)).toString()
            }
        } catch (error: Exception) {
            snapshotInFlight.set(false)
            throw error
        }
        if (payload == null) { snapshotInFlight.set(false); return }
        network.execute {
            try {
                runCatching { client.postMetric(connection, payload) }
                .onSuccess {
                    if (store.connection() == connection) database.setStatus(connection.destinationId, "metrics_status", "Delivered")
                }
                .onFailure { error ->
                    if (store.allows(connection, "metrics")) {
                        if (error is ApiHttpException && error.unauthorized) database.pauseChannel(connection.deliveryScope, "metrics")
                        if (error is ApiHttpException) error.retryAfterMillis()?.let {
                            database.deferChannel(connection.deliveryScope, "metrics", retryAt(it))
                        }
                        database.setStatus(connection.destinationId, "metrics_status",
                            if (error is ApiHttpException && error.unauthorized) "Authorization paused" else "Snapshot failed")
                    }
                }
            } finally {
                snapshotInFlight.set(false)
            }
        }
    }

    private fun tick() {
        rideStore.finalizeExpired()
        val config = store.snapshot()
        val connection = store.connection() ?: return
        if (!config.enabled || connection.serverId == null) return
        val ride = rideStore.activeRide() ?: return
        if (ride.boardFrameCount == 0L || finalizingRide == ride.id) return
        val ids = exportIds(ride)
        sendDueSnapshot(connection, ride, ids)
        // Reconnect during BLE grace too; a new board frame is not required to retry the socket.
        if (store.allows(connection, "realtime")) ensureRealtime(connection, ids.first, ids.second)
        if (config.gpsEnabled && !RideLocationService.hasRequiredPermissions(app)) {
            reportGpsUnavailable("Precise and background location permissions are required")
        } else if (config.gpsEnabled && config.capabilities?.realtime?.gps == true) {
            RideLocationService.start(app, ride.logFile)
        }
    }

    private fun sendDueSnapshot(connection: ApiConnection, ride: RideSummary, ids: Pair<String, String>) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastSnapshotElapsedMs.get()
        if (now - previous >= SNAPSHOT_INTERVAL_MS && lastSnapshotElapsedMs.compareAndSet(previous, now)) {
            sendSnapshot(connection, ride, ids.first, ids.second)
        }
    }

    private fun resetLiveTransports() {
        synchronized(lock) {
            realtimeGeneration += 1
            realtime?.close()
            realtime = null
            realtimeRide = null
            reconnectAttempt = 0
            nextRealtimeAttemptElapsedMs = 0
        }
        stopGps("GPS awaiting active ride")
    }

    private fun ensureRealtime(connection: ApiConnection, boardId: String, rideId: String) {
        synchronized(lock) {
            if (realtimeRide == rideId && realtime != null) return
            if (SystemClock.elapsedRealtime() < nextRealtimeAttemptElapsedMs) return
            realtimeGeneration += 1
            realtime?.close()
            realtimeRide = rideId
            val generation = realtimeGeneration
            val session = RealtimeSession(
                connection,
                RealtimeSession.Context(database.sourceId(), boardId, rideId),
                allowed = store::allows,
                locationAllowed = { store.snapshot().gpsEnabled && RideLocationService.hasRequiredPermissions(app) },
                onState = { status, error ->
                    val current = synchronized(lock) {
                        if (generation != realtimeGeneration) false else {
                            if (status == "Live") reconnectAttempt = 0
                            true
                        }
                    }
                    if (current) database.setStatus(connection.destinationId, "realtime_status", status, error)
                },
                onDisconnected = { authorizationPaused ->
                    synchronized(lock) {
                        if (generation != realtimeGeneration) return@synchronized
                        realtime = null
                        if (authorizationPaused) {
                            nextRealtimeAttemptElapsedMs = Long.MAX_VALUE
                        } else {
                            reconnectAttempt += 1
                            val delay = min(30_000L, 1_000L shl reconnectAttempt.coerceAtMost(5))
                            nextRealtimeAttemptElapsedMs = SystemClock.elapsedRealtime() + delay
                        }
                    }
                },
            )
            realtime = session
            session.open()
        }
    }

    private fun metricSnapshot(
        ride: RideSummary,
        active: Boolean,
        range: RangeEstimate? = null,
        finalizedAt: String? = null,
    ): MetricSnapshot {
        val appState = state.snapshot()
        val estimate = range
        return MetricSnapshot(
            connection = connectionWireState(appState.connection, active),
            rideActive = active,
            tripDistanceKm = ride.distanceKm,
            odometerKm = ride.odometerEndKm,
            batteryReportedPercent = ride.boardBatteryEnd,
            batteryEstimatedPercent = ride.estimatedBatteryPercent,
            estimatedRangeKm = estimate?.remainingKm,
            lastBoardObservationAt = ride.lastFrameAt,
            finalizedAt = finalizedAt,
        )
    }

    private fun exportIds(ride: RideSummary): Pair<String, String> =
        database.exportId("board", ride.localBoardId ?: "unknown") to database.exportId("ride", ride.id)

    private fun stopGps(reason: String) {
        RideLocationService.stop(app)
        store.connection()?.let { database.setStatus(it.destinationId, "gps_status", reason) }
        LocationFixState.clear()
    }

    companion object {
        private const val SNAPSHOT_INTERVAL_MS = 10_000L
        @Volatile private var instance: ApiIntegration? = null
        fun get(context: Context): ApiIntegration = instance ?: synchronized(this) {
            instance ?: ApiIntegration(context).also { instance = it }
        }
    }
}

internal fun connectionWireState(value: String, rideActive: Boolean): String = when {
    !rideActive -> "standby"
    value.startsWith("Disconnected", ignoreCase = true) || value.startsWith("Idle", ignoreCase = true) ||
        value.contains("reconnect", ignoreCase = true) -> "reconnecting"
    value.startsWith("Connecting", ignoreCase = true) || value.startsWith("Connected", ignoreCase = true) -> "connecting"
    rideActive -> "live"
    else -> "standby"
}

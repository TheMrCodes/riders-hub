package at.themrcodes.ridershub.homeassistant

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import at.themrcodes.ridershub.BuildConfig
import at.themrcodes.ridershub.session.RangeEstimate
import at.themrcodes.ridershub.session.RideSummary
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HomeAssistantIntegration private constructor(context: Context) {
    private val store = HomeAssistantStore(context)
    private val client = HomeAssistantClient()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "riders-hub-home-assistant").apply { isDaemon = true }
    }
    private val deliveryLock = Any()
    private val encryptionUpgradeQueued = AtomicBoolean(false)
    @Volatile private var busy = false
    @Volatile private var syncing = false
    private var lastQueuedBattery: Int? = null
    private var lastQueuedTripAtElapsedMs = 0L
    private var lastDeliveryStartedAtElapsedMs = 0L

    init {
        ensureEncryption()
    }

    fun snapshot(): HomeAssistantSnapshot = store.snapshot(busy, syncing)

    fun setEnabled(enabled: Boolean) {
        store.setEnabled(enabled)
        if (enabled) ensureEncryption()
    }

    fun connect(instanceUrl: String, accessToken: String) {
        if (busy) return
        busy = true
        store.clearRequestError()
        store.setStatus("Connecting…")
        executor.execute {
            try {
                val registration = client.register(
                    instanceUrl = instanceUrl,
                    accessToken = accessToken,
                    deviceId = store.deviceId(),
                    appVersion = BuildConfig.VERSION_NAME,
                )
                val connection = HomeAssistantConnection(
                    webhookUrl = registration.webhookUrl,
                    encryptionSecret = registration.encryptionSecret,
                )
                store.connect(connection)
                if (connection.encryptionSecret == null) {
                    store.recordRequestError(ENCRYPTION_UNAVAILABLE_ERROR)
                    ensureEncryption()
                } else {
                    runCatching { client.registerSensors(connection) }
                        .onFailure { store.recordRequestError("Entity setup failed: ${safeMessage(it)}") }
                }
            } catch (error: Exception) {
                store.recordRequestError("Connection failed: ${safeMessage(error)}")
            } finally {
                busy = false
            }
        }
    }

    fun saveWebhookUrl(webhookUrl: String) {
        runCatching { HomeAssistantUrls.validateWebhookUrl(webhookUrl) }
            .onSuccess {
                store.updateWebhookUrl(it)
                store.clearRequestError()
            }
            .onFailure { store.setStatus("Webhook save failed: ${safeMessage(it)}") }
    }

    fun disconnect() {
        synchronized(deliveryLock) {
            lastQueuedBattery = null
            lastQueuedTripAtElapsedMs = 0L
            lastDeliveryStartedAtElapsedMs = 0L
        }
        syncing = false
        store.disconnect()
    }

    fun sync(
        boardBatteryPercent: Int?,
        estimatedRangeKm: Double?,
        currentTripKm: Double,
        inUse: Boolean,
        updatedAt: String,
    ) {
        if (syncing) return
        val initial = store.snapshot()
        if (!initial.enabled || !initial.connected) {
            store.recordRequestError("Home Assistant is not connected")
            return
        }
        syncing = true
        executor.execute {
            try {
                deliver(
                    update = HomeAssistantUpdate(
                        boardBatteryPercent = boardBatteryPercent,
                        estimatedRangeKm = estimatedRangeKm,
                        currentTripKm = currentTripKm,
                        inUse = inUse,
                        updatedAt = updatedAt,
                    ),
                    event = null,
                )
            } finally {
                syncing = false
            }
        }
    }

    fun onRideStarted(ride: RideSummary) {
        val startedAt = ride.lastFrameAt ?: ride.startedAt
        synchronized(deliveryLock) {
            lastQueuedBattery = ride.boardBatteryEnd ?: lastQueuedBattery
            lastQueuedTripAtElapsedMs = SystemClock.elapsedRealtime()
        }
        queue(
            update = HomeAssistantUpdate(
                boardBatteryPercent = ride.boardBatteryEnd,
                estimatedRangeKm = null,
                currentTripKm = ride.distanceKm,
                inUse = true,
                updatedAt = startedAt,
            ),
            event = HomeAssistantTripEvent(true, startedAt, ride.distanceKm),
            force = true,
        )
    }

    fun onTelemetry(ride: RideSummary, rangeEstimate: () -> RangeEstimate) {
        val battery = ride.boardBatteryEnd
        val nowElapsed = SystemClock.elapsedRealtime()
        val shouldSend = synchronized(deliveryLock) {
            val batteryChanged = battery != null && battery != lastQueuedBattery
            if (
                isRunningUpdateDue(
                    nowElapsedMs = nowElapsed,
                    lastQueuedAtElapsedMs = lastQueuedTripAtElapsedMs,
                    lastRequestStartedAtElapsedMs = lastDeliveryStartedAtElapsedMs,
                    batteryChanged = batteryChanged,
                )
            ) {
                lastQueuedBattery = battery ?: lastQueuedBattery
                lastQueuedTripAtElapsedMs = nowElapsed
                true
            } else false
        }
        if (!shouldSend) return
        queue(
            update = HomeAssistantUpdate(
                boardBatteryPercent = battery,
                estimatedRangeKm = rangeEstimate().remainingKm,
                currentTripKm = ride.distanceKm,
                inUse = true,
                updatedAt = ride.lastFrameAt ?: Instant.now().toString(),
            ),
        )
    }

    fun onRideEnded(ride: RideSummary, rangeEstimate: RangeEstimate) {
        synchronized(deliveryLock) {
            lastQueuedBattery = ride.boardBatteryEnd ?: lastQueuedBattery
            lastQueuedTripAtElapsedMs = SystemClock.elapsedRealtime()
        }
        val endedAt = ride.endedAt ?: ride.lastFrameAt ?: Instant.now().toString()
        queue(
            update = HomeAssistantUpdate(
                boardBatteryPercent = ride.boardBatteryEnd,
                estimatedRangeKm = rangeEstimate.remainingKm,
                currentTripKm = ride.distanceKm,
                inUse = false,
                updatedAt = endedAt,
            ),
            event = HomeAssistantTripEvent(false, endedAt, ride.distanceKm),
            force = true,
        )
    }

    private fun queue(
        update: HomeAssistantUpdate,
        event: HomeAssistantTripEvent? = null,
        force: Boolean = false,
    ) {
        val initial = store.snapshot()
        if (!initial.enabled || !initial.connected) return
        if (!force && !update.inUse) return
        if (!initial.payloadEncrypted) ensureEncryption()
        executor.execute {
            deliver(update, event)
        }
    }

    private fun markDeliveryStarted() {
        synchronized(deliveryLock) {
            lastDeliveryStartedAtElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    private fun deliver(
        update: HomeAssistantUpdate,
        event: HomeAssistantTripEvent?,
    ) {
        val current = store.snapshot()
        val connection = store.connection()
        if (!current.enabled || connection == null) return
        if (connection.encryptionSecret == null) {
            store.recordRequestError(ENCRYPTION_UNAVAILABLE_ERROR)
            return
        }
        try {
            markDeliveryStarted()
            val result = client.updateSensors(connection, update)
            if (event != null) client.fireTripEvent(connection, event)
            if (store.connection() == connection) store.recordDelivery(result.disabledCount)
        } catch (error: Exception) {
            if (store.connection() != connection) return
            if (error is HomeAssistantHttpException && error.statusCode == 410) {
                store.disconnect("Registration was removed in Home Assistant")
            } else {
                store.recordRequestError(safeMessage(error))
            }
        }
    }

    /**
     * Existing pre-encryption registrations have no retained secret. Home Assistant's
     * enable_encryption command returns it exactly once, so persist it synchronously
     * before allowing another webhook request.
     */
    private fun ensureEncryption() {
        val initial = store.snapshot()
        val connection = store.connection()
        if (!initial.enabled || connection == null || connection.encryptionSecret != null) return
        if (!encryptionUpgradeQueued.compareAndSet(false, true)) return
        store.setStatus("Enabling webhook encryption…")
        executor.execute {
            try {
                val secret = client.enableEncryption(connection.webhookUrl)
                val current = store.connection()
                if (current != null && current.encryptionSecret == null) {
                    store.connect(current.copy(encryptionSecret = secret))
                    store.clearRequestError()
                }
            } catch (error: Exception) {
                val status = if (
                    error is HomeAssistantHttpException &&
                    error.errorCode == "encryption_already_enabled"
                ) {
                    "Encryption key unavailable; disconnect and reconnect"
                } else {
                    "Encryption setup failed: ${safeMessage(error)}"
                }
                if (store.connection()?.encryptionSecret == null) store.recordRequestError(status)
            } finally {
                encryptionUpgradeQueued.set(false)
            }
        }
    }

    private fun safeMessage(error: Throwable): String = when (error) {
        is HomeAssistantHttpException -> error.errorCode ?: "HTTP ${error.statusCode}"
        is HomeAssistantProtocolException -> error.message ?: "Invalid response"
        is IllegalArgumentException -> error.message ?: "Invalid configuration"
        is java.io.IOException -> error.message ?: "Network error"
        else -> error.javaClass.simpleName
    }

    companion object {
        @Volatile
        @SuppressLint("StaticFieldLeak")
        private var instance: HomeAssistantIntegration? = null

        fun get(context: Context): HomeAssistantIntegration = instance ?: synchronized(this) {
            instance ?: HomeAssistantIntegration(context.applicationContext).also { instance = it }
        }
    }
}

internal const val TRIP_UPDATE_INTERVAL_MS = 10_000L
internal const val ENCRYPTION_UNAVAILABLE_ERROR =
    "Webhook encryption is unavailable; disconnect and reconnect Home Assistant"

internal fun isRunningUpdateDue(
    nowElapsedMs: Long,
    lastQueuedAtElapsedMs: Long,
    lastRequestStartedAtElapsedMs: Long,
    batteryChanged: Boolean,
): Boolean = batteryChanged || remainingRunningUpdateDelayMs(
    nowElapsedMs = nowElapsedMs,
    lastQueuedAtElapsedMs = lastQueuedAtElapsedMs,
    lastRequestStartedAtElapsedMs = lastRequestStartedAtElapsedMs,
) == 0L

internal fun remainingRunningUpdateDelayMs(
    nowElapsedMs: Long,
    lastQueuedAtElapsedMs: Long,
    lastRequestStartedAtElapsedMs: Long,
): Long {
    val cadenceStartedAt = maxOf(lastQueuedAtElapsedMs, lastRequestStartedAtElapsedMs)
    return (TRIP_UPDATE_INTERVAL_MS - (nowElapsedMs - cadenceStartedAt)).coerceAtLeast(0L)
}

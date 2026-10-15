package at.themrcodes.ridershub.api

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.*
import android.os.*
import androidx.core.content.ContextCompat
import at.themrcodes.ridershub.MainActivity
import at.themrcodes.ridershub.R
import at.themrcodes.ridershub.session.RideStore
import java.time.Instant

/** Phone sensors and local recording run on a dedicated worker, never the UI thread. */
class RideMotionService : Service(), SensorEventListener {
    private val worker = HandlerThread("riders-hub-motion")
    private lateinit var handler: Handler
    private lateinit var manager: SensorManager
    private lateinit var store: ApiStore
    private lateinit var journal: MotionStore
    private var connection: ApiConnection? = null
    private var localRide: String? = null
    private var batch: MotionBatch? = null
    private var window: RemoteTrackingGate.Window? = null
    @Volatile private var destroyed = false
    private var stopping = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var nextFlushAt = 0L
    private val flush = object : Runnable {
        override fun run() {
            if (destroyed || stopping) return
            if (!stillAllowed()) { finish(); return }
            wakeLock?.acquire(30_000)
            if (persist()) {
                nextFlushAt = maxOf(nextFlushAt + 5_000, SystemClock.uptimeMillis())
                handler.postAtTime(this, nextFlushAt)
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        worker.start(); handler = Handler(worker.looper)
        manager = getSystemService(SensorManager::class.java)
        store = ApiStore(this); journal = MotionStore.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Ride motion recording", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            handler.post {
                synchronized(ApiStore.CONFIG_LOCK) {
                    (connection ?: store.connection())?.let { journal.approve(it.deliveryScope, false); journal.status(it.deliveryScope, "Off") }
                    ApiClient.cancelMotionTransports()
                }
                finish()
            }
            return START_NOT_STICKY
        }
        try { startForeground(NOTIFICATION_ID, notification()) }
        catch (_: RuntimeException) { stopSelf(); return START_NOT_STICKY }
        val requestedRide = intent?.getStringExtra("ride_id")
        handler.post {
            if (destroyed || stopping || batch != null) return@post
            window = RemoteTrackingGate.shared.current()?.takeIf {
                it.generation == intent?.getLongExtra("remote_generation", -1) && it.rideId == requestedRide
            }
            if (window == null) { finish(); return@post }
            val ride = RideStore.get(this).activeRide()?.takeIf { it.id == requestedRide }
            val candidate = store.connection()?.takeIf { it.serverId != null }
            if (ride == null || candidate == null) { finish(); return@post }
            connection = candidate; localRide = ride.id
            if (!stillAllowed()) { finish(); return@post }
            try {
                val available = listOf(Sensor.TYPE_ACCELEROMETER to "accelerometer", Sensor.TYPE_GYROSCOPE to "gyroscope")
                    .mapNotNull { (type, name) -> manager.getDefaultSensor(type)?.let { it to name } }
                    .filter { (sensor, _) -> manager.registerListener(this, sensor, MotionBatch.SAMPLE_PERIOD_US, 0, handler) }
                if (available.isEmpty()) {
                    journal.status(candidate.deliveryScope, "Phone motion sensors unavailable")
                    journal.approve(candidate.deliveryScope, false)
                    finish(); return@post
                }
                val origin = SystemClock.elapsedRealtimeNanos()
                batch = MotionBatch(store.database().sourceId(),
                    store.database().exportId("board", ride.localBoardId ?: "unknown"),
                    store.database().exportId("ride", ride.id), Instant.now().toString(), origin, available.map { it.second })
                journal.status(candidate.deliveryScope, "Recording ${available.joinToString { it.second }}")
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ridershub:motion").apply {
                        setReferenceCounted(false); acquire(30_000)
                    }
                nextFlushAt = SystemClock.uptimeMillis() + 5_000
                handler.postAtTime(flush, nextFlushAt)
            } catch (_: Exception) { recordingFailed() }
        }
        return START_NOT_STICKY
    }
    private fun stillAllowed(): Boolean {
        if (!RemoteTrackingGate.shared.isCurrent(window)) return false
        val candidate = connection ?: return false
        val config = store.snapshot()
        return candidate == store.connection() && config.enabled && config.motionEnabled && config.capabilities?.motion != null &&
            RideStore.get(this).activeRide()?.id == localRide
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (destroyed || stopping) return
        if (!RemoteTrackingGate.shared.isCurrent(window)) { finish(); return }
        val name = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> "accelerometer"
            Sensor.TYPE_GYROSCOPE -> "gyroscope"
            else -> return
        }
        try {
            // SensorEvent values are reused by Android; MotionBatch copies the three axes.
            batch?.add(name, event.timestamp, event.values, event.accuracy)
            if (batch?.size == MotionBatch.MAX_SAMPLES) persist()
        } catch (_: Exception) { recordingFailed() }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    private fun persist(): Boolean = try {
        batch?.drain()?.let { value ->
            val scope = requireNotNull(connection).deliveryScope
            journal.record(scope, value.getString("batch_id"), value.toString())
            MotionDelivery.request(applicationContext)
        }
        true
    } catch (_: Exception) { recordingFailed(); false }
    private fun recordingFailed() {
        connection?.let {
            journal.status(it.deliveryScope, "Motion recording stopped: sensor or storage failure")
            journal.approve(it.deliveryScope, false)
        }
        batch = null
        finish()
    }
    private fun finish() {
        if (stopping) return
        stopping = true
        handler.removeCallbacks(flush)
        manager.unregisterListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        persist()
        batch = null
        if (connection != null && RemoteTrackingGate.shared.isCurrent(window)) MotionDeliveryWorker.enqueue(applicationContext)
        stopSelf()
    }
    override fun onDestroy() {
        destroyed = true
        handler.post {
            try { finish() } finally { worker.quitSafely() }
        }
        synchronized(START_LOCK) { requested = null }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    internal fun notification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification).setContentTitle("Riders Hub motion recording")
        .setContentText("Recording phone accelerometer and gyroscope in five-second batches")
        .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
        .setContentIntent(PendingIntent.getActivity(this, NOTIFICATION_ID, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(Notification.Action.Builder(null, "Stop motion recording", PendingIntent.getService(this, NOTIFICATION_ID,
            Intent(this, RideMotionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()).build()
    companion object {
        private const val CHANNEL = "ride_motion"
        private const val NOTIFICATION_ID = 2113
        internal const val ACTION_STOP = "at.themrcodes.ridershub.api.STOP_MOTION"
        private val START_LOCK = Any()
        private var requested: String? = null
        private var nextStart = 0L
        internal fun start(context: Context, localRideId: String, connection: ApiConnection, window: RemoteTrackingGate.Window) {
            if (!RemoteTrackingGate.shared.isCurrent(window)) return
            val key = "$localRideId:${connection.deliveryScope}:${connection.revision}:${window.generation}"
            synchronized(START_LOCK) {
                if (requested == key || SystemClock.elapsedRealtime() < nextStart) return
                requested = key
            }
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RideMotionService::class.java)
                .putExtra("ride_id", localRideId).putExtra("remote_generation", window.generation)) }.onFailure {
                synchronized(START_LOCK) { requested = null; nextStart = SystemClock.elapsedRealtime() + 30_000 }
                MotionStore.get(context).status(connection.deliveryScope, "Open Riders Hub to start motion recording")
            }
        }
        fun stop(context: Context) {
            synchronized(START_LOCK) { requested = null }
            context.stopService(Intent(context, RideMotionService::class.java))
        }
    }
}

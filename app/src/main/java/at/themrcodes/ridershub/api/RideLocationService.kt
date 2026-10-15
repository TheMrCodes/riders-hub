package at.themrcodes.ridershub.api

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Handler
import android.os.IBinder
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import at.themrcodes.ridershub.MainActivity
import at.themrcodes.ridershub.R
import at.themrcodes.ridershub.log.JsonlSessionLog
import at.themrcodes.ridershub.session.RideStore
import org.json.JSONObject
import java.io.File
import java.time.Instant

internal object LocationFixState {
    private var latest: RealtimeLocation? = null
    private var path: String? = null
    private var owner: Any? = null
    @Synchronized fun begin(logPath: String, owner: Any? = null) { path = logPath; latest = null; this.owner = owner }
    @Synchronized fun latest(): RealtimeLocation? = latest
    @Synchronized fun clear() { latest = null }
    @Synchronized fun stop(owner: Any? = null) {
        if (owner != null && this.owner !== owner) return
        path = null; latest = null; this.owner = null
    }
    @Synchronized fun accept(logPath: String, value: RealtimeLocation, allowed: () -> Boolean = { true }, owner: Any? = null, record: () -> Unit): Boolean {
        if (path != logPath || (owner != null && this.owner !== owner) || !allowed()) return false
        record()
        latest = value
        return true
    }
}

class RideLocationService : Service(), LocationListener {
    private lateinit var manager: LocationManager
    private var logFile: File? = null
    private var fixRequest: LocationListener? = null
    private val fixOwner = Any()
    private var window: RemoteTrackingGate.Window? = null
    private val worker = HandlerThread("riders-hub-location")
    private lateinit var handler: Handler
    private val store by lazy { ApiStore(this) }
    @Volatile private var destroyed = false
    private val permissionListener = AppOpsManager.OnOpChangedListener { _, packageName ->
        if (packageName == this.packageName && !hasRequiredPermissions(this)) {
            LocationFixState.stop(fixOwner)
            handler.post { stopLocation("Location permission revoked") }
        }
    }
    private val monitor = object : Runnable {
        override fun run() {
            if (!eligible(logFile?.path)) {
                stopLocation("GPS no longer enabled for this ride")
            } else handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        worker.start()
        handler = Handler(worker.looper)
        manager = getSystemService(LocationManager::class.java)
        getSystemService(AppOpsManager::class.java).startWatchingMode(
            AppOpsManager.OPSTR_FINE_LOCATION, packageName, permissionListener)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ride location", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Meet the foreground-service deadline before queueing eligibility/database work.
        if (intent?.action != ACTION_STOP) {
            try {
                startForeground(NOTIFICATION_ID, notification())
            } catch (_: RuntimeException) {
                handler.post { if (!destroyed) stopLocation("GPS could not start; check location permissions and services", 30_000) }
                return START_NOT_STICKY
            }
        } else LocationFixState.stop(fixOwner)
        handler.post { if (!destroyed) startLocation(intent) }
        return START_NOT_STICKY
    }

    private fun startLocation(intent: Intent?): Int {
        val prefs = preferences()
        if (intent?.action == ACTION_STOP) {
            (logFile?.path ?: prefs.getString(KEY_ACTIVE_LOG, null))?.let {
                prefs.edit().putString(KEY_SUPPRESSED_RIDE, it).commit()
            }
            stopLocation("Stopped from notification")
            return START_NOT_STICKY
        }
        window = RemoteTrackingGate.shared.current()?.takeIf {
            it.generation == intent?.getLongExtra("remote_generation", -1)
        }
        val path = intent?.getStringExtra(EXTRA_LOG_FILE) ?: prefs.getString(KEY_ACTIVE_LOG, null)
        if (!eligible(path) || prefs.getString(KEY_SUPPRESSED_RIDE, null) == path) {
            stopLocation("GPS unavailable for this ride")
            return START_NOT_STICKY
        }
        return try {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                stopLocation("Location services disabled", 30_000)
                return START_NOT_STICKY
            }
            if (!RideStore.get(this).resumeLocationLog(requireNotNull(path))) {
                stopLocation("Ride finalized")
                return START_NOT_STICKY
            }
            logFile = File(path)
            prefs.edit().putString(KEY_ACTIVE_LOG, path).commit()
            LocationFixState.begin(path, fixOwner)
            fixRequest?.let { manager.removeUpdates(it) }
            val requestedAt = SystemClock.elapsedRealtimeNanos()
            val request = LocationRequest.Builder(0L).setMaxUpdates(1).setDurationMillis(1_500L).build()
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!destroyed && fixRequest === this) {
                        fixRequest = null
                        manager.removeUpdates(this)
                        if (location.elapsedRealtimeNanos >= requestedAt) this@RideLocationService.onLocationChanged(location)
                        else reportStatus("No fresh GPS fix")
                    }
                }
            }
            fixRequest = listener
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, request,
                { command -> handler.post(command) }, listener)
            handler.postDelayed({
                if (fixRequest === listener) {
                    fixRequest = null
                    manager.removeUpdates(listener)
                    reportStatus("No fresh GPS fix")
                }
            }, 1_500)
            handler.removeCallbacks(monitor)
            handler.postDelayed(monitor, 1_000)
            reportStatus("Waiting for GPS fix")
            START_STICKY
        } catch (_: SecurityException) {
            stopLocation("Location permission revoked")
            START_NOT_STICKY
        } catch (_: RuntimeException) {
            stopLocation("GPS could not start; check location permissions and services", 30_000)
            START_NOT_STICKY
        }
    }

    private fun eligible(path: String?): Boolean {
        if (!RemoteTrackingGate.shared.isCurrent(window) || window?.logPath != path) return false
        if (path == null || !hasRequiredPermissions(this)) return false
        val config = store.snapshot()
        if (!config.enabled || !config.gpsEnabled || config.capabilities?.realtime?.gps != true) return false
        val ride = RideStore.get(this).activeRide()
        return ride?.logFile == path && ride.endedAt == null && ride.boardFrameCount > 0
    }

    override fun onLocationChanged(location: Location) {
        if (destroyed) return
        val file = logFile ?: return
        if (!eligible(file.path)) { stopLocation("GPS access revoked or ride finalized"); return }
        if (!location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
            !location.longitude.isFinite() || location.longitude !in -180.0..180.0 ||
            location.elapsedRealtimeNanos <= 0) {
            reportStatus("Invalid GPS fix")
            return
        }
        val fixAt = Instant.ofEpochMilli(location.time).toString()
        val fields = JSONObject().put("schema_version", 1).put("fix_at", fixAt)
            .put("latitude", location.latitude).put("longitude", location.longitude)
            .put("provider", location.provider ?: "gps").put("mock", location.isMock)
            .put("fix_elapsed_realtime_ns", location.elapsedRealtimeNanos)
        if (location.hasAccuracy()) fields.putFinite("accuracy_m", location.accuracy.toDouble())
        if (location.hasAltitude()) fields.putFinite("altitude_m", location.altitude)
        if (location.hasVerticalAccuracy()) fields.putFinite("vertical_accuracy_m", location.verticalAccuracyMeters.toDouble())
        if (location.hasSpeed()) fields.putFinite("speed_mps", location.speed.toDouble())
        if (location.hasSpeedAccuracy()) fields.putFinite("speed_accuracy_mps", location.speedAccuracyMetersPerSecond.toDouble())
        if (location.hasBearing()) fields.putFinite("bearing_deg", location.bearing.toDouble())
        if (location.hasBearingAccuracy()) fields.putFinite("bearing_accuracy_deg", location.bearingAccuracyDegrees.toDouble())
        val value = RealtimeLocation(fixAt, location.latitude, location.longitude,
            location.altitude.takeIf { location.hasAltitude() && it.isFinite() }, location.elapsedRealtimeNanos)
        if (LocationFixState.accept(file.path, value, allowed = { RemoteTrackingGate.shared.isCurrent(window) }, owner = fixOwner) {
            JsonlSessionLog.recordRide(file, "location_fix", fields, Instant.now(), SystemClock.elapsedRealtimeNanos())
        }) reportStatus("Recording")
    }

    override fun onProviderDisabled(provider: String) {
        LocationFixState.clear()
        reportStatus("Location services disabled")
    }
    override fun onProviderEnabled(provider: String) { reportStatus("Waiting for GPS fix") }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        getSystemService(AppOpsManager::class.java).stopWatchingMode(permissionListener)
        // Serialize removal after any in-flight startup; never wait for the worker on the UI.
        handler.post {
            fixRequest?.let { runCatching { manager.removeUpdates(it) } }
            fixRequest = null
            runCatching { manager.removeUpdates(this) }
            LocationFixState.stop(fixOwner)
            worker.quitSafely()
        }
        synchronized(START_LOCK) { requestedPath = null }
        super.onDestroy()
    }

    private fun stopLocation(status: String, retryDelayMs: Long = 0) {
        handler.removeCallbacks(monitor)
        fixRequest?.let { runCatching { manager.removeUpdates(it) } }
        fixRequest = null
        runCatching { manager.removeUpdates(this) }
        LocationFixState.stop(fixOwner)
        reportStatus(status)
        logFile = null
        preferences().edit().remove(KEY_ACTIVE_LOG).commit()
        synchronized(START_LOCK) { requestedPath = null; nextStartAt = SystemClock.elapsedRealtime() + retryDelayMs }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun reportStatus(status: String) {
        store.connection()?.let { store.database().setStatus(it.destinationId, "gps_status", status) }
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Riders Hub ride location")
        .setContentText("Requesting GPS for realtime batches")
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(0, "Stop GPS", PendingIntent.getService(this, 1,
            Intent(this, RideLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    private fun preferences() = getSharedPreferences("ride_location", MODE_PRIVATE)

    companion object {
        private const val CHANNEL_ID = "ride_location"
        private const val NOTIFICATION_ID = 2102
        private const val ACTION_START = "at.themrcodes.ridershub.api.START_LOCATION"
        private const val ACTION_STOP = "at.themrcodes.ridershub.api.STOP_LOCATION"
        private const val EXTRA_LOG_FILE = "log_file"
        private const val KEY_ACTIVE_LOG = "active_log"
        private const val KEY_SUPPRESSED_RIDE = "suppressed_ride"
        private val START_LOCK = Any()
        private var requestedPath: String? = null
        private var nextStartAt = 0L

        fun hasRequiredPermissions(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        internal fun start(context: Context, logFile: String, window: RemoteTrackingGate.Window) {
            if (!RemoteTrackingGate.shared.isCurrent(window) || window.logPath != logFile) return
            if (!hasRequiredPermissions(context)) return
            val prefs = context.getSharedPreferences("ride_location", Context.MODE_PRIVATE)
            if (prefs.getString(KEY_SUPPRESSED_RIDE, null) == logFile) return
            synchronized(START_LOCK) {
                if (SystemClock.elapsedRealtime() < nextStartAt) return
                requestedPath = logFile
            }
            val intent = Intent(context, RideLocationService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_LOG_FILE, logFile).putExtra("remote_generation", window.generation)
            runCatching { ContextCompat.startForegroundService(context, intent) }.onFailure {
                synchronized(START_LOCK) { requestedPath = null; nextStartAt = SystemClock.elapsedRealtime() + 30_000 }
                val store = ApiStore(context)
                store.connection()?.let { connection ->
                    store.database().setStatus(connection.destinationId, "gps_status", "Could not start",
                        "Check location permissions and background service access")
                }
            }
        }

        fun stop(context: Context) {
            LocationFixState.stop()
            synchronized(START_LOCK) { requestedPath = null; nextStartAt = 0 }
            context.getSharedPreferences("ride_location", Context.MODE_PRIVATE).edit().remove(KEY_ACTIVE_LOG).commit()
            context.stopService(Intent(context, RideLocationService::class.java))
        }
    }
}

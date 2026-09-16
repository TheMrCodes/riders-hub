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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    @Synchronized fun begin(logPath: String) { path = logPath; latest = null }
    @Synchronized fun latest(): RealtimeLocation? = latest
    @Synchronized fun clear() { latest = null }
    @Synchronized fun stop() { path = null; latest = null }
    @Synchronized fun accept(logPath: String, value: RealtimeLocation, record: () -> Unit): Boolean {
        if (path != logPath) return false
        record()
        latest = value
        return true
    }
}

class RideLocationService : Service(), LocationListener {
    private lateinit var manager: LocationManager
    private var logFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val permissionListener = AppOpsManager.OnOpChangedListener { _, packageName ->
        if (packageName == this.packageName && !hasRequiredPermissions(this)) {
            LocationFixState.stop()
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
        manager = getSystemService(LocationManager::class.java)
        getSystemService(AppOpsManager::class.java).startWatchingMode(
            AppOpsManager.OPSTR_FINE_LOCATION, packageName, permissionListener)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ride location", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = preferences()
        if (intent?.action == ACTION_STOP) {
            (logFile?.path ?: prefs.getString(KEY_ACTIVE_LOG, null))?.let {
                prefs.edit().putString(KEY_SUPPRESSED_RIDE, it).commit()
            }
            stopLocation("Stopped from notification")
            return START_NOT_STICKY
        }
        val path = intent?.getStringExtra(EXTRA_LOG_FILE) ?: prefs.getString(KEY_ACTIVE_LOG, null)
        if (!eligible(path) || prefs.getString(KEY_SUPPRESSED_RIDE, null) == path) {
            stopLocation("GPS unavailable for this ride")
            return START_NOT_STICKY
        }
        return try {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                stopLocation("Location services disabled")
                return START_NOT_STICKY
            }
            // Android validates location permissions at startForeground, so this belongs inside
            // the guarded operation too (permissions may change after our preceding check).
            startForeground(NOTIFICATION_ID, notification())
            if (!RideStore.get(this).resumeLocationLog(requireNotNull(path))) {
                stopLocation("Ride finalized")
                return START_NOT_STICKY
            }
            logFile = File(path)
            prefs.edit().putString(KEY_ACTIVE_LOG, path).commit()
            LocationFixState.begin(path)
            manager.removeUpdates(this)
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this)
            handler.removeCallbacks(monitor)
            handler.postDelayed(monitor, 1_000)
            reportStatus("Waiting for GPS fix")
            START_STICKY
        } catch (_: SecurityException) {
            stopLocation("Location permission revoked")
            START_NOT_STICKY
        } catch (_: RuntimeException) {
            stopLocation("GPS could not start; check location permissions and services")
            START_NOT_STICKY
        }
    }

    private fun eligible(path: String?): Boolean {
        if (path == null || !hasRequiredPermissions(this)) return false
        val config = ApiStore(this).snapshot()
        if (!config.enabled || !config.gpsEnabled || config.capabilities?.realtime?.gps != true) return false
        val ride = RideStore.get(this).activeRide()
        return ride?.logFile == path && ride.endedAt == null && ride.boardFrameCount > 0
    }

    override fun onLocationChanged(location: Location) {
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
        if (LocationFixState.accept(file.path, value) {
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
        handler.removeCallbacksAndMessages(null)
        getSystemService(AppOpsManager::class.java).stopWatchingMode(permissionListener)
        runCatching { manager.removeUpdates(this) }
        LocationFixState.stop()
        synchronized(START_LOCK) { requestedPath = null }
        super.onDestroy()
    }

    private fun stopLocation(status: String) {
        handler.removeCallbacks(monitor)
        runCatching { manager.removeUpdates(this) }
        LocationFixState.stop()
        reportStatus(status)
        logFile = null
        preferences().edit().remove(KEY_ACTIVE_LOG).commit()
        synchronized(START_LOCK) { requestedPath = null; nextStartAt = SystemClock.elapsedRealtime() + 30_000 }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun reportStatus(status: String) {
        val store = ApiStore(this)
        store.connection()?.let { store.database().setStatus(it.destinationId, "gps_status", status) }
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Riders Hub ride location")
        .setContentText("Recording precise GPS for the active ride")
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

        fun start(context: Context, logFile: String) {
            if (!hasRequiredPermissions(context)) return
            val prefs = context.getSharedPreferences("ride_location", Context.MODE_PRIVATE)
            if (prefs.getString(KEY_SUPPRESSED_RIDE, null) == logFile) return
            synchronized(START_LOCK) {
                if (requestedPath == logFile || SystemClock.elapsedRealtime() < nextStartAt) return
                requestedPath = logFile
            }
            val intent = Intent(context, RideLocationService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_LOG_FILE, logFile)
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

package at.themrcodes.ridershub.charger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.content.ContextCompat
import at.themrcodes.ridershub.CompanionRole
import at.themrcodes.ridershub.CompanionRoleStore
import at.themrcodes.ridershub.MainActivity
import at.themrcodes.ridershub.R
import at.themrcodes.ridershub.toBackfireAssociation

/** Owns charger GATT independently of transient companion presence callbacks. */
class ChargerMonitorService : Service() {
    private val worker = HandlerThread("riders-hub-charger")
    private lateinit var handler: Handler
    private lateinit var store: ChargerStore
    private lateinit var preferences: SharedPreferences
    private var session: ShellyChargerSession? = null
    private var associationId: Int? = null
    @Volatile private var destroyed = false
    @Volatile private var stopping = false
    private val updateNotification = Runnable {
        if (!destroyed && !stopping) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }
    private val stateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.removeCallbacks(updateNotification)
        handler.postDelayed(updateNotification, 200)
    }
    private val commands = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (destroyed || stopping || intent.action !in setOf(ChargerControl.ACTION_START, ChargerControl.ACTION_STOP) ||
                !intent.getBooleanExtra(ChargerControl.EXTRA_CONFIRMED, false)) return
            val token = intent.getStringExtra(ChargerControl.EXTRA_APPEARANCE_TOKEN).orEmpty()
            val start = intent.action == ChargerControl.ACTION_START
            val snapshot = store.snapshot()
            if (token.isEmpty() || token != snapshot.appearanceToken || (start && !snapshot.canPrompt)) return
            val result = session?.userDecision(start, token)
                ?: Result.failure(IllegalStateException("Charger is reconnecting; wait for verification"))
            result.onSuccess { ChargerNotifier(this@ChargerMonitorService).cancelPrompt() }
                .onFailure { store.update(ChargerPhase.ERROR, "Charger command was not sent", error = it.message) }
        }
    }
    private val checkAssociation = object : Runnable {
        override fun run() {
            if (destroyed) return
            val selected = CompanionRoleStore(this@ChargerMonitorService).associationId(CompanionRole.CHARGER_PLUG)
            val exists = getSystemService(CompanionDeviceManager::class.java).myAssociations.any { it.id == associationId }
            if (!exists || selected != associationId ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                preferences.edit().clear().apply()
                stopSelf()
            } else handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        worker.start()
        handler = Handler(worker.looper)
        store = ChargerStore(this)
        preferences = getSharedPreferences("charger_monitor", MODE_PRIVATE)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Charger monitoring", NotificationManager.IMPORTANCE_LOW))
        ChargerNotifier.createChannel(this)
        getSharedPreferences("charger_state", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(stateListener)
        registerReceiver(commands, IntentFilter().apply {
            addAction(ChargerControl.ACTION_START); addAction(ChargerControl.ACTION_STOP)
        }, null, handler, RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MONITOR) {
            finishMonitoring()
            return START_NOT_STICKY
        }
        val requestedId = intent?.getIntExtra(EXTRA_ASSOCIATION, -1)?.takeIf { it >= 0 }
            ?: preferences.getInt(EXTRA_ASSOCIATION, -1)
        val fresh = intent?.getBooleanExtra(EXTRA_FRESH, false) == true
        if (!canResume(preferences, requestedId, fresh)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try { startForeground(NOTIFICATION_ID, notification()) }
        catch (_: RuntimeException) {
            store.update(ChargerPhase.ERROR, "Could not start charger monitoring; open Riders Hub to retry")
            stopSelf()
            return START_NOT_STICKY
        }
        handler.post {
            if (destroyed || stopping) return@post
            val id = intent?.getIntExtra(EXTRA_ASSOCIATION, -1)?.takeIf { it >= 0 }
                ?: preferences.getInt(EXTRA_ASSOCIATION, -1)
            val info = getSystemService(CompanionDeviceManager::class.java).myAssociations.firstOrNull { it.id == id }
            if (info == null || CompanionRoleStore(this).associationId(CompanionRole.CHARGER_PLUG) != id ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                stopSelf(startId)
                return@post
            }
            if (associationId == id && session != null) return@post
            runCatching {
                val association = requireNotNull(info.toBackfireAssociation())
                session?.stop("charger_selection_changed")
                associationId = id
                preferences.edit().putInt(EXTRA_ASSOCIATION, id).apply()
                val fresh = intent?.getBooleanExtra(EXTRA_FRESH, false) == true
                store.setAssociation(id, association.displayName)
                store.beginAppearance(fresh)
                // A separate Handler shares this worker, but owns only session callbacks.
                session = ShellyChargerSession(this, association.address, store, fresh, Handler(worker.looper),
                    onMonitoringStopped = { finishMonitoring() }) { _ -> }
                    .also { it.start() }
                handler.removeCallbacks(checkAssociation)
                handler.postDelayed(checkAssociation, 30_000)
            }.onFailure {
                store.update(ChargerPhase.ERROR, "Could not initialize charger monitoring")
                stopSelf(startId)
            }
        }
        return START_STICKY
    }

    private fun finishMonitoring() {
        stopping = true
        val id = associationId ?: preferences.getInt(EXTRA_ASSOCIATION, -1)
        preferences.edit().remove(EXTRA_ASSOCIATION).putInt(PAUSED_ASSOCIATION, id).apply()
        handler.removeCallbacks(updateNotification)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        unregisterReceiver(commands)
        getSharedPreferences("charger_state", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(stateListener)
        handler.removeCallbacksAndMessages(null)
        handler.post {
            try { session?.stop("monitor_stopped") }
            finally { session = null; worker.quitSafely() }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    internal fun notification(): Notification {
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, NOTIFICATION_ID,
            Intent(this, ChargerMonitorService::class.java).setAction(ACTION_STOP_MONITOR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Riders Hub charger monitoring")
            .setContentText(store.snapshot().status).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Stop monitoring", stop).build()).build()
    }

    companion object {
        internal const val NOTIFICATION_ID = 2110
        private const val CHANNEL_ID = "charger_monitor"
        private const val EXTRA_ASSOCIATION = "association_id"
        private const val EXTRA_FRESH = "fresh"
        private const val PAUSED_ASSOCIATION = "paused_association_id"
        internal const val ACTION_STOP_MONITOR = "at.themrcodes.ridershub.STOP_CHARGER_MONITOR"
        internal fun canResume(preferences: SharedPreferences, associationId: Int, fresh: Boolean): Boolean {
            if (associationId < 0) return false
            if (fresh) {
                preferences.edit().remove(PAUSED_ASSOCIATION).apply()
                return true
            }
            return preferences.getInt(PAUSED_ASSOCIATION, -1) != associationId
        }
        fun start(context: Context, associationId: Int, fresh: Boolean) {
            if (!canResume(context.getSharedPreferences("charger_monitor", MODE_PRIVATE), associationId, fresh)) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ChargerMonitorService::class.java)
                    .putExtra(EXTRA_ASSOCIATION, associationId).putExtra(EXTRA_FRESH, fresh))
            }.onFailure {
                ChargerStore(context).update(ChargerPhase.ERROR,
                    "Charger monitoring could not start; open Riders Hub to retry")
            }
        }
    }
}

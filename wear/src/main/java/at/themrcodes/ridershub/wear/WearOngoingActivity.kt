package at.themrcodes.ridershub.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.wear.ongoing.OngoingActivity
import at.themrcodes.ridershub.wear.shared.WearConnectionStatus
import at.themrcodes.ridershub.wear.shared.WearTelemetryState

internal object WearOngoingActivity {
    private var visible = false
    private var lastPostAtEpochMs = 0L

    fun sync(context: Context, telemetry: WearTelemetryState?, nowEpochMs: Long) {
        if (!shouldKeepRideVisible(telemetry, nowEpochMs)) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            visible = false
            lastPostAtEpochMs = 0L
            return
        }
        if (!notificationsAllowed(context)) return
        if (visible && nowEpochMs - lastPostAtEpochMs < NOTIFICATION_REFRESH_MS) return

        createChannel(context)
        val touchIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.ongoing_title))
            .setContentText(context.getString(R.string.ongoing_status))
            .setContentIntent(touchIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        OngoingActivity.Builder(context, NOTIFICATION_ID, notification)
            .setStaticIcon(R.drawable.ic_notification)
            .setTouchIntent(touchIntent)
            .setTitle(context.getString(R.string.ongoing_title))
            .build()
            .apply(context)

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification.build())
            visible = true
            lastPostAtEpochMs = nowEpochMs
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and this call.
        }
    }

    private fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.ongoing_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.ongoing_channel_description)
                setShowBadge(false)
            },
        )
    }

    private const val CHANNEL_ID = "active_ride"
    private const val NOTIFICATION_ID = 4101
    private const val NOTIFICATION_REFRESH_MS = 60_000L
    private const val NOTIFICATION_TIMEOUT_MS = 150_000L
}

internal fun shouldKeepRideVisible(
    telemetry: WearTelemetryState?,
    nowEpochMs: Long,
): Boolean {
    if (telemetry == null) return false
    val ageMs = (nowEpochMs - telemetry.updatedAtEpochMs).coerceAtLeast(0L)
    return telemetry.connection != WearConnectionStatus.STANDBY && ageMs <= MAX_ONGOING_AGE_MS
}

private const val MAX_ONGOING_AGE_MS = 120_000L

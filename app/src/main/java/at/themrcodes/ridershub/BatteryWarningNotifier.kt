package at.themrcodes.ridershub

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Locale

class BatteryWarningNotifier(private val context: Context) {
    private val preferences = context.getSharedPreferences("battery_warnings", Context.MODE_PRIVATE)
    private val generalSettings = GeneralSettingsStore(context)

    fun maybeNotifyBoard(sessionId: String, batteryPercent: Int, packVoltageV: Double) {
        val warningPercent = generalSettings.snapshot().lowBatteryWarningPercent
        if (!shouldNotifyLowBoardBattery(batteryPercent, packVoltageV, warningPercent)) return
        if (preferences.getString(KEY_LAST_BOARD_SESSION, null) == sessionId) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Board battery is low")
            .setContentText("$batteryPercent% remaining (${"%.1f".format(Locale.US, packVoltageV)} V). Recharge before the next ride.")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(BOARD_NOTIFICATION_ID, notification)
        preferences.edit().putString(KEY_LAST_BOARD_SESSION, sessionId).apply()
    }

    companion object {
        private const val CHANNEL_ID = "battery_warning"
        private const val KEY_LAST_BOARD_SESSION = "last_board_warning_session"
        private const val BOARD_NOTIFICATION_ID = 2001

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Board battery warnings",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Warns when the board battery reaches the configured limit"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

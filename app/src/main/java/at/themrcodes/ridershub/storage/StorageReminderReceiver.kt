package at.themrcodes.ridershub.storage

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import at.themrcodes.ridershub.MainActivity
import at.themrcodes.ridershub.R
import java.time.ZoneId

/** Short local work only. Alarm delivery is approximate and subject to Android power restrictions. */
class StorageReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = StorageModeStore.get(context)
        if (intent.action == ACTION_REMIND) store.evaluate()
        else schedule(context, store.snapshot())
    }

    companion object {
        internal const val ACTION_REMIND = "at.themrcodes.ridershub.STORAGE_REMINDER"
        internal fun schedule(context: Context, state: StorageState, now: Long = System.currentTimeMillis()) {
            val alarm = context.getSystemService(AlarmManager::class.java)
            val pending = PendingIntent.getBroadcast(context, 3001,
                Intent(context, StorageReminderReceiver::class.java).setAction(ACTION_REMIND),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val at = storageAlarmAt(state, now, ZoneId.systemDefault())
            if (at == null) alarm.cancel(pending)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }
}

internal object StorageNotifications {
    const val CHANNEL = "board_storage"
    const val ID = 3001
    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Board storage reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Storage setup, battery check-ins and overdue charging around 10:00 local time"
            })
    }

    fun available(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun cancel(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(ID) }

    fun post(context: Context, kind: StorageReminder): Boolean {
        createChannel(context)
        if (!available(context)) return false
        val (title, body) = when (kind) {
            StorageReminder.OFFER -> "Put your board in storage mode?" to "No recorded ride for 14 days. Open Riders Hub to answer."
            StorageReminder.BASELINE -> "Connect your board to start storage tracking" to "Keep the board still for 30 seconds to record its starting battery level."
            StorageReminder.SAMPLE -> "Storage battery check-in is due" to "Connect your board and remote to collect the next weekly battery observation."
            StorageReminder.CHARGE -> "Time to charge your stored board" to "Charge, then connect your board and remote. A fresh battery increase confirms charging."
        }
        val pending = PendingIntent.getActivity(context, ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_storage", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(NotificationManager::class.java).notify(ID,
            Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(pending).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build())
        return true
    }
}

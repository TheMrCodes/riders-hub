package at.themrcodes.ridershub.charger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import at.themrcodes.ridershub.MainActivity
import at.themrcodes.ridershub.R

class ChargerNotifier(private val context: Context) {
    fun prompt(token: String) = show(
        id = PROMPT_ID,
        title = "Board charger detected",
        body = "Charger is ready. Connect the board, then tap Start charging.",
        token = token,
        ongoing = false,
    )

    fun cancelPrompt() = context.getSystemService(NotificationManager::class.java).cancel(PROMPT_ID)

    fun complete() = show(
        id = RESULT_ID,
        title = "Charging complete",
        body = "The charger output was turned off and verified.",
        token = null,
        ongoing = false,
    )

    fun fault(detail: String) = show(RESULT_ID, "Charger stopped", detail, null, false)

    private fun show(id: Int, title: String, body: String, token: String?, ongoing: Boolean) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ChargerControl.EXTRA_OPEN_PROMPT, token != null)
            token?.let { putExtra(ChargerControl.EXTRA_APPEARANCE_TOKEN, it) }
        }
        val open = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(body)
            .setContentIntent(open).setAutoCancel(!ongoing).setOngoing(ongoing)
            .setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE)
        if (token != null) {
            val start = PendingIntent.getBroadcast(
                context, PROMPT_ID,
                Intent(ChargerControl.ACTION_START).apply {
                    setPackage(context.packageName)
                    // Keep old notification intents tied to their original appearance.
                    identifier = token
                    putExtra(ChargerControl.EXTRA_APPEARANCE_TOKEN, token)
                    putExtra(ChargerControl.EXTRA_CONFIRMED, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, "Start charging", start)
                .setAuthenticationRequired(true).build())
        }
        context.getSystemService(NotificationManager::class.java).notify(id, builder.build())
    }

    companion object {
        private const val CHANNEL_ID = "charger_observation"
        private const val PROMPT_ID = 2101
        private const val RESULT_ID = 2112
        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Charger observation", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Prompts and safety outcomes for an observed board charger"
                },
            )
        }
    }
}

package at.themrcodes.ridershub.charger

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class ChargerNotifierTest {
    private val app = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val notifier = ChargerNotifier(app)

    @Before fun setup() {
        manager.cancelAll()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ChargerNotifier.createChannel(app)
    }

    @Test fun startActionUsesConfirmedAppearanceScopedBroadcast() {
        notifier.prompt("synthetic-appearance-one")
        val notification = shadowOf(manager).allNotifications.single()
        val action = notification.actions.single()
        assertEquals("Start charging", action.title.toString())
        assertTrue(action.isAuthenticationRequired)
        assertTrue(action.actionIntent.isImmutable)
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals(ChargerControl.ACTION_START, intent.action)
        assertEquals(app.packageName, intent.`package`)
        assertEquals("synthetic-appearance-one", intent.getStringExtra(ChargerControl.EXTRA_APPEARANCE_TOKEN))
        assertTrue(intent.getBooleanExtra(ChargerControl.EXTRA_CONFIRMED, false))
        var delivered: Intent? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { delivered = intent }
        }
        app.registerReceiver(receiver, IntentFilter(ChargerControl.ACTION_START), Context.RECEIVER_NOT_EXPORTED)
        try {
            action.actionIntent.send()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("synthetic-appearance-one", delivered?.getStringExtra(ChargerControl.EXTRA_APPEARANCE_TOKEN))
        } finally {
            app.unregisterReceiver(receiver)
        }
        val oldAction = action.actionIntent
        notifier.prompt("synthetic-appearance-two")
        val newAction = shadowOf(manager).allNotifications.single().actions.single().actionIntent
        assertNotEquals(oldAction, newAction)
        assertEquals("synthetic-appearance-one", shadowOf(oldAction).savedIntent.getStringExtra(ChargerControl.EXTRA_APPEARANCE_TOKEN))
    }

    @Test fun cancelledPromptLeavesCompletionNotificationWithoutStartAction() {
        notifier.prompt("synthetic-appearance")
        notifier.cancelPrompt()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        notifier.complete()
        assertNull(shadowOf(manager).allNotifications.single().actions)
    }

    @Test fun deniedNotificationPermissionDoesNotPostPrompt() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.prompt("synthetic-appearance")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}

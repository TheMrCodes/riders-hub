package at.themrcodes.ridershub

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.wear.remote.interactions.RemoteActivityHelper
import at.themrcodes.ridershub.wear.shared.WearConnectionStatus
import at.themrcodes.ridershub.wear.shared.WearSettingsState
import at.themrcodes.ridershub.wear.shared.WearTelemetryState
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import java.security.MessageDigest
import java.util.Locale

internal class WearAutoOpenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun autoOpenOnLive(): Boolean = preferences.getBoolean(
        KEY_AUTO_OPEN_ON_LIVE,
        DEFAULT_AUTO_OPEN_ON_LIVE,
    )

    fun setAutoOpenOnLive(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_OPEN_ON_LIVE, enabled).apply()
    }

    fun lastSeenLiveSession(): String? = preferences.getString(KEY_LAST_SEEN_LIVE_SESSION, null)

    fun markLiveSessionSeen(sessionToken: String) {
        preferences.edit().putString(KEY_LAST_SEEN_LIVE_SESSION, sessionToken).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "wear_auto_open"
        const val KEY_AUTO_OPEN_ON_LIVE = "enabled"
        const val KEY_LAST_SEEN_LIVE_SESSION = "last_seen_live_session"
        const val DEFAULT_AUTO_OPEN_ON_LIVE = WearSettingsState.DEFAULT_AUTO_OPEN_ON_LIVE
    }
}

class WearSettingsListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.dataItem.uri.path != WearSettingsState.DATA_PATH) return@forEach
            if (event.type == DataEvent.TYPE_DELETED) return@forEach
            val settings = runCatching {
                WearSettingsState.decode(event.dataItem.data ?: return@forEach)
            }.getOrNull() ?: return@forEach
            WearAutoOpenStore(this).setAutoOpenOnLive(settings.autoOpenOnLive)
        }
    }
}

internal class WearRemoteLauncher(context: Context) {
    private val applicationContext = context.applicationContext
    private val store = WearAutoOpenStore(applicationContext)
    private val callbackExecutor = ContextCompat.getMainExecutor(applicationContext)
    private val remoteActivityHelper = RemoteActivityHelper(applicationContext, callbackExecutor)

    fun onTelemetry(snapshot: AppSnapshot, telemetry: WearTelemetryState) {
        val sessionToken = snapshot.latestLog?.let(::privacySafeSessionToken) ?: return
        val isFirstLiveFrame = isFirstLiveSessionFrame(
            connection = telemetry.connection,
            hasValidFrame = snapshot.frameCount > 0 && snapshot.crcValid == true,
            sessionToken = sessionToken,
            lastSeenLiveSession = store.lastSeenLiveSession(),
        )
        if (!isFirstLiveFrame) return

        // Mark even when disabled so enabling the setting mid-ride applies to the next trip.
        store.markLiveSessionSeen(sessionToken)
        if (!store.autoOpenOnLive()) return
        val intent = Intent(Intent.ACTION_VIEW)
            .setData(Uri.parse(WEAR_RIDE_URI))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        runCatching {
            remoteActivityHelper.startRemoteActivity(intent, null)
        }
    }
}

internal fun shouldAutoOpenWearDashboard(
    enabled: Boolean,
    connection: WearConnectionStatus,
    hasValidFrame: Boolean,
    sessionToken: String?,
    lastSeenLiveSession: String?,
): Boolean = enabled && isFirstLiveSessionFrame(
    connection = connection,
    hasValidFrame = hasValidFrame,
    sessionToken = sessionToken,
    lastSeenLiveSession = lastSeenLiveSession,
)

internal fun isFirstLiveSessionFrame(
    connection: WearConnectionStatus,
    hasValidFrame: Boolean,
    sessionToken: String?,
    lastSeenLiveSession: String?,
): Boolean = connection == WearConnectionStatus.LIVE &&
    hasValidFrame &&
    sessionToken != null &&
    sessionToken != lastSeenLiveSession

private fun privacySafeSessionToken(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .take(16)
    .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }

private const val WEAR_RIDE_URI = "ridershub://ride"

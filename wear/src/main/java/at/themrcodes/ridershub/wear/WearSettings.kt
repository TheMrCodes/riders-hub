package at.themrcodes.ridershub.wear

import android.content.Context
import at.themrcodes.ridershub.wear.shared.WearSettingsState
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable

internal object WearSettingsPreferences {
    fun autoOpenOnLive(context: Context): Boolean = preferences(context)
        .getBoolean(KEY_AUTO_OPEN_ON_LIVE, DEFAULT_AUTO_OPEN_ON_LIVE)

    fun setAutoOpenOnLive(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_AUTO_OPEN_ON_LIVE, enabled).apply()
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private const val PREFERENCES_NAME = "wear_settings"
    private const val KEY_AUTO_OPEN_ON_LIVE = "auto_open_on_live"
    private const val DEFAULT_AUTO_OPEN_ON_LIVE = WearSettingsState.DEFAULT_AUTO_OPEN_ON_LIVE
}

internal object WearSettingsPublisher {
    fun publish(context: Context, autoOpenOnLive: Boolean) {
        val request = PutDataRequest.create(WearSettingsState.DATA_PATH)
            .setData(WearSettingsState(autoOpenOnLive).encode())
            .setUrgent()
        Wearable.getDataClient(context.applicationContext).putDataItem(request)
    }
}

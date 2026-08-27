package at.themrcodes.ridershub.homeassistant

import android.content.Context
import java.io.IOException
import java.time.Instant
import java.util.UUID

internal class HomeAssistantStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val vault = HomeAssistantVault()

    @Synchronized
    fun snapshot(busy: Boolean = false): HomeAssistantSnapshot {
        val connection = connection()
        return HomeAssistantSnapshot(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            connected = connection != null,
            webhookUrl = connection?.webhookUrl,
            payloadEncrypted = connection?.encryptionSecret != null,
            status = preferences.getString(KEY_STATUS, null)
                ?: if (connection == null) "Not connected" else "Connected",
            lastDeliveryAt = preferences.getString(KEY_LAST_DELIVERY_AT, null),
            busy = busy,
        )
    }

    @Synchronized
    fun connection(): HomeAssistantConnection? {
        val protectedUrl = preferences.getString(KEY_PROTECTED_WEBHOOK_URL, null)
        val legacyUrl = preferences.getString(KEY_LEGACY_WEBHOOK_URL, null)
        val webhookUrl = try {
            protectedUrl?.let { vault.decrypt(it, PURPOSE_WEBHOOK_URL) } ?: legacyUrl
        } catch (_: IOException) {
            invalidateCredentials()
            return null
        }?.takeIf(String::isNotBlank) ?: return null

        val protectedSecret = preferences.getString(KEY_PROTECTED_ENCRYPTION_SECRET, null)
        val secret = try {
            protectedSecret?.let { vault.decrypt(it, PURPOSE_ENCRYPTION_SECRET) }
        } catch (_: IOException) {
            invalidateCredentials()
            return null
        }
        return HomeAssistantConnection(webhookUrl, secret)
    }

    fun setEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_STATUS, if (enabled) "Ready" else "Delivery paused")
            .apply()
    }

    fun setStatus(status: String) {
        preferences.edit().putString(KEY_STATUS, status).apply()
    }

    /** Stores the webhook and encryption key together before either can be used. */
    @Synchronized
    fun connect(connection: HomeAssistantConnection) {
        val protectedUrl = vault.encrypt(connection.webhookUrl, PURPOSE_WEBHOOK_URL)
        val protectedSecret = connection.encryptionSecret?.let {
            vault.encrypt(it, PURPOSE_ENCRYPTION_SECRET)
        }
        val editor = preferences.edit()
            .putString(KEY_PROTECTED_WEBHOOK_URL, protectedUrl)
            .remove(KEY_LEGACY_WEBHOOK_URL)
            .putString(KEY_STATUS, if (protectedSecret == null) "Connected" else "Connected · encrypted")
        if (protectedSecret == null) {
            editor.remove(KEY_PROTECTED_ENCRYPTION_SECRET)
        } else {
            editor.putString(KEY_PROTECTED_ENCRYPTION_SECRET, protectedSecret)
        }
        if (!editor.commit()) throw IOException("Could not securely store Home Assistant credentials")
    }

    @Synchronized
    fun updateWebhookUrl(webhookUrl: String) {
        val protectedUrl = vault.encrypt(webhookUrl, PURPOSE_WEBHOOK_URL)
        if (!preferences.edit()
                .putString(KEY_PROTECTED_WEBHOOK_URL, protectedUrl)
                .remove(KEY_LEGACY_WEBHOOK_URL)
                .putString(KEY_STATUS, "Webhook saved")
                .commit()
        ) {
            throw IOException("Could not securely store the webhook")
        }
    }

    fun recordDelivery(disabledCount: Int) {
        val status = if (disabledCount == 0) {
            "Delivered"
        } else {
            "Delivered · $disabledCount ${if (disabledCount == 1) "entity" else "entities"} disabled"
        }
        preferences.edit()
            .putString(KEY_STATUS, status)
            .putString(KEY_LAST_DELIVERY_AT, Instant.now().toString())
            .apply()
    }

    @Synchronized
    fun disconnect(status: String = "Not connected") {
        preferences.edit()
            .remove(KEY_PROTECTED_WEBHOOK_URL)
            .remove(KEY_PROTECTED_ENCRYPTION_SECRET)
            .remove(KEY_LEGACY_WEBHOOK_URL)
            .remove(KEY_LAST_DELIVERY_AT)
            .putString(KEY_STATUS, status)
            .commit()
        runCatching(vault::deleteKey)
    }

    fun deviceId(): String {
        preferences.getString(KEY_DEVICE_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also { generated ->
            preferences.edit().putString(KEY_DEVICE_ID, generated).commit()
        }
    }

    private fun invalidateCredentials() {
        preferences.edit()
            .remove(KEY_PROTECTED_WEBHOOK_URL)
            .remove(KEY_PROTECTED_ENCRYPTION_SECRET)
            .remove(KEY_LEGACY_WEBHOOK_URL)
            .remove(KEY_LAST_DELIVERY_AT)
            .putString(KEY_STATUS, "Secure credentials unavailable; reconnect required")
            .commit()
        runCatching(vault::deleteKey)
    }

    companion object {
        private const val PREFERENCES_NAME = "home_assistant"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PROTECTED_WEBHOOK_URL = "protected_webhook_url"
        private const val KEY_PROTECTED_ENCRYPTION_SECRET = "protected_encryption_secret"
        private const val KEY_LEGACY_WEBHOOK_URL = "webhook_url"
        private const val KEY_STATUS = "status"
        private const val KEY_LAST_DELIVERY_AT = "last_delivery_at"
        private const val KEY_DEVICE_ID = "device_id"
        private const val PURPOSE_WEBHOOK_URL = "webhook-url"
        private const val PURPOSE_ENCRYPTION_SECRET = "encryption-secret"
    }
}

internal data class HomeAssistantConnection(
    val webhookUrl: String,
    val encryptionSecret: String?,
)

data class HomeAssistantSnapshot(
    val enabled: Boolean,
    val connected: Boolean,
    val webhookUrl: String?,
    val payloadEncrypted: Boolean,
    val status: String,
    val lastDeliveryAt: String?,
    val busy: Boolean,
)

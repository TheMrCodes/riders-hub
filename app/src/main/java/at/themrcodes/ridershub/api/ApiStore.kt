package at.themrcodes.ridershub.api

import android.content.Context
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class ApiConnection(
    val baseUrl: String,
    val bearerKey: String,
    val destinationId: String,
    val serverId: String? = null,
    val revision: String = "",
) {
    val deliveryScope: String get() = deliveryScopeId(destinationId, requireNotNull(serverId))
    // Credentials must never enter exception messages or diagnostic logs.
    override fun toString() = "ApiConnection(redacted)"
}

enum class ApiAuthMode { OIDC_DEVICE, LEGACY_BEARER }

internal fun deliveryScopeId(destinationId: String, serverId: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$destinationId\u0000$serverId".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

data class ApiSnapshot(
    val enabled: Boolean,
    val configured: Boolean,
    val baseUrl: String?,
    val authMode: ApiAuthMode?,
    val insecure: Boolean,
    val httpConfirmed: Boolean,
    val serverId: String?,
    val capabilities: ApiCapabilities?,
    val analyticsApproved: Boolean,
    val gpsEnabled: Boolean,
    val metricsStatus: String,
    val realtimeStatus: String,
    val analyticsStatus: String,
    val chargerAnalyticsStatus: String,
    val gpsStatus: String,
    val lastError: String?,
    val motionEnabled: Boolean = false,
    val motionStatus: String = "Off",
)

internal class ApiStore(
    context: Context,
    private val vault: ApiCredentialProtection = ApiVault(),
    private val database: ApiDatabase = ApiDatabase.get(context),
) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    // Cache only decrypted identity, never server capabilities, consent or channel status.
    // Compare persisted ciphertext on every read so other ApiStore instances invalidate it too.
    private class Identity(val encryptedUrl: String, val encryptedKey: String,
        val url: String, val key: String, val destination: String)
    private var identity: Identity? = null

    fun connection(): ApiConnection? = synchronized(CONFIG_LOCK) { readConnection() }

    private fun readConnection(): ApiConnection? = try {
        val encryptedUrl = preferences.getString(KEY_URL, null)
        val encryptedKey = preferences.getString(KEY_KEY, null)
        if (encryptedUrl == null || encryptedKey == null) {
            identity = null
            return null
        }
        if (preferences.getString(KEY_AUTH_MODE, null) != ApiAuthMode.OIDC_DEVICE.name) {
            clearConfiguration()
            return null
        }
        val cached = identity?.takeIf {
            it.encryptedUrl == encryptedUrl && it.encryptedKey == encryptedKey
        } ?: run {
            val url = vault.decrypt(encryptedUrl, "base-url")
            Identity(encryptedUrl, encryptedKey, url, vault.decrypt(encryptedKey, "bearer-key"),
                destinationId(url)).also { identity = it }
        }
        val (url, key, destination) = Triple(cached.url, cached.key, cached.destination)
        ApiConnection(url, key, destination, database.serverId(destination),
            preferences.getString(KEY_REVISION, "") ?: "")
    } catch (_: Exception) {
        clearConfiguration()
        null
    }

    fun configureOidc(baseUrl: String, deviceToken: String) = synchronized(CONFIG_LOCK) {
        require(apiUrls(baseUrl).base.isHttps) { "SSO enrollment requires HTTPS" }
        require(deviceToken.isNotBlank()) { "Device token must not be blank" }
        saveConfiguration(baseUrl, deviceToken)
    }

    private fun saveConfiguration(
        baseUrl: String,
        deviceToken: String,
    ) {
        val urls = apiUrls(baseUrl)
        val normalized = urls.base.toString().removeSuffix("/")
        check(preferences.edit()
            .putString(KEY_URL, vault.encrypt(normalized, "base-url"))
            .putString(KEY_KEY, vault.encrypt(deviceToken, "bearer-key"))
            .putString(KEY_AUTH_MODE, ApiAuthMode.OIDC_DEVICE.name)
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_REVISION, UUID.randomUUID().toString())
            .commit()) { "Could not securely store API configuration" }
        database.saveDestination(destinationId(normalized), null, null)
        removeHomeAssistantCredentials()
    }

    fun setEnabled(enabled: Boolean) = synchronized(CONFIG_LOCK) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).commit()
    }
    fun setGpsEnabled(enabled: Boolean) = synchronized(CONFIG_LOCK) {
        preferences.edit().putBoolean(KEY_GPS_ENABLED, enabled).apply()
    }

    fun saveCapabilities(destinationId: String, json: JSONObject, parsed: ApiCapabilities) = synchronized(CONFIG_LOCK) {
        database.saveDestination(destinationId, parsed.serverId, json)
    }

    fun approveAnalytics(approved: Boolean) = synchronized(CONFIG_LOCK) {
        val connection = connection() ?: return@synchronized
        val server = connection.serverId ?: return@synchronized
        database.setAnalyticsApproved(connection.destinationId, server, approved)
    }

    fun snapshot(): ApiSnapshot = synchronized(CONFIG_LOCK) {
        val connection = connection()
        val caps = connection?.let { database.cachedCapabilities(it.destinationId) }
            ?.let { runCatching { parseCapabilities(it) }.getOrNull() }
        val server = connection?.serverId
        val statuses = connection?.let { database.statuses(it.destinationId) }
            ?: listOf("Not configured", "Not configured", "Not configured", "Not configured", "Off", null)
        ApiSnapshot(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            configured = connection != null,
            baseUrl = connection?.baseUrl,
            authMode = connection?.let { ApiAuthMode.OIDC_DEVICE },
            insecure = connection?.let { !apiUrls(it.baseUrl).base.isHttps } == true,
            httpConfirmed = false,
            serverId = server,
            capabilities = caps,
            analyticsApproved = connection != null && server != null &&
                database.analyticsApproved(connection.destinationId, server),
            gpsEnabled = preferences.getBoolean(KEY_GPS_ENABLED, false),
            metricsStatus = statuses[0] ?: "Unknown",
            realtimeStatus = statuses[1] ?: "Unknown",
            analyticsStatus = statuses[2] ?: "Unknown",
            chargerAnalyticsStatus = statuses[3] ?: "Unknown",
            gpsStatus = statuses[4] ?: "Unknown",
            lastError = statuses[5],
            motionEnabled = connection?.takeIf { it.serverId != null }?.let { MotionStore.get(app).approved(it.deliveryScope) } == true,
            motionStatus = connection?.takeIf { it.serverId != null }?.let { MotionStore.get(app).status(it.deliveryScope) } ?: "Off",
        )
    }

    fun clearConfiguration() = synchronized(CONFIG_LOCK) {
        identity = null
        preferences.edit().clear().commit()
    }

    fun resyncArchives() = synchronized(CONFIG_LOCK) {
        connection()?.takeIf { it.serverId != null }?.let {
            preferences.edit().putString(KEY_REVISION, UUID.randomUUID().toString()).commit()
            database.clearArchiveAcknowledgements(it.deliveryScope)
        }
    }

    fun allows(connection: ApiConnection, channel: String): Boolean = synchronized(CONFIG_LOCK) {
        if (connection != readConnection()) return@synchronized false
        val config = snapshot()
        if (!config.enabled || connection.serverId == null) return@synchronized false
        if (!database.channelReady(connection.deliveryScope, channel)) return@synchronized false
        when (channel) {
            "motion" -> config.capabilities?.motion != null && config.motionEnabled
            "metrics" -> config.capabilities?.metrics != null
            "realtime" -> config.capabilities?.realtime != null
            "analytics" -> config.capabilities?.analytics != null && config.analyticsApproved
            else -> false
        }
    }

    fun database() = database

    private fun removeHomeAssistantCredentials() {
        app.getSharedPreferences("home_assistant", Context.MODE_PRIVATE).edit().clear().commit()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { store ->
                if (store.containsAlias(OLD_HA_ALIAS)) store.deleteEntry(OLD_HA_ALIAS)
            }
        }
    }

    companion object {
        internal val CONFIG_LOCK = Any()
        private const val PREFERENCES = "self_hosted_api_credentials"
        private const val KEY_URL = "protected_base_url"
        private const val KEY_KEY = "protected_bearer_key"
        private const val KEY_AUTH_MODE = "auth_mode"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HTTP_CONFIRMED = "http_confirmed"
        private const val KEY_GPS_ENABLED = "gps_enabled"
        private const val KEY_REVISION = "configuration_revision"
        private const val OLD_HA_ALIAS = "riders_hub_home_assistant_credentials"

        fun destinationId(baseUrl: String): String = MessageDigest.getInstance("SHA-256")
            .digest(apiUrls(baseUrl).base.toString().toByteArray())
            .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }
}

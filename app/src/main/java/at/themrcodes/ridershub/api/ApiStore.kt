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
)

internal class ApiStore(
    context: Context,
    private val vault: ApiCredentialProtection = ApiVault(),
    private val database: ApiDatabase = ApiDatabase.get(context),
) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun connection(): ApiConnection? = synchronized(CONFIG_LOCK) { readConnection() }

    private fun readConnection(): ApiConnection? = try {
        val url = preferences.getString(KEY_URL, null)?.let { vault.decrypt(it, "base-url") }
            ?: return null
        val key = preferences.getString(KEY_KEY, null)?.let { vault.decrypt(it, "bearer-key") }
            ?: return null
        val destination = destinationId(url)
        ApiConnection(url, key, destination, database.serverId(destination),
            preferences.getString(KEY_REVISION, "") ?: "")
    } catch (_: Exception) {
        clearConfiguration()
        null
    }

    fun configure(baseUrl: String, bearerKey: String, confirmHttp: Boolean) = synchronized(CONFIG_LOCK) {
        val urls = apiUrls(baseUrl)
        require(bearerKey.isNotBlank()) { "Bearer key must not be blank" }
        require(urls.base.isHttps || confirmHttp) { "Confirm the unencrypted HTTP connection" }
        saveConfiguration(baseUrl, bearerKey, confirmHttp, ApiAuthMode.LEGACY_BEARER)
    }

    fun configureOidc(baseUrl: String, deviceToken: String) = synchronized(CONFIG_LOCK) {
        require(apiUrls(baseUrl).base.isHttps) { "SSO enrollment requires HTTPS" }
        require(deviceToken.isNotBlank()) { "Device token must not be blank" }
        saveConfiguration(baseUrl, deviceToken, false, ApiAuthMode.OIDC_DEVICE)
    }

    private fun saveConfiguration(
        baseUrl: String,
        bearerKey: String,
        confirmHttp: Boolean,
        authMode: ApiAuthMode,
    ) {
        val urls = apiUrls(baseUrl)
        val normalized = urls.base.toString().removeSuffix("/")
        check(preferences.edit()
            .putString(KEY_URL, vault.encrypt(normalized, "base-url"))
            .putString(KEY_KEY, vault.encrypt(bearerKey, "bearer-key"))
            .putString(KEY_AUTH_MODE, authMode.name)
            .putBoolean(KEY_HTTP_CONFIRMED, confirmHttp && !urls.base.isHttps)
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
            authMode = connection?.let {
                runCatching {
                    ApiAuthMode.valueOf(preferences.getString(KEY_AUTH_MODE, null)
                        ?: ApiAuthMode.LEGACY_BEARER.name)
                }.getOrDefault(ApiAuthMode.LEGACY_BEARER)
            },
            insecure = connection?.let { !apiUrls(it.baseUrl).base.isHttps } == true,
            httpConfirmed = preferences.getBoolean(KEY_HTTP_CONFIRMED, false),
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
        )
    }

    fun clearConfiguration() = synchronized(CONFIG_LOCK) {
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

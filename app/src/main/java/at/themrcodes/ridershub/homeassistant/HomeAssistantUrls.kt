package at.themrcodes.ridershub.homeassistant

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object HomeAssistantUrls {
    fun registrationUrl(instanceUrl: String): String =
        "${normalize(instanceUrl, "Home Assistant URL")}/api/mobile_app/registrations"

    fun webhookUrl(
        instanceUrl: String,
        webhookId: String,
        cloudhookUrl: String?,
        remoteUiUrl: String?,
    ): String {
        cloudhookUrl?.takeIf(String::isNotBlank)?.let { return validateWebhookUrl(it) }
        val baseUrl = remoteUiUrl?.takeIf(String::isNotBlank) ?: instanceUrl
        val encodedId = URLEncoder.encode(webhookId, StandardCharsets.UTF_8.toString())
        return validateWebhookUrl("${normalize(baseUrl, "Home Assistant URL")}/api/webhook/$encodedId")
    }

    fun validateWebhookUrl(webhookUrl: String): String = normalize(webhookUrl, "Webhook URL")

    private fun normalize(rawUrl: String, label: String): String {
        val value = rawUrl.trim().trimEnd('/')
        require(value.isNotEmpty()) { "$label is required" }
        val uri = runCatching { URI(value) }.getOrElse {
            throw IllegalArgumentException("$label is not a valid URL")
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "$label must not contain credentials, a query, or a fragment"
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        require(scheme == "https" || scheme == "http") { "$label must use HTTPS or HTTP" }
        require(!host.isNullOrBlank()) { "$label must include a host" }
        require(scheme == "https" || isLocalHost(host)) {
            "HTTP is only allowed for local Home Assistant addresses; use HTTPS otherwise"
        }
        return value
    }

    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host == "::1") return true
        if (host.startsWith("fc", ignoreCase = true) || host.startsWith("fd", ignoreCase = true) ||
            host.startsWith("fe8", ignoreCase = true) || host.startsWith("fe9", ignoreCase = true) ||
            host.startsWith("fea", ignoreCase = true) || host.startsWith("feb", ignoreCase = true)
        ) return true
        val parts = host.split('.').mapNotNull(String::toIntOrNull)
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 ||
            parts[0] == 127 ||
            parts[0] == 192 && parts[1] == 168 ||
            parts[0] == 172 && parts[1] in 16..31 ||
            parts[0] == 169 && parts[1] == 254
    }
}

internal fun isValidHomeAssistantWebhookUrl(value: String): Boolean =
    runCatching { HomeAssistantUrls.validateWebhookUrl(value) }.isSuccess

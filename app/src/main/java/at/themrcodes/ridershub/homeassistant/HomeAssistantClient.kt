package at.themrcodes.ridershub.homeassistant

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class HomeAssistantClient(
    private val crypto: HomeAssistantCrypto = HomeAssistantCrypto(),
) {
    fun register(
        instanceUrl: String,
        accessToken: String,
        deviceId: String,
        appVersion: String,
    ): HomeAssistantRegistration {
        require(accessToken.isNotBlank()) { "Long-lived access token is required" }
        val response = post(
            url = HomeAssistantUrls.registrationUrl(instanceUrl),
            payload = JSONObject()
                .put("device_id", deviceId)
                .put("app_id", "at.themrcodes.ridershub")
                .put("app_name", "Riders Hub")
                .put("app_version", appVersion)
                .put("device_name", "Riders Hub")
                .put("manufacturer", "Riders Hub")
                .put("model", "Android device")
                .put("os_name", "Android")
                .put("os_version", Build.VERSION.RELEASE)
                .put("supports_encryption", true),
            bearerToken = accessToken.trim(),
        )
        val webhookId = response.optionalString("webhook_id")
            ?: throw HomeAssistantProtocolException("Registration response had no webhook")
        val secret = response.optionalString("secret")
            ?.let(crypto::validateSecret)
            ?: throw HomeAssistantProtocolException("Registration response had no encryption key")
        return HomeAssistantRegistration(
            webhookUrl = HomeAssistantUrls.webhookUrl(
                instanceUrl = instanceUrl,
                webhookId = webhookId,
                cloudhookUrl = response.optionalString("cloudhook_url"),
                remoteUiUrl = response.optionalString("remote_ui_url"),
            ),
            encryptionSecret = secret,
        )
    }

    /** Permanently upgrades an existing mobile_app registration to encrypted webhooks. */
    fun enableEncryption(webhookUrl: String): String {
        val response = post(
            url = webhookUrl,
            payload = JSONObject().put("type", "enable_encryption"),
        )
        return response.optionalString("secret")
            ?.let(crypto::validateSecret)
            ?: throw HomeAssistantProtocolException("Encryption response had no key")
    }

    fun registerSensors(connection: HomeAssistantConnection) {
        sensorDefinitions().forEach { sensor ->
            val response = postWebhook(connection, "register_sensor", sensor)
            requireSuccess(response, "Entity registration")
        }
    }

    fun updateSensors(
        connection: HomeAssistantConnection,
        update: HomeAssistantUpdate,
    ): HomeAssistantUpdateResult {
        val batch = homeAssistantSensorBatch(update)
        val response = postWebhook(connection, "update_sensor_states", batch.values)
        return validateHomeAssistantSensorUpdate(response, batch.expectedIds)
    }

    fun fireTripEvent(connection: HomeAssistantConnection, event: HomeAssistantTripEvent) {
        val eventData = JSONObject()
            .put("at", event.at)
            .put("distance_km", roundDistance(event.distanceKm))
        postWebhook(
            connection = connection,
            type = "fire_event",
            data = JSONObject()
                .put("event_type", if (event.started) "riders_hub_trip_started" else "riders_hub_trip_ended")
                .put("event_data", eventData),
        )
    }

    private fun postWebhook(
        connection: HomeAssistantConnection,
        type: String,
        data: Any,
    ): JSONObject {
        val secret = connection.encryptionSecret
        val payload = if (secret == null) {
            JSONObject().put("type", type).put("data", data)
        } else {
            JSONObject()
                .put("type", type)
                .put("encrypted", true)
                .put("encrypted_data", crypto.encryptJson(data, secret))
        }
        val response = post(connection.webhookUrl, payload)
        if (!response.optBoolean("encrypted", false)) return response
        if (secret == null) throw HomeAssistantProtocolException("Unexpected encrypted response")
        val encoded = response.optionalString("encrypted_data")
            ?: throw HomeAssistantProtocolException("Encrypted response had no data")
        return crypto.decryptJson(encoded, secret) as? JSONObject
            ?: throw HomeAssistantProtocolException("Home Assistant returned an unexpected response")
    }

    private fun post(url: String, payload: JSONObject, bearerToken: String? = null): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            if (bearerToken != null) connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                throw HomeAssistantHttpException(status, body.errorCodeOrNull())
            }
            return if (body.isBlank()) JSONObject() else runCatching { JSONObject(body) }
                .getOrElse { throw HomeAssistantProtocolException("Home Assistant returned invalid JSON") }
        } finally {
            connection.disconnect()
        }
    }

    private fun requireSuccess(response: JSONObject, action: String) {
        if (!response.optBoolean("success", false)) {
            throw HomeAssistantProtocolException("$action rejected: ${response.errorCode() ?: "missing_result"}")
        }
    }

    private fun sensorDefinitions(): List<JSONObject> = listOf(
        sensorDefinition(
            id = ID_BOARD_BATTERY,
            name = "Board battery",
            type = "sensor",
            initialState = "unknown",
            icon = "mdi:battery",
            unit = "%",
            deviceClass = "battery",
            stateClass = "measurement",
        ),
        sensorDefinition(
            id = ID_ESTIMATED_RANGE,
            name = "Estimated range",
            type = "sensor",
            initialState = "unknown",
            icon = "mdi:map-marker-distance",
            unit = "km",
            deviceClass = "distance",
            stateClass = "measurement",
        ),
        sensorDefinition(
            id = ID_CURRENT_TRIP,
            name = "Current trip distance",
            type = "sensor",
            initialState = 0.0,
            icon = "mdi:map-marker-path",
            unit = "km",
            deviceClass = "distance",
            stateClass = "measurement",
        ),
        sensorDefinition(
            id = ID_LAST_UPDATE,
            name = "Last update",
            type = "sensor",
            initialState = "unknown",
            icon = "mdi:clock-outline",
            deviceClass = "timestamp",
        ),
        sensorDefinition(
            id = ID_IN_USE,
            name = "In use",
            type = "binary_sensor",
            initialState = false,
            icon = "mdi:skateboard",
        ),
    )

    private fun sensorDefinition(
        id: String,
        name: String,
        type: String,
        initialState: Any,
        icon: String,
        unit: String? = null,
        deviceClass: String? = null,
        stateClass: String? = null,
    ): JSONObject = JSONObject()
        .put("unique_id", id)
        .put("name", name)
        .put("type", type)
        .put("state", initialState)
        .put("icon", icon)
        .apply {
            unit?.let { put("unit_of_measurement", it) }
            deviceClass?.let { put("device_class", it) }
            stateClass?.let { put("state_class", it) }
        }

    companion object {
        private const val TIMEOUT_MS = 15_000
    }
}

internal class HomeAssistantHttpException(
    val statusCode: Int,
    val errorCode: String? = null,
) : IOException("Home Assistant returned HTTP $statusCode")

internal class HomeAssistantProtocolException(message: String) : IOException(message)

private fun JSONObject.optionalString(key: String): String? =
    optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }

private fun JSONObject.errorCode(): String? = optJSONObject("error")?.optionalString("code")

private fun String.errorCodeOrNull(): String? = runCatching { JSONObject(this).errorCode() }.getOrNull()

internal data class HomeAssistantRegistration(
    val webhookUrl: String,
    val encryptionSecret: String,
)

internal data class HomeAssistantUpdateResult(val disabledCount: Int)

internal data class HomeAssistantSensorBatch(
    val values: JSONArray,
    val expectedIds: List<String>,
)

internal fun homeAssistantSensorBatch(update: HomeAssistantUpdate): HomeAssistantSensorBatch {
    val values = JSONArray()
    val expectedIds = mutableListOf<String>()
    fun addState(id: String, type: String, state: Any) {
        values.put(
            JSONObject()
                .put("unique_id", id)
                .put("type", type)
                .put("state", state),
        )
        expectedIds += id
    }

    update.boardBatteryPercent?.let { addState(ID_BOARD_BATTERY, "sensor", it) }
    update.estimatedRangeKm?.let { addState(ID_ESTIMATED_RANGE, "sensor", roundDistance(it)) }
    addState(ID_CURRENT_TRIP, "sensor", roundDistance(update.currentTripKm))
    addState(ID_LAST_UPDATE, "sensor", update.updatedAt)
    addState(ID_IN_USE, "binary_sensor", update.inUse)
    return HomeAssistantSensorBatch(values, expectedIds)
}

internal fun validateHomeAssistantSensorUpdate(
    response: JSONObject,
    expectedIds: List<String>,
): HomeAssistantUpdateResult {
    val failedCodes = mutableListOf<String>()
    var disabledCount = 0
    expectedIds.forEach { id ->
        val result = response.optJSONObject(id)
        when {
            result == null -> failedCodes += "missing_result"
            !result.optBoolean("success", false) -> failedCodes += result.errorCode() ?: "rejected"
            result.optBoolean("is_disabled", false) -> disabledCount += 1
        }
    }
    if (failedCodes.isNotEmpty()) {
        throw HomeAssistantProtocolException(
            "Sensor update rejected: ${failedCodes.distinct().joinToString()}",
        )
    }
    return HomeAssistantUpdateResult(disabledCount)
}

internal data class HomeAssistantUpdate(
    val boardBatteryPercent: Int?,
    val estimatedRangeKm: Double?,
    val currentTripKm: Double,
    val inUse: Boolean,
    val updatedAt: String,
)

internal data class HomeAssistantTripEvent(
    val started: Boolean,
    val at: String,
    val distanceKm: Double,
)

private fun roundDistance(value: Double): Double = kotlin.math.round(value * 1_000.0) / 1_000.0

private const val ID_BOARD_BATTERY = "riders_hub_board_battery"
private const val ID_ESTIMATED_RANGE = "riders_hub_estimated_range"
private const val ID_CURRENT_TRIP = "riders_hub_current_trip_distance"
private const val ID_LAST_UPDATE = "riders_hub_last_update"
private const val ID_IN_USE = "riders_hub_in_use"

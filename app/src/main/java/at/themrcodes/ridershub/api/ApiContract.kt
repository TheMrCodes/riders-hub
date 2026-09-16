package at.themrcodes.ridershub.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.util.UUID

const val API_CONTRACT_VERSION = 1
const val METRICS_SCHEMA_VERSION = 1
const val REALTIME_SCHEMA_VERSION = 1
const val ANALYTICS_SCHEMA_VERSION = 1
const val REALTIME_SUBPROTOCOL = "riders-hub.realtime.v1"
const val OIDC_AUDIENCE = "ridershub-api"
const val OIDC_REGISTRATION_SCOPE = "ridershub.device.register"

data class OidcAuthConfig(
    val issuer: String,
    val clientId: String,
    val audience: String,
    val scopes: List<String>,
)

data class DeviceRegistration(
    val deviceId: String,
    val sourceId: String,
    val deviceToken: String,
)

data class MetricsCapability(val schemaVersion: Int)
data class RealtimeCapability(val schemaVersion: Int, val gps: Boolean)
data class AnalyticsCapability(
    val schemaVersion: Int,
    val rhaFormatVersions: Set<Int>,
    val coaFormatVersions: Set<Int>,
    val maxUploadBytes: Long,
)

data class ApiCapabilities(
    val contractVersion: Int,
    val serverId: String,
    val metrics: MetricsCapability?,
    val realtime: RealtimeCapability?,
    val analytics: AnalyticsCapability?,
) {
    init {
        require(contractVersion == API_CONTRACT_VERSION) { "Unsupported contract version $contractVersion" }
        require(serverId.isNotBlank()) { "server_id must not be blank" }
        require(metrics != null || realtime != null || analytics != null) {
            "Server advertised no usable capabilities"
        }
    }
}

data class ApiUrls(
    val base: HttpUrl,
    val authConfig: HttpUrl,
    val deviceRegistrations: HttpUrl,
    val capabilities: HttpUrl,
    val metrics: HttpUrl,
    val realtime: String,
) {
    fun analytics(sourceId: String, partitionSequence: Int): HttpUrl = base.newBuilder()
        .addPathSegments("v1/analytics/partitions")
        .addPathSegment(sourceId)
        .addPathSegment(partitionSequence.toString())
        .build()

    fun chargerFile(sourceId: String, fileId: String): HttpUrl = base.newBuilder()
        .addPathSegments("v1/analytics/charger-files")
        .addPathSegment(sourceId)
        .addPathSegment(fileId)
        .build()
}

fun apiUrls(value: String): ApiUrls {
    val parsed = value.trim().toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Enter an absolute HTTP or HTTPS base URL")
    require(parsed.scheme == "http" || parsed.scheme == "https") { "Only HTTP and HTTPS are supported" }
    require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Credentials must not be embedded in the URL" }
    require(parsed.query == null && parsed.fragment == null) { "Base URL must not contain a query or fragment" }
    // Preserve significant empty/escaped segments inside a reverse-proxy prefix.
    val normalized = parsed.newBuilder()
        .encodedPath(parsed.encodedPath.trimEnd('/').ifEmpty { "/" }).build()
    fun endpoint(path: String) = normalized.newBuilder().addPathSegments(path).build()
    val httpRealtime = endpoint("v1/realtime")
    val websocket = httpRealtime.toString().replaceFirst(
        if (normalized.isHttps) "https://" else "http://",
        if (normalized.isHttps) "wss://" else "ws://",
    )
    return ApiUrls(
        base = normalized,
        authConfig = endpoint("v1/auth/config"),
        deviceRegistrations = endpoint("v1/devices/registrations"),
        capabilities = endpoint("v1/capabilities"),
        metrics = endpoint("v1/metrics"),
        realtime = websocket,
    )
}

fun parseOidcAuthConfig(json: JSONObject): OidcAuthConfig {
    require(json.strictInt("schema_version") == 1) { "Unsupported authentication schema" }
    val issuer = (json.opt("issuer") as? String)?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing OIDC issuer")
    val parsedIssuer = issuer.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("OIDC issuer is not an absolute URL")
    require(parsedIssuer.isHttps && parsedIssuer.username.isEmpty() && parsedIssuer.password.isEmpty() &&
        parsedIssuer.query == null && parsedIssuer.fragment == null
    ) { "OIDC issuer must be an HTTPS URL without credentials, query, or fragment" }
    val clientId = (json.opt("client_id") as? String)?.takeIf {
        it.isNotBlank() && it.none(Char::isWhitespace)
    } ?: throw IllegalArgumentException("Missing OIDC client ID")
    val audience = json.opt("audience") as? String
    require(audience == OIDC_AUDIENCE) { "Unsupported OIDC audience" }
    val rawScopes = json.optJSONArray("scopes")
        ?: throw IllegalArgumentException("Missing OIDC scopes")
    val scopes = buildList {
        for (index in 0 until rawScopes.length()) {
            val scope = rawScopes.opt(index) as? String
                ?: throw IllegalArgumentException("Invalid OIDC scope")
            require(scope.isNotBlank() && scope.none(Char::isWhitespace)) { "Invalid OIDC scope" }
            add(scope)
        }
    }
    require(scopes.size == scopes.toSet().size && "openid" in scopes && OIDC_REGISTRATION_SCOPE in scopes) {
        "OIDC registration scopes are incomplete"
    }
    // Do not let a server expand the browser consent request with unrelated identity scopes.
    return OidcAuthConfig(issuer, clientId, audience, listOf("openid", OIDC_REGISTRATION_SCOPE))
}

fun parseDeviceRegistration(json: JSONObject, expectedSourceId: String): DeviceRegistration {
    require(json.strictInt("schema_version") == 1) { "Unsupported registration schema" }
    val deviceId = json.opt("device_id") as? String
        ?: throw IllegalArgumentException("Missing device_id")
    val sourceId = json.opt("source_id") as? String
        ?: throw IllegalArgumentException("Missing source_id")
    require(runCatching { UUID.fromString(deviceId) }.isSuccess) { "Invalid device_id" }
    require(sourceId == expectedSourceId) { "Registration source_id did not match" }
    require(json.opt("token_type") == "Bearer") { "Unsupported device token type" }
    val token = (json.opt("device_token") as? String)?.takeIf { DEVICE_TOKEN.matches(it) }
        ?: throw IllegalArgumentException("Invalid device token")
    return DeviceRegistration(deviceId, sourceId, token)
}

fun parseCapabilities(json: JSONObject): ApiCapabilities {
    val version = json.strictInt("contract_version") ?: -1
    require(version == API_CONTRACT_VERSION) { "Unsupported contract version $version" }
    val serverId = (json.opt("server_id") as? String)?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing server_id")
    val advertised = json.optJSONObject("capabilities")
        ?: throw IllegalArgumentException("Missing capabilities object")

    // An invalid individual capability is ignored so other independent channels remain usable.
    val metrics = advertised.optJSONObject("metrics")?.let { value ->
        value.strictInt("schema_version").takeIf { it == METRICS_SCHEMA_VERSION }
            ?.let(::MetricsCapability)
    }
    val realtime = advertised.optJSONObject("realtime")?.let { value ->
        val gps = value.opt("gps") as? Boolean
        value.strictInt("schema_version").takeIf { it == REALTIME_SCHEMA_VERSION && gps != null }
            ?.let { RealtimeCapability(it, requireNotNull(gps)) }
    }
    val analytics = advertised.optJSONObject("analytics")?.let { value ->
        val versions = value.optJSONArray("rha_format_versions")?.let(::strictVersionSet)
        val maxBytes = value.strictLong("max_upload_bytes")
        val coaVersions = value.optJSONArray("coa_format_versions")?.let(::strictVersionSet) ?: emptySet()
        value.strictInt("schema_version")
            .takeIf {
                it == ANALYTICS_SCHEMA_VERSION && versions != null && 1 in versions &&
                    maxBytes != null && maxBytes > 0
            }
            ?.let { AnalyticsCapability(it, requireNotNull(versions), coaVersions, requireNotNull(maxBytes)) }
    }
    return ApiCapabilities(version, serverId, metrics, realtime, analytics)
}

enum class MetricsKind(val wireValue: String) {
    RIDE_STARTED("ride.started"),
    SNAPSHOT("metrics.snapshot"),
    RIDE_ENDED("ride.ended"),
}

data class MetricSnapshot(
    val connection: String,
    val rideActive: Boolean,
    val tripDistanceKm: Double?,
    val odometerKm: Double?,
    val batteryReportedPercent: Int?,
    val batteryEstimatedPercent: Double?,
    val estimatedRangeKm: Double?,
    val lastBoardObservationAt: String?,
    val finalizedAt: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("connection", connection)
        .put("ride_active", rideActive)
        .putFinite("trip_distance_km", tripDistanceKm?.takeIf { it >= 0 })
        .putFinite("odometer_km", odometerKm?.takeIf { it >= 0 })
        .putOptional("battery_reported_percent", batteryReportedPercent?.takeIf { it in 0..100 })
        .putFinite("battery_estimated_percent", batteryEstimatedPercent?.takeIf { it in 0.0..100.0 })
        .putFinite("estimated_range_km", estimatedRangeKm?.takeIf { it >= 0 })
        .putOptional("last_board_observation_at", lastBoardObservationAt)
        .putOptional("finalized_at", finalizedAt)
        .putOptional("started_at", startedAt)
        .putOptional("ended_at", endedAt)
}

fun metricsEnvelope(
    sourceId: String,
    boardId: String,
    rideId: String?,
    appVersion: String,
    sequence: Long,
    kind: MetricsKind,
    occurredAt: String,
    snapshot: MetricSnapshot,
    messageId: String = UUID.randomUUID().toString(),
    sentAt: String = Instant.now().toString(),
): JSONObject = JSONObject()
    .put("schema_version", METRICS_SCHEMA_VERSION)
    .put("message_id", messageId)
    .put("sequence", sequence)
    .put("source_id", sourceId)
    .put("app_version", appVersion)
    .put("kind", kind.wireValue)
    .put("occurred_at", occurredAt)
    .put("sent_at", sentAt)
    .put("board_id", boardId)
    .putOptional("ride_id", rideId)
    .put("data", snapshot.toJson())

fun streamOpened(sourceId: String, boardId: String, rideId: String, streamId: String): JSONObject =
    JSONObject()
        .put("schema_version", REALTIME_SCHEMA_VERSION)
        .put("type", "stream.opened")
        .put("source_id", sourceId)
        .put("board_id", boardId)
        .put("ride_id", rideId)
        .put("stream_id", streamId)
        .put("opened_at", Instant.now().toString())

data class RealtimeLocation(
    val fixAt: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val observedElapsedNanos: Long,
)

data class RealtimeSample(
    val sourceId: String,
    val boardId: String,
    val rideId: String,
    val streamId: String,
    val captureSegmentId: String,
    val streamSequence: Long,
    val frameNumber: Long,
    val frameAt: String,
    val segmentElapsedMs: Long,
    val speedKmh: Double,
    val location: RealtimeLocation?,
) {
    fun toJson(nowElapsedNanos: Long): JSONObject = JSONObject()
        .put("schema_version", REALTIME_SCHEMA_VERSION)
        .put("type", "telemetry")
        .put("source_id", sourceId)
        .put("board_id", boardId)
        .put("ride_id", rideId)
        .put("stream_id", streamId)
        .put("capture_segment_id", captureSegmentId)
        .put("stream_sequence", streamSequence)
        .put("frame_number", frameNumber)
        .put("frame_at", frameAt)
        .put("segment_elapsed_ms", segmentElapsedMs)
        .putFinite("speed_kmh", speedKmh)
        .apply {
            location?.takeIf {
                it.observedElapsedNanos >= 0 && nowElapsedNanos >= it.observedElapsedNanos &&
                    nowElapsedNanos - it.observedElapsedNanos <= 5_000_000_000L &&
                    it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
                    it.longitude.isFinite() && it.longitude in -180.0..180.0
            }
                ?.let { fix ->
                    put("location", JSONObject()
                        .put("fix_at", fix.fixAt)
                        .putFinite("latitude", fix.latitude)
                        .putFinite("longitude", fix.longitude)
                        .putFinite("altitude_m", fix.altitudeM))
                }
        }
}

internal fun JSONObject.putOptional(key: String, value: Any?): JSONObject = apply {
    if (value != null) put(key, value)
}

internal fun JSONObject.putFinite(key: String, value: Double?): JSONObject = apply {
    if (value != null && value.isFinite()) put(key, value)
}

private fun JSONObject.strictInt(key: String): Int? = strictInt(opt(key))

private fun JSONObject.strictLong(key: String): Long? = (opt(key) as? Number)?.let { number ->
    runCatching { number.toString().toBigDecimal().longValueExact() }.getOrNull()
}

private fun strictInt(value: Any?): Int? = (value as? Number)?.let { number ->
    runCatching { number.toString().toBigDecimal().intValueExact() }.getOrNull()
}

private fun strictVersionSet(array: JSONArray): Set<Int>? {
    val values = mutableSetOf<Int>()
    for (index in 0 until array.length()) values += strictInt(array.opt(index)) ?: return null
    return values
}

private val DEVICE_TOKEN = Regex("^rhd_[A-Za-z0-9_-]{43}$")

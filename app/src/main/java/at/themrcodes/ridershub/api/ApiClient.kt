package at.themrcodes.ridershub.api

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ApiClient private constructor(
    private val allowed: (ApiConnection, String) -> Boolean,
    private val client: OkHttpClient = defaultClient(),
) {
    constructor(allowed: (ApiConnection, String) -> Boolean = { _, _ -> true }) :
        this(allowed, defaultClient())

    init { synchronized(CLIENTS) { CLIENTS.add(client) } }

    fun authConfig(baseUrl: String): OidcAuthConfig {
        val urls = apiUrls(baseUrl)
        require(urls.base.isHttps) { "SSO enrollment requires HTTPS" }
        val request = Request.Builder().url(urls.authConfig).get().build()
        client.newCall(request).execute().use { response ->
            acknowledged(response)
            return runCatching { parseOidcAuthConfig(readBoundedJson(response)) }
                .getOrElse { throw IOException("Invalid authentication configuration", it) }
        }
    }

    fun registerDevice(baseUrl: String, accessToken: String, sourceId: String): DeviceRegistration {
        val urls = apiUrls(baseUrl)
        require(urls.base.isHttps) { "SSO enrollment requires HTTPS" }
        require(accessToken.isNotBlank() && accessToken.none { it.isWhitespace() }) {
            "OIDC access token is invalid"
        }
        val payload = JSONObject()
            .put("schema_version", 1)
            .put("source_id", sourceId)
            .toString()
        val request = Request.Builder().url(urls.deviceRegistrations)
            .header("Authorization", "Bearer $accessToken")
            .post(payload.toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code !in 200..299) {
                val publicError = runCatching { readBoundedJson(response).optString("error") }
                    .getOrNull()
                    ?.takeIf { it == "OIDC provider is unavailable" || it == "device registration is unavailable" }
                throw ApiHttpException(response.code, response.header("Retry-After"), publicError)
            }
            return runCatching { parseDeviceRegistration(readBoundedJson(response), sourceId) }
                .getOrElse { throw IOException("Invalid device registration response", it) }
        }
    }

    fun capabilities(connection: ApiConnection): Pair<JSONObject, ApiCapabilities> {
        val request = authenticated(connection, apiUrls(connection.baseUrl).capabilities).get().build()
        client.newCall(request).execute().use { response ->
            acknowledged(response)
            // Discovery is small configuration data, never an unbounded response allocation.
            val source = response.body.source()
            source.request(MAX_DISCOVERY_BYTES + 1)
            if (source.buffer.size > MAX_DISCOVERY_BYTES) throw IOException("Capability response is too large")
            val body = source.buffer.readUtf8()
            val json = runCatching { JSONObject(body) }
                .getOrElse { throw IOException("Capability response was not valid JSON", it) }
            return json to runCatching { parseCapabilities(json) }
                .getOrElse { throw IOException("Invalid capability response", it) }
        }
    }

    private fun readBoundedJson(response: Response): JSONObject {
        val source = response.body.source()
        source.request(MAX_DISCOVERY_BYTES + 1)
        if (source.buffer.size > MAX_DISCOVERY_BYTES) throw IOException("JSON response is too large")
        return runCatching { JSONObject(source.buffer.readUtf8()) }
            .getOrElse { throw IOException("Response was not valid JSON", it) }
    }

    fun postMotion(connection: ApiConnection, payload: String): Int =
        executeAcknowledged(connection, "motion", authenticated(connection, apiUrls(connection.baseUrl).motionBatches())
            .post(payload.toRequestBody(JSON)).build())

    fun postMetric(connection: ApiConnection, payload: String): Int =
        executeAcknowledged(connection, "metrics", metricRequest(connection, payload))

    suspend fun postMetricDurable(connection: ApiConnection, payload: String): Int =
        executeAcknowledgedAsync(connection, "metrics", metricRequest(connection, payload))

    private fun metricRequest(connection: ApiConnection, payload: String) =
        authenticated(connection, apiUrls(connection.baseUrl).metrics).post(payload.toRequestBody(JSON)).build()

    fun uploadArchive(connection: ApiConnection, sourceId: String, partitionSequence: Int, file: File,
                      entries: Int, final: Boolean, compressedDigest: String, uncompressedDigest: String): Int {
        val request = archiveRequest(connection, sourceId, partitionSequence,
            file, entries, final, compressedDigest, uncompressedDigest)
        if (final && archiveAlreadyStored { executeAcknowledged(connection, "analytics", request.newBuilder().head().build()) }) return 204
        return executeAcknowledged(connection, "analytics", request)
    }

    suspend fun uploadArchiveDurable(connection: ApiConnection, sourceId: String, partitionSequence: Int, file: File,
                                     entries: Int, final: Boolean, compressedDigest: String, uncompressedDigest: String): Int {
        val request = archiveRequest(connection, sourceId, partitionSequence,
            file, entries, final, compressedDigest, uncompressedDigest)
        if (final && archiveAlreadyStored { executeAcknowledgedAsync(connection, "analytics", request.newBuilder().head().build()) }) return 204
        return executeAcknowledgedAsync(connection, "analytics", request)
    }

    // A missing archive or an older server without HEAD support falls back to PUT.
    // Authentication, overload and network failures retain the normal retry policy.
    private inline fun archiveAlreadyStored(check: () -> Int): Boolean = try {
        check() == 204
    } catch (error: ApiHttpException) {
        if (error.statusCode == 404 || error.statusCode == 405) false else throw error
    }

    private fun archiveRequest(connection: ApiConnection, sourceId: String, partitionSequence: Int, file: File,
                               entries: Int, final: Boolean, compressedDigest: String, uncompressedDigest: String) =
        authenticated(connection, apiUrls(connection.baseUrl).analytics(sourceId, partitionSequence))
            .put(file.asRequestBody(RHA))
            .header("Riders-Hub-Partition-Entries", entries.toString())
            .header("Riders-Hub-Partition-Final", final.toString())
            .header("Content-Digest", "sha-256=:$compressedDigest:")
            .header("Riders-Hub-Uncompressed-Digest", "sha-256=:$uncompressedDigest:").build()

    suspend fun uploadChargerFileDurable(
        connection: ApiConnection,
        sourceId: String,
        fileId: String,
        file: File,
        records: Int,
        digest: String,
    ): Int = executeAcknowledgedAsync(
        connection,
        "analytics",
        authenticated(connection, apiUrls(connection.baseUrl).chargerFile(sourceId, fileId))
            .put(file.asRequestBody(COA))
            .header("Riders-Hub-Charger-Records", records.toString())
            .header("Content-Digest", "sha-256=:$digest:")
            .build(),
    )

    fun openRealtime(connection: ApiConnection, listener: WebSocketListener): WebSocket =
        synchronized(ApiStore.CONFIG_LOCK) {
            checkAllowed(connection, "realtime")
            client.newWebSocket(Request.Builder().url(apiUrls(connection.baseUrl).realtime)
                .header("Authorization", "Bearer ${connection.bearerKey}")
                .header("Sec-WebSocket-Protocol", REALTIME_SUBPROTOCOL).build(), listener)
                .also { SOCKETS.add(it) }
        }

    private fun authenticated(connection: ApiConnection, url: okhttp3.HttpUrl) =
        Request.Builder().url(url).header("Authorization", "Bearer ${connection.bearerKey}")

    private fun newCall(connection: ApiConnection, channel: String, request: Request): Call =
        synchronized(ApiStore.CONFIG_LOCK) {
            checkAllowed(connection, channel)
            client.newCall(request).also {
                if (channel == "analytics") it.timeout().timeout(8, TimeUnit.MINUTES)
                if (channel == "motion") synchronized(MOTION_CALLS) {
                    checkAllowed(connection, channel)
                    MOTION_CALLS.add(it)
                }
                CALLS.add(it)
            }
        }

    private fun forget(call: Call) { synchronized(ApiStore.CONFIG_LOCK) { CALLS.remove(call); synchronized(MOTION_CALLS) { MOTION_CALLS.remove(call) } } }

    private fun executeAcknowledged(connection: ApiConnection, channel: String, request: Request): Int {
        val call = newCall(connection, channel, request)
        return try { call.execute().use(::acknowledged) } finally { forget(call) }
    }

    private suspend fun executeAcknowledgedAsync(connection: ApiConnection, channel: String, request: Request): Int {
        val call = newCall(connection, channel, request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel(); forget(call) }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    forget(call)
                    continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try { continuation.resume(response.use(::acknowledged)) }
                    catch (error: Exception) { continuation.resumeWithException(error) }
                    finally { forget(call) }
                }
            })
        }
    }

    private fun acknowledged(response: Response): Int {
        if (response.code !in 200..299) throw ApiHttpException(response.code, response.header("Retry-After"))
        return response.code
    }

    private fun checkAllowed(connection: ApiConnection, channel: String) {
        if (!allowed(connection, channel)) throw ApiDeliveryCancelled()
    }

    companion object {
        private const val MAX_DISCOVERY_BYTES = 64L * 1024L
        private val JSON = "application/json".toMediaType()
        private val RHA = "application/vnd.ridershub.rha;version=1".toMediaType()
        private val COA = "application/vnd.ridershub.coa;version=1".toMediaType()
        private val CLIENTS = Collections.newSetFromMap(WeakHashMap<OkHttpClient, Boolean>())
        private val CALLS = mutableSetOf<Call>()
        private val MOTION_CALLS = mutableSetOf<Call>()
        fun cancelMotionTransports() = synchronized(MOTION_CALLS) {
            MOTION_CALLS.toList().forEach { it.cancel() }
        }
        private val SOCKETS = Collections.newSetFromMap(WeakHashMap<WebSocket, Boolean>())

        private fun defaultClient() = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build()

        internal fun withClientForTest(client: OkHttpClient) =
            ApiClient({ _, _ -> true }, client)

        fun cancelAllTransports() = synchronized(ApiStore.CONFIG_LOCK) {
            CALLS.forEach { it.cancel() }
            SOCKETS.toList().forEach { it.cancel() }
            SOCKETS.clear()
            synchronized(CLIENTS) { CLIENTS.toList() }.forEach { it.dispatcher.cancelAll() }
        }
    }
}

internal class ApiDeliveryCancelled : IOException("API delivery configuration is no longer active")

internal class ApiHttpException(
    val statusCode: Int,
    val retryAfter: String? = null,
    val publicError: String? = null,
) : IOException("HTTP $statusCode") {
    val retryable: Boolean get() = statusCode == 408 || statusCode == 429 || statusCode >= 500
    val unauthorized: Boolean get() = statusCode == 401 || statusCode == 403

    fun retryAfterMillis(nowEpochMs: Long = System.currentTimeMillis()): Long? {
        val value = retryAfter?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let {
            return it.coerceIn(0, Long.MAX_VALUE / 1_000L) * 1_000L
        }
        return runCatching {
            (java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli() - nowEpochMs).coerceAtLeast(0)
        }.getOrNull()
    }
}

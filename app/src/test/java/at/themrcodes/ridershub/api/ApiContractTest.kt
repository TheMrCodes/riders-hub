package at.themrcodes.ridershub.api

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.util.concurrent.TimeUnit
import java.io.File

class ApiContractTest {
    @Test fun realtimeCarriesRawFrameBytes() {
        val sample = RealtimeSample("source", "board", "ride", "stream", "segment", 1, 1,
            "2030-01-01T00:00:00.123456789Z", 0, 5.0, null, "ac0019")
        val json = sample.toJson(0)
        assertEquals("ac0019", json.getString("raw_hex"))
        assertEquals("2030-01-01T00:00:00.123456789Z", json.getString("frame_at"))
    }

    @Test
    fun finalizedArchiveLookupSkipsBytesAcrossInstallations() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val missing = File("synthetic-archive-not-read.rha")
            val result = ApiClient().uploadArchiveDurable(
                ApiConnection(server.url("/").toString(), "synthetic", "destination"),
                "20000000-0000-4000-8000-000000000002", 1, missing, 2, true, "compressed", "logical",
            )
            assertEquals(204, result)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("HEAD", request.method)
            assertEquals(0L, request.bodySize)
            assertEquals("sha-256=:logical:", request.getHeader("Riders-Hub-Uncompressed-Digest"))
        }
    }

    @Test
    fun archiveLookupMissOrOldServerFallsBackToUpload() = runBlocking {
        for (status in listOf(404, 405)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status))
            server.enqueue(MockResponse().setResponseCode(204))
            val file = File.createTempFile("synthetic-archive", ".rha")
            try {
                file.writeText("synthetic archive")
                assertEquals(204, ApiClient().uploadArchiveDurable(
                    ApiConnection(server.url("/").toString(), "synthetic", "destination"),
                    "20000000-0000-4000-8000-000000000002", 1, file, 2, true, "compressed", "logical",
                ))
                assertEquals("HEAD", server.takeRequest().method)
                val upload = server.takeRequest()
                assertEquals("PUT", upload.method)
                assertEquals("synthetic archive", upload.body.readUtf8())
            } finally { file.delete() }
        }
    }

    @Test
    fun archiveLookupFailureDoesNotSendArchive() = runBlocking {
        for (status in listOf(401, 403, 429, 503)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status))
            try {
                ApiClient().uploadArchiveDurable(
                    ApiConnection(server.url("/").toString(), "synthetic", "destination"),
                    "20000000-0000-4000-8000-000000000002", 1, File("synthetic-unread.rha"), 2, true, "compressed", "logical",
                )
                throw AssertionError("Expected lookup failure")
            } catch (e: ApiHttpException) { assertEquals(status, e.statusCode) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun stoppingMotionDoesNotCancelAnUnrelatedMetricUpload() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(300, TimeUnit.MILLISECONDS))
            val client = ApiClient.withClientForTest(OkHttpClient.Builder().build())
            val connection = ApiConnection(server.url("/").toString(), "synthetic", "destination")
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val pending = executor.submit<Int> { client.postMetric(connection, "{}") }
                assertEquals("/v1/metrics", server.takeRequest(5, TimeUnit.SECONDS)?.path)
                ApiClient.cancelMotionTransports()
                assertEquals(204, pending.get(5, TimeUnit.SECONDS).toInt())
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun remoteDisconnectCancelsAnInFlightMotionUpload() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = ApiClient.withClientForTest(OkHttpClient.Builder().build())
            val connection = ApiConnection(server.url("/").toString(), "synthetic", "destination")
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val pending = executor.submit<Boolean> {
                    runCatching { client.postMotion(connection, "{}") }.isFailure
                }
                assertEquals("/v1/motion/batches", server.takeRequest(5, TimeUnit.SECONDS)?.path)
                ApiClient.cancelMotionTransports()
                assertTrue(pending.get(5, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

    @Test
    fun oidcEnrollmentUsesHttpsBasePathAccessTokenAndSourceIdentity() {
        val heldCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCertificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(heldCertificate.certificate)
            .build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("""{
                "schema_version":1,
                "issuer":"https://identity.example.test/realms/ridershub",
                "client_id":"ridershub-android",
                "audience":"ridershub-api",
                "scopes":["openid","ridershub.device.register"]
            }""".trimIndent()))
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{
                "schema_version":1,
                "device_id":"70000000-0000-4000-8000-000000000007",
                "source_id":"20000000-0000-4000-8000-000000000002",
                "token_type":"Bearer",
                "device_token":"rhd_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            }""".trimIndent()))
            val client = ApiClient.withClientForTest(OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .followRedirects(false).followSslRedirects(false).build())
            val baseUrl = server.url("/prefix/").toString()
            val config = client.authConfig(baseUrl)
            assertEquals(OIDC_AUDIENCE, config.audience)
            assertEquals(listOf("openid", OIDC_REGISTRATION_SCOPE), config.scopes)
            val configRequest = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("/prefix/v1/auth/config", configRequest.path)
            assertNull(configRequest.getHeader("Authorization"))

            val registration = client.registerDevice(
                baseUrl,
                "synthetic-short-lived-access-token",
                "20000000-0000-4000-8000-000000000002",
            )
            assertEquals("rhd_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", registration.deviceToken)
            val registrationRequest = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("/prefix/v1/devices/registrations", registrationRequest.path)
            assertEquals("Bearer synthetic-short-lived-access-token", registrationRequest.getHeader("Authorization"))
            assertEquals(
                "20000000-0000-4000-8000-000000000002",
                JSONObject(registrationRequest.body.readUtf8()).getString("source_id"),
            )
        }
    }

    @Test
    fun oidcEnrollmentRejectsHttpMalformedConfigAndRedirects() {
        MockWebServer().use { server ->
            assertThrows(IllegalArgumentException::class.java) {
                ApiClient().authConfig(server.url("/").toString())
            }
            assertEquals(0, server.requestCount)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseOidcAuthConfig(JSONObject("""{
                "schema_version":1,
                "issuer":"https://identity.example.test/realms/ridershub",
                "client_id":"ridershub-android",
                "audience":"ridershub-api",
                "scopes":["openid"]
            }""".trimIndent()))
        }

        val heldCertificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(heldCertificate).build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(heldCertificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.enqueue(MockResponse().setResponseCode(307)
                .setHeader("Location", "https://other.example.test/v1/auth/config"))
            val client = ApiClient.withClientForTest(OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .followRedirects(false).followSslRedirects(false).build())
            val error = assertThrows(ApiHttpException::class.java) {
                client.authConfig(server.url("/").toString())
            }
            assertEquals(307, error.statusCode)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun deviceRegistrationExposesOnlyKnownSafeServerErrors() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.enqueue(MockResponse().setResponseCode(503)
                .setBody("""{"error":"OIDC provider is unavailable"}"""))
            server.enqueue(MockResponse().setResponseCode(503)
                .setBody("""{"error":"private account detail"}"""))
            val client = ApiClient.withClientForTest(OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .followRedirects(false).followSslRedirects(false).build())
            val baseUrl = server.url("/").toString()
            val sourceId = "20000000-0000-4000-8000-000000000002"
            val first = assertThrows(ApiHttpException::class.java) {
                client.registerDevice(baseUrl, "synthetic-access-token", sourceId)
            }
            assertEquals(503, first.statusCode)
            assertEquals("OIDC provider is unavailable", first.publicError)
            val second = assertThrows(ApiHttpException::class.java) {
                client.registerDevice(baseUrl, "synthetic-access-token", sourceId)
            }
            assertNull(second.publicError)
        }
    }

    @Test
    fun chargerArchiveUploadUsesDedicatedMediaTypeAndIntegrityHeaders() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val file = File.createTempFile("synthetic-charger-", ".coa").apply { writeText("{}\n") }
            try {
                ApiClient().uploadChargerFileDurable(
                    ApiConnection(server.url("/prefix/").toString(), "synthetic-secret", "destination"),
                    "source", "file", file, 2, "YWJj",
                )
                val request = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                assertEquals("PUT", request.method)
                assertEquals("/prefix/v1/analytics/charger-files/source/file", request.path)
                assertEquals("application/vnd.ridershub.coa;version=1", request.getHeader("Content-Type"))
                assertEquals("2", request.getHeader("Riders-Hub-Charger-Records"))
                assertEquals("sha-256=:YWJj:", request.getHeader("Content-Digest"))
            } finally { file.delete() }
        }
    }
    @Test
    fun durableHttpCallCancelsWithItsWorkerCoroutine() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val connection = ApiConnection(server.url("/").toString(), "synthetic-secret", "destination")
            val job = launch(Dispatchers.IO) { ApiClient().postMetricDurable(connection, "{}") }
            assertTrue(server.takeRequest(3, TimeUnit.SECONDS) != null)
            withTimeout(3_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }

    @Test
    fun discoveryRejectsUnboundedResponseAndOversizedIntegerCapability() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(" ".repeat(65_537)))
            assertThrows(java.io.IOException::class.java) {
                ApiClient().capabilities(ApiConnection(server.url("/").toString(), "synthetic", "destination"))
            }
        }
        val caps = parseCapabilities(JSONObject("""{
            "contract_version":1,"server_id":"synthetic",
            "capabilities":{"metrics":{"schema_version":1},
            "analytics":{"schema_version":1,"rha_format_versions":[1],"max_upload_bytes":9223372036854775808}}
        }"""))
        assertNull(caps.analytics)
    }
    @Test
    fun reverseProxyPrefixPreservesEmptyAndEscapedSegments() {
        assertEquals("https://api.example.test/a//b%2Fc/v1/metrics",
            apiUrls("https://api.example.test/a//b%2Fc/").metrics.toString())
    }

    @Test
    fun invalidLocationIsOmittedAsAWholeAndDisconnectedRideUsesReconnectState() {
        val sample = RealtimeSample("source", "board", "ride", "stream", "segment", 1, 1,
            "2030-01-01T00:00:00Z", 1000, 12.0,
            RealtimeLocation("2030-01-01T00:00:00Z", Double.NaN, 0.0, null, 0))
        assertFalse(sample.toJson(1_000_000_000).has("location"))
        assertEquals("reconnecting", connectionWireState("Disconnected — retry in 2s", true))
        assertEquals("standby", connectionWireState("Disconnected", false))
        assertEquals("live", connectionWireState("Listening for telemetry", true))
    }

    @Test
    fun revokedDeliveryGuardPreventsAnHttpRequest() {
        MockWebServer().use { server ->
            val connection = ApiConnection(server.url("/").toString(), "synthetic-secret", "destination")
            assertThrows(ApiDeliveryCancelled::class.java) {
                ApiClient { _, _ -> false }.postMetric(connection, "{}")
            }
            assertEquals(0, server.requestCount)
        }
    }
    @Test
    fun basePathIsPreservedAndWebSocketSchemeIsDerived() {
        val secure = apiUrls("https://api.example.test/custom/prefix/")
        assertEquals("https://api.example.test/custom/prefix/v1/capabilities", secure.capabilities.toString())
        assertEquals("wss://api.example.test/custom/prefix/v1/realtime", secure.realtime)
        assertEquals(
            "https://api.example.test/custom/prefix/v1/analytics/charger-files/source/file",
            secure.chargerFile("source", "file").toString(),
        )

        val cleartext = apiUrls("http://127.0.0.1:8080/root")
        assertTrue(cleartext.realtime.startsWith("ws://"))
        assertThrows(IllegalArgumentException::class.java) { apiUrls("https://user:pass@example.test") }
        assertThrows(IllegalArgumentException::class.java) { apiUrls("https://example.test?token=bad") }
    }

    @Test
    fun chargerArchiveCapabilityIsOptionalAndVersioned() {
        val parsed = parseCapabilities(JSONObject("""{
          "contract_version":1,"server_id":"synthetic-server",
          "capabilities":{"analytics":{"schema_version":1,"rha_format_versions":[1],
          "coa_format_versions":[1],"max_upload_bytes":4096}}
        }"""))
        assertEquals(setOf(1), parsed.analytics?.coaFormatVersions)
        val legacy = parseCapabilities(JSONObject("""{
          "contract_version":1,"server_id":"synthetic-server",
          "capabilities":{"analytics":{"schema_version":1,"rha_format_versions":[1],"max_upload_bytes":4096}}
        }"""))
        assertTrue(legacy.analytics?.coaFormatVersions.isNullOrEmpty())
    }

    @Test
    fun malformedCapabilityDoesNotDisableValidSibling() {
        val parsed = parseCapabilities(JSONObject("""
            {
              "contract_version": 1,
              "server_id": "synthetic-server",
              "capabilities": {
                "metrics": {"schema_version": 9},
                "realtime": {"schema_version": 1, "gps": true},
                "analytics": {"schema_version": 1, "rha_format_versions": [2], "max_upload_bytes": 1000},
                "future": {"schema_version": 1}
              }
            }
        """))
        assertNull(parsed.metrics)
        assertEquals(true, parsed.realtime?.gps)
        assertNull(parsed.analytics)
    }

    @Test
    fun capabilityValuesAreValidatedWithoutJsonCoercion() {
        val parsed = parseCapabilities(JSONObject("""
            {
              "contract_version": 1,
              "server_id": "synthetic-server",
              "capabilities": {
                "metrics": {"schema_version": 1},
                "realtime": {"schema_version": 1, "gps": "true"},
                "analytics": {
                  "schema_version": 1,
                  "rha_format_versions": [1, "2"],
                  "max_upload_bytes": "4096"
                }
              }
            }
        """))
        assertTrue(parsed.metrics != null)
        assertNull(parsed.realtime)
        assertNull(parsed.analytics)

        assertThrows(IllegalArgumentException::class.java) {
            parseCapabilities(JSONObject("""{
              "contract_version":"1",
              "server_id":"synthetic-server",
              "capabilities":{"metrics":{"schema_version":1}}
            }"""))
        }
    }

    @Test
    fun discoveryRequiresSupportedTopLevelVersionAndOneUsableChannel() {
        assertThrows(IllegalArgumentException::class.java) {
            parseCapabilities(JSONObject("""{"contract_version":2,"server_id":"x","capabilities":{"metrics":{"schema_version":1}}}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseCapabilities(JSONObject("""{"contract_version":1,"server_id":"x","capabilities":{"metrics":{"schema_version":2}}}"""))
        }
    }

    @Test
    fun metricSnapshotOmitsUnavailableAndNonFiniteValues() {
        val json = MetricSnapshot(
            connection = "live",
            rideActive = true,
            tripDistanceKm = 2.5,
            odometerKm = Double.NaN,
            batteryReportedPercent = null,
            batteryEstimatedPercent = Double.POSITIVE_INFINITY,
            estimatedRangeKm = 8.0,
            lastBoardObservationAt = "2030-01-01T00:00:00Z",
        ).toJson()
        assertEquals(2.5, json.getDouble("trip_distance_km"), 0.0)
        assertFalse(json.has("odometer_km"))
        assertFalse(json.has("battery_reported_percent"))
        assertFalse(json.has("battery_estimated_percent"))
    }

    @Test
    fun realtimeProjectionOmitsStaleLocationAndExcludedTelemetry() {
        val sample = RealtimeSample(
            "source", "board", "ride", "stream", "segment", 1, 2,
            "2030-01-01T00:00:00Z", 1000, 12.0,
            RealtimeLocation("2030-01-01T00:00:00Z", 0.0, 0.0, null, 1_000_000_000L),
        )
        val fresh = sample.toJson(5_999_999_999L)
        assertTrue(fresh.has("location"))
        assertFalse(fresh.has("battery_reported_percent"))
        assertFalse(fresh.has("voltage"))
        assertFalse(fresh.has("raw"))
        assertFalse(sample.toJson(6_000_000_001L).has("location"))
    }

    @Test
    fun clientSendsBearerPreservesBasePathAndRejectsRedirect() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"contract_version":1,"server_id":"synthetic","capabilities":{"metrics":{"schema_version":1}}}
            """.trimIndent()))
            val connection = ApiConnection(
                server.url("/prefix").toString(), "synthetic-secret", "destination",
            )
            val capabilities = ApiClient().capabilities(connection).second
            assertTrue(capabilities.metrics != null)
            val request = server.takeRequest()
            assertEquals("/prefix/v1/capabilities", request.path)
            assertEquals("Bearer synthetic-secret", request.getHeader("Authorization"))
        }

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "https://other.example.test/v1/capabilities"))
            val connection = ApiConnection(server.url("/").toString(), "synthetic-secret", "destination")
            val error = assertThrows(ApiHttpException::class.java) { ApiClient().capabilities(connection) }
            assertEquals(307, error.statusCode)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun metricUploadTreatsAnyTwoHundredResponseAsAcknowledged() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(202))
            val connection = ApiConnection(
                server.url("/self-hosted").toString(), "synthetic-secret", "destination",
            )
            assertEquals(202, ApiClient().postMetric(connection, "{}"))
            val request = server.takeRequest()
            assertEquals("/self-hosted/v1/metrics", request.path)
            assertEquals("Bearer synthetic-secret", request.getHeader("Authorization"))
            assertTrue(request.getHeader("Content-Type")?.startsWith("application/json") == true)
        }
    }

    @Test
    fun retryAfterSupportsDeltaSecondsAndHttpDate() {
        assertEquals(12_000L, ApiHttpException(429, "12").retryAfterMillis(0))
        assertEquals(
            60_000L,
            ApiHttpException(503, "Tue, 01 Jan 2030 00:01:00 GMT")
                .retryAfterMillis(1_893_456_000_000L),
        )
    }

    @Test
    fun lifecycleRetryUpdatesOnlyTransmissionTime() {
        val stored = JSONObject()
            .put("message_id", "00000000-0000-4000-8000-000000000001")
            .put("sequence", 12)
            .put("occurred_at", "2030-01-01T00:00:00Z")
            .put("sent_at", "2030-01-01T00:00:01Z")
        val retried = JSONObject(metricPayloadForAttempt(stored.toString(), "2030-01-01T00:01:00Z"))
        assertEquals(stored.getString("message_id"), retried.getString("message_id"))
        assertEquals(stored.getLong("sequence"), retried.getLong("sequence"))
        assertEquals(stored.getString("occurred_at"), retried.getString("occurred_at"))
        assertEquals("2030-01-01T00:01:00Z", retried.getString("sent_at"))
    }
}

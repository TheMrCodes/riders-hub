package at.themrcodes.ridershub.api

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class ApiStoreTest {
    private val app = RuntimeEnvironment.getApplication()
    private val database = ApiDatabase(app)
    // Configuration state tests inject protection; production always uses the Android Keystore.
    private var decryptions = 0
    private val protection = object : ApiCredentialProtection {
        override fun encrypt(value: String, purpose: String) = "$purpose|$value"
        override fun decrypt(value: String, purpose: String): String {
            decryptions++
            return value.removePrefix("$purpose|")
        }
    }
    private val store = ApiStore(app, protection, database)

    @After fun close() { store.clearConfiguration(); database.close(); app.deleteDatabase("self_hosted_api.db") }

    @Test fun destinationSwitchAndChangedServerInvalidateCapturedDeliveryAndConsent() {
        val first = configure("https://one.example.test/prefix", "server-a")
        store.approveAnalytics(true)
        assertTrue(store.allows(first, "analytics"))
        val second = configure("https://two.example.test/prefix", "server-a")
        assertFalse(store.allows(first, "metrics"))
        assertFalse(store.allows(second, "analytics"))
        val returned = configure("https://one.example.test/prefix", "server-a")
        assertTrue(store.snapshot().analyticsApproved)
        assertFalse(store.allows(first, "metrics"))
        assertTrue(store.allows(returned, "metrics"))
        capabilities("server-b")
        assertFalse(store.allows(returned, "metrics"))
        assertFalse(store.snapshot().analyticsApproved)
    }

    @Test fun keyRotationKeepsDeliveryScopeButRejectsOldCredentialRequests() {
        val old = configure("https://one.example.test", "server-a")
        store.approveAnalytics(true)
        store.configureOidc("https://one.example.test", "rhd_" + "B".repeat(43))
        val rotated = requireNotNull(store.connection())
        assertEquals(old.deliveryScope, rotated.deliveryScope)
        assertNotEquals(old.revision, rotated.revision)
        assertFalse(store.allows(old, "metrics"))
        assertTrue(store.allows(rotated, "analytics"))
        store.setEnabled(false)
        assertFalse(store.allows(rotated, "metrics"))
        assertFalse(store.allows(rotated, "analytics"))
    }

    @Test fun oidcDeviceCredentialIsStoredAsTheActiveAuthenticationMode() {
        store.configureOidc("https://one.example.test/prefix", "rhd_" + "A".repeat(43))
        assertEquals(ApiAuthMode.OIDC_DEVICE, store.snapshot().authMode)
        assertEquals("rhd_" + "A".repeat(43), requireNotNull(store.connection()).bearerKey)
        assertThrows(IllegalArgumentException::class.java) {
            store.configureOidc("http://one.example.test", "rhd_" + "B".repeat(43))
        }
    }

    @Test fun capabilityRemovalAndConsentRevocationStopOnlyTheirChannels() {
        configure("https://one.example.test", "server-a")
        store.approveAnalytics(true)
        store.approveAnalytics(false)
        val connection = requireNotNull(store.connection())
        assertTrue(store.allows(connection, "metrics"))
        assertFalse(store.allows(connection, "analytics"))
        val response = JSONObject("""{"contract_version":1,"server_id":"server-a",
            "capabilities":{"realtime":{"schema_version":1,"gps":false}}}""")
        store.saveCapabilities(connection.destinationId, response, parseCapabilities(response))
        assertFalse(store.allows(connection, "metrics"))
        assertTrue(store.allows(connection, "realtime"))
    }

    @Test fun oidcRequiresHttps() {
        assertThrows(IllegalArgumentException::class.java) {
            store.configureOidc("http://api.example.test", "rhd_" + "A".repeat(43))
        }
        store.configureOidc("https://api.example.test", "rhd_" + "A".repeat(43))
        assertFalse(store.snapshot().insecure)
        assertFalse(store.snapshot().httpConfirmed)
    }

    @Test fun savedLegacyCredentialIsRemovedBeforeUse() {
        app.getSharedPreferences("self_hosted_api_credentials", Context.MODE_PRIVATE).edit()
            .putString("protected_base_url", "base-url|https://one.example.test")
            .putString("protected_bearer_key", "bearer-key|synthetic-former-key")
            .putString("auth_mode", ApiAuthMode.LEGACY_BEARER.name)
            .commit()
        assertNull(store.connection())
        assertFalse(store.snapshot().configured)
    }

    @Test fun repeatedReadsReuseIdentityButObserveChangesFromAnotherStore() {
        val original = configure("https://one.example.test", "server-a")
        val reads = decryptions
        repeat(20) {
            assertEquals(original, store.connection())
            assertTrue(store.allows(original, "metrics"))
            store.snapshot()
        }
        assertEquals(reads, decryptions)
        val other = ApiStore(app, protection, database)
        other.setEnabled(false)
        assertFalse(store.allows(original, "metrics"))
        other.setEnabled(true)
        other.setGpsEnabled(true)
        assertTrue(store.snapshot().gpsEnabled)
        other.configureOidc("https://one.example.test", "rhd_" + "C".repeat(43))
        val beforeRefresh = decryptions
        assertNotEquals(original, store.connection())
        assertEquals(beforeRefresh + 2, decryptions)
        assertFalse(store.allows(original, "metrics"))
        other.clearConfiguration()
        assertNull(store.connection())
        assertFalse(store.snapshot().configured)
    }

    @Test fun motionConsentIsIndependentAndScopedToAuthenticatedServer() {
        val connection = configure("https://motion.example.test", "motion-server-a")
        val response = JSONObject("""{"contract_version":1,"server_id":"motion-server-a","capabilities":{
            "metrics":{"schema_version":1},"motion":{"schema_version":1,"max_batch_samples":4096,"max_upload_bytes":1048576}}}""")
        store.saveCapabilities(connection.destinationId, response, parseCapabilities(response))
        assertFalse(store.allows(connection, "motion"))
        MotionStore.get(app).approve(connection.deliveryScope, true)
        assertTrue(store.allows(connection, "motion"))
        assertFalse(store.allows(connection, "analytics"))
        store.setGpsEnabled(false)
        assertTrue(store.allows(connection, "motion"))
        MotionStore.get(app).approve(connection.deliveryScope, false)
        assertFalse(store.allows(connection, "motion"))
        assertTrue(store.allows(connection, "metrics"))
        MotionStore.get(app).approve(connection.deliveryScope, true)
        response.put("server_id", "motion-server-b")
        store.saveCapabilities(connection.destinationId, response, parseCapabilities(response))
        assertFalse(store.snapshot().motionEnabled)
        assertFalse(store.allows(requireNotNull(store.connection()), "motion"))
    }

    private fun configure(url: String, server: String): ApiConnection {
        store.configureOidc(url, "rhd_" + "A".repeat(43))
        capabilities(server)
        return requireNotNull(store.connection())
    }

    private fun capabilities(server: String) {
        val response = JSONObject().put("contract_version", 1).put("server_id", server)
            .put("capabilities", JSONObject("""{"metrics":{"schema_version":1},
                "analytics":{"schema_version":1,"rha_format_versions":[1],"max_upload_bytes":4096}}"""))
        store.saveCapabilities(requireNotNull(store.connection()).destinationId, response, parseCapabilities(response))
    }
}

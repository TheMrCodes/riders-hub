package at.themrcodes.ridershub.api

import android.app.Application
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
    private val protection = object : ApiCredentialProtection {
        override fun encrypt(value: String, purpose: String) = "$purpose|$value"
        override fun decrypt(value: String, purpose: String) = value.removePrefix("$purpose|")
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
        store.configure("https://one.example.test", "synthetic-rotated", false)
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

        store.configure("https://one.example.test/prefix", "synthetic-legacy", false)
        assertEquals(ApiAuthMode.LEGACY_BEARER, store.snapshot().authMode)
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

    @Test fun httpRequiresConfirmationAndRetainsTheWarning() {
        assertThrows(IllegalArgumentException::class.java) { store.configure("http://api.example.test", "synthetic", false) }
        store.configure("http://api.example.test", "synthetic", true)
        assertTrue(store.snapshot().insecure)
        assertTrue(store.snapshot().httpConfirmed)
        store.configure("https://api.example.test", "synthetic", true)
        assertFalse(store.snapshot().insecure)
        assertFalse(store.snapshot().httpConfirmed)
    }

    private fun configure(url: String, server: String): ApiConnection {
        store.configure(url, "synthetic-secret", false)
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

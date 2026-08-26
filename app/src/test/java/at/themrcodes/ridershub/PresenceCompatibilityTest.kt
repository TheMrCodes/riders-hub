package at.themrcodes.ridershub

import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceCompatibilityTest {
    @Test
    fun android14UsesAddressPresenceCallbacks() {
        assertEquals(
            PresenceRegistrationMode.BLE_ADDRESS,
            presenceRegistrationModeForSdk(34),
        )
    }

    @Test
    fun android15UsesAddressPresenceCallbacks() {
        assertEquals(
            PresenceRegistrationMode.BLE_ADDRESS,
            presenceRegistrationModeForSdk(35),
        )
    }

    @Test
    fun android16UsesAssociationPresenceEvents() {
        assertEquals(
            PresenceRegistrationMode.ASSOCIATION_ID,
            presenceRegistrationModeForSdk(36),
        )
    }
}

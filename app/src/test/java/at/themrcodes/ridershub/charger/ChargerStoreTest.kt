package at.themrcodes.ridershub.charger

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class ChargerStoreTest {
    private val app = RuntimeEnvironment.getApplication()
    private val preferences = app.getSharedPreferences("charger_state", Application.MODE_PRIVATE)
    private val store = ChargerStore(app)

    @Before fun clearBefore() = preferences.edit().clear().commit().let { Unit }
    @After fun clearAfter() = preferences.edit().clear().commit().let { Unit }

    @Test fun `association identity remains separate from live connection state`() {
        store.setAssociation(42, "ShellyPlusPlugS-TEST")
        var snapshot = store.snapshot()
        assertTrue(snapshot.paired)
        assertEquals(42, snapshot.associationId)
        assertEquals("ShellyPlusPlugS-TEST", snapshot.associatedDeviceName)
        assertFalse(snapshot.present)
        assertFalse(snapshot.gattConnected)
        assertFalse(snapshot.nearby)
        assertFalse(snapshot.rpcVerified)

        store.beginAppearance()
        store.setGattConnected(true)
        store.setRpcVerified(true)
        store.update(ChargerPhase.CHARGING, "Synthetic charging state", outputOn = true, powerWatts = 60.0)
        snapshot = store.snapshot()
        assertTrue(snapshot.present)
        assertTrue(snapshot.gattConnected)
        assertTrue(snapshot.nearby)
        assertTrue(snapshot.rpcVerified)
        assertEquals(true, snapshot.outputOn)

        store.setGattConnected(false)
        snapshot = store.snapshot()
        assertTrue(snapshot.paired)
        assertTrue(snapshot.present)
        assertFalse(snapshot.gattConnected)
        assertTrue(snapshot.nearby)
        assertFalse(snapshot.rpcVerified)
        assertNull(snapshot.outputOn)
        assertNull(snapshot.powerWatts)
    }

    @Test fun `absence clears live readings but preserves selected device`() {
        store.setAssociation(7, "ShellyPlusPlugS-DEMO")
        store.beginAppearance()
        store.setGattConnected(true)
        store.setRpcVerified(true)
        store.update(ChargerPhase.AWAITING_CONFIRMATION, "Ready", outputOn = false, powerWatts = 0.0)

        store.setAbsent()

        val snapshot = store.snapshot()
        assertEquals("ShellyPlusPlugS-DEMO", snapshot.associatedDeviceName)
        assertEquals(7, snapshot.associationId)
        assertFalse(snapshot.present)
        assertFalse(snapshot.gattConnected)
        assertFalse(snapshot.nearby)
        assertFalse(snapshot.rpcVerified)
        assertNull(snapshot.outputOn)
        assertNull(snapshot.powerWatts)
    }

    @Test fun `service rebound probe does not claim presence before GATT connects`() {
        store.setAssociation(9, "ShellyPlusPlugS-SYNTHETIC")

        store.beginAppearance(presenceObserved = false)
        var snapshot = store.snapshot()
        assertFalse(snapshot.present)
        assertFalse(snapshot.nearby)
        assertEquals("Checking the associated charger over BLE", snapshot.status)

        store.setGattConnected(true)
        snapshot = store.snapshot()
        assertFalse(snapshot.present)
        assertTrue(snapshot.gattConnected)
        assertTrue(snapshot.nearby)
    }
}

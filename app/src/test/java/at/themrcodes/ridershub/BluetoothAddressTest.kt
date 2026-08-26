package at.themrcodes.ridershub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BluetoothAddressTest {
    @Test
    fun canonicalizesLowercaseAssociationAddress() {
        assertEquals(
            "02:AB:CD:EF:00:01",
            canonicalBluetoothAddress("02:ab:cd:ef:00:01"),
        )
    }

    @Test
    fun preservesCanonicalAddressAndTrimsWhitespace() {
        assertEquals(
            "02:AB:CD:EF:00:01",
            canonicalBluetoothAddress("  02:AB:CD:EF:00:01  "),
        )
    }

    @Test
    fun rejectsMalformedAddress() {
        assertThrows(IllegalArgumentException::class.java) {
            canonicalBluetoothAddress("BF_EXAMPLE")
        }
    }
}

package at.themrcodes.ridershub.wear.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class WearTelemetryContractTest {
    @Test
    fun payloadRoundTripsWithoutDeviceIdentifiers() {
        val expected = WearTelemetryState(
            connection = WearConnectionStatus.LIVE,
            updatedAtEpochMs = 1_700_000_000_000,
            speedKmh = 24.5,
            boardBatteryPercent = 78,
            tripKm = 4.25,
            estimatedRangeKm = 12.75,
            mode = "SPORT",
        )

        assertEquals(expected, WearTelemetryState.decode(expected.encode()))
    }

    @Test
    fun nullableReadingsRoundTrip() {
        val expected = WearTelemetryState(
            connection = WearConnectionStatus.STANDBY,
            updatedAtEpochMs = 0,
            speedKmh = null,
            boardBatteryPercent = null,
            tripKm = null,
            estimatedRangeKm = null,
            mode = null,
        )

        assertEquals(expected, WearTelemetryState.decode(expected.encode()))
    }

    @Test
    fun invalidTelemetryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            WearTelemetryState(
                connection = WearConnectionStatus.LIVE,
                updatedAtEpochMs = 1,
                speedKmh = Double.NaN,
                boardBatteryPercent = 101,
                tripKm = -1.0,
                estimatedRangeKm = Double.POSITIVE_INFINITY,
                mode = "SPORT",
            )
        }
    }

    @Test
    fun unknownPayloadIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            WearTelemetryState.decode(ByteArray(32))
        }
    }

    @Test
    fun legacyPayloadDecodesWithUnavailableRange() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(0x52485542)
            output.writeInt(1)
            output.writeUTF(WearConnectionStatus.LIVE.name)
            output.writeLong(123_000)
            output.writeBoolean(true)
            output.writeDouble(24.5)
            output.writeBoolean(true)
            output.writeInt(78)
            output.writeBoolean(true)
            output.writeDouble(4.25)
            output.writeBoolean(true)
            output.writeUTF("SPORT")
        }

        val decoded = WearTelemetryState.decode(bytes.toByteArray())

        assertEquals(null, decoded.estimatedRangeKm)
        assertEquals(4.25, decoded.tripKm!!, 0.001)
    }

    @Test
    fun wearSettingsRoundTrip() {
        assertEquals(
            WearSettingsState(autoOpenOnLive = true),
            WearSettingsState.decode(WearSettingsState(autoOpenOnLive = true).encode()),
        )
        assertEquals(
            WearSettingsState(autoOpenOnLive = false),
            WearSettingsState.decode(WearSettingsState(autoOpenOnLive = false).encode()),
        )
    }

    @Test
    fun invalidWearSettingsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            WearSettingsState.decode(byteArrayOf(1, 2))
        }
    }
}

package at.themrcodes.ridershub.wear.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WearTelemetryContractTest {
    @Test
    fun payloadRoundTripsWithoutDeviceIdentifiers() {
        val expected = WearTelemetryState(
            connection = WearConnectionStatus.LIVE,
            updatedAtEpochMs = 1_700_000_000_000,
            speedKmh = 24.5,
            boardBatteryPercent = 78,
            tripKm = 4.25,
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
}

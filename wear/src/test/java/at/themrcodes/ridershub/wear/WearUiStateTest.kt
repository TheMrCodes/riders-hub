package at.themrcodes.ridershub.wear

import at.themrcodes.ridershub.wear.shared.WearConnectionStatus
import at.themrcodes.ridershub.wear.shared.WearTelemetryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearUiStateTest {
    @Test
    fun recentLiveTelemetryIsFormattedForAGlance() {
        val ui = wearUiState(
            telemetry = telemetry(WearConnectionStatus.LIVE, updatedAt = 100_000),
            nowEpochMs = 105_000,
        )

        assertTrue(ui.live)
        assertEquals("LIVE", ui.statusLabel)
        assertEquals("24.5", ui.speed)
        assertEquals("78%", ui.battery)
        assertEquals("4.3 KM", ui.trip)
        assertEquals("4.3", ui.tripValue)
        assertEquals("12.8", ui.rangeValue)
        assertEquals("SPORT", ui.mode)
    }

    @Test
    fun oldLivePayloadIsVisiblyStale() {
        val ui = wearUiState(
            telemetry = telemetry(WearConnectionStatus.LIVE, updatedAt = 100_000),
            nowEpochMs = 131_000,
        )

        assertFalse(ui.live)
        assertEquals("UPDATE STALE", ui.statusLabel)
    }

    @Test
    fun absentPhoneDataHasExplicitEmptyState() {
        val ui = wearUiState(telemetry = null, nowEpochMs = 100_000)

        assertFalse(ui.live)
        assertEquals("WAITING FOR PHONE", ui.statusLabel)
        assertEquals("--.-", ui.speed)
        assertEquals("--%", ui.battery)
    }

    @Test
    fun ambientGatePublishesOnlyOnMinuteTicks() {
        val gate = AmbientTelemetryGate<String>()
        gate.update("interactive")
        gate.setAmbient(true)

        gate.update("buffered")
        assertEquals("interactive", gate.visible)

        gate.onAmbientTick()
        assertEquals("buffered", gate.visible)

        gate.update("latest")
        assertEquals("buffered", gate.visible)

        gate.setAmbient(false)
        assertEquals("latest", gate.visible)
    }

    @Test
    fun burnInOffsetMovesAroundFourCorners() {
        assertEquals(-2 to -2, ambientBurnInOffset(0))
        assertEquals(2 to -2, ambientBurnInOffset(1))
        assertEquals(2 to 2, ambientBurnInOffset(2))
        assertEquals(-2 to 2, ambientBurnInOffset(3))
        assertEquals(-2 to -2, ambientBurnInOffset(4))
    }

    @Test
    fun ongoingActivityTracksOnlyRecentActiveRideStates() {
        assertTrue(
            shouldKeepRideVisible(
                telemetry(WearConnectionStatus.LIVE, updatedAt = 100_000),
                nowEpochMs = 105_000,
            ),
        )
        assertTrue(
            shouldKeepRideVisible(
                telemetry(WearConnectionStatus.RECONNECTING, updatedAt = 100_000),
                nowEpochMs = 105_000,
            ),
        )
        assertFalse(
            shouldKeepRideVisible(
                telemetry(WearConnectionStatus.STANDBY, updatedAt = 100_000),
                nowEpochMs = 105_000,
            ),
        )
        assertFalse(
            shouldKeepRideVisible(
                telemetry(WearConnectionStatus.LIVE, updatedAt = 100_000),
                nowEpochMs = 220_001,
            ),
        )
    }

    private fun telemetry(connection: WearConnectionStatus, updatedAt: Long) = WearTelemetryState(
        connection = connection,
        updatedAtEpochMs = updatedAt,
        speedKmh = 24.5,
        boardBatteryPercent = 78,
        tripKm = 4.25,
        estimatedRangeKm = 12.75,
        mode = "SPORT",
    )
}

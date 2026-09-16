package at.themrcodes.ridershub.wear

import at.themrcodes.ridershub.wear.shared.WearConnectionStatus
import at.themrcodes.ridershub.wear.shared.WearTelemetryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WearUiStateTest {
    @Test
    fun clockAdvancesAcrossMinuteAndDayBoundariesInTheLocalZone() {
        val time = Instant.parse("2030-01-02T22:59:59Z").toEpochMilli()
        val zone = ZoneId.of("Europe/Vienna")
        assertEquals("23:59", wearClockText(time, zone))
        assertEquals("00:00", wearClockText(time + 1_000, zone))
    }

    @Test
    fun batteryEstimateIsClearlyMarkedAndRawPayloadsStillWork() {
        val state = telemetry(WearConnectionStatus.LIVE, updatedAt = 100_000)
        assertEquals("~57.3%", wearUiState(state.copy(estimatedBatteryPercent = 57.3), 100_000).battery)
        assertEquals("${state.boardBatteryPercent}%", wearUiState(state, 100_000).battery)
    }

    @Test
    fun clockUsesBareTwentyFourHourHoursAndMinutes() {
        assertEquals(
            "05:07",
            wearClockText(
                nowEpochMs = Instant.parse("2030-01-02T05:07:45Z").toEpochMilli(),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun persistedTelemetryRoundTripsThroughTheProductionContract() {
        val telemetry = telemetry(WearConnectionStatus.RECONNECTING, updatedAt = 100_000)

        assertEquals(telemetry, decodePersistedTelemetry(encodePersistedTelemetry(telemetry)))
        assertNull(decodePersistedTelemetry("not-valid-base64"))
    }

    @Test
    fun olderBootstrapPayloadCannotReplaceNewerBackgroundState() {
        val newer = telemetry(WearConnectionStatus.LIVE, updatedAt = 200_000)
        val older = telemetry(WearConnectionStatus.RECONNECTING, updatedAt = 100_000)

        assertFalse(shouldReplaceTelemetry(newer, older))
        assertTrue(shouldReplaceTelemetry(older, newer))
        assertTrue(shouldReplaceTelemetry(null, older))
    }

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
        assertFalse(shouldShowStatusMessage(ui))
    }

    @Test
    fun oldLivePayloadIsVisiblyStale() {
        val ui = wearUiState(
            telemetry = telemetry(WearConnectionStatus.LIVE, updatedAt = 100_000),
            nowEpochMs = 131_000,
        )

        assertFalse(ui.live)
        assertEquals("UPDATE STALE", ui.statusLabel)
        assertTrue(shouldShowStatusMessage(ui))
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

    @Test
    fun screenWakeLockRequiresTheUserSettingAndARecentRide() {
        val active = telemetry(WearConnectionStatus.LIVE, updatedAt = 100_000)

        assertFalse(
            shouldKeepScreenOn(
                enabled = false,
                telemetry = active,
                nowEpochMs = 105_000,
            ),
        )
        assertTrue(
            shouldKeepScreenOn(
                enabled = true,
                telemetry = active,
                nowEpochMs = 105_000,
            ),
        )
        assertFalse(
            shouldKeepScreenOn(
                enabled = true,
                telemetry = active,
                nowEpochMs = 220_001,
            ),
        )
        assertFalse(
            shouldKeepScreenOn(
                enabled = true,
                telemetry = telemetry(WearConnectionStatus.STANDBY, updatedAt = 100_000),
                nowEpochMs = 105_000,
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

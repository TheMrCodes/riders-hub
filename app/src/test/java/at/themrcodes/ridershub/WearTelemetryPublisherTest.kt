package at.themrcodes.ridershub

import at.themrcodes.ridershub.wear.shared.WearConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearTelemetryPublisherTest {
    @Test
    fun liveSnapshotMapsOnlyGlanceableTelemetry() {
        val payload = snapshot(
            serviceActive = true,
            present = true,
            connection = "Listening for telemetry",
            speedKmh = 21.5f,
            boardBatteryPercent = 64,
            tripKm = 3.75f,
            mode = "SPORT",
        ).toWearTelemetryState(nowEpochMs = 123_000, estimatedRangeKm = 12.75)

        assertEquals(WearConnectionStatus.LIVE, payload.connection)
        assertEquals(123_000, payload.updatedAtEpochMs)
        assertEquals(21.5, payload.speedKmh!!, 0.001)
        assertEquals(64, payload.boardBatteryPercent)
        assertEquals(3.75, payload.tripKm!!, 0.001)
        assertEquals(12.75, payload.estimatedRangeKm!!, 0.001)
        assertEquals("SPORT", payload.mode)
    }

    @Test
    fun connectingSnapshotKeepsUnavailableReadingsEmpty() {
        val payload = snapshot(
            serviceActive = true,
            present = true,
            connection = "Connected — discovering services",
        ).toWearTelemetryState(nowEpochMs = 123_000)

        assertEquals(WearConnectionStatus.CONNECTING, payload.connection)
        assertNull(payload.speedKmh)
        assertNull(payload.boardBatteryPercent)
        assertNull(payload.tripKm)
        assertNull(payload.mode)
    }

    @Test
    fun disconnectedSnapshotIsMarkedForReconnection() {
        val payload = snapshot(
            serviceActive = true,
            present = false,
            connection = "Disconnected — retry in 4s",
        ).toWearTelemetryState(nowEpochMs = 123_000)

        assertEquals(WearConnectionStatus.RECONNECTING, payload.connection)
    }

    @Test
    fun autoOpenRequiresTheFirstValidLiveFrameOfANewSession() {
        assertTrue(
            shouldAutoOpenWearDashboard(
                enabled = true,
                connection = WearConnectionStatus.LIVE,
                hasValidFrame = true,
                sessionToken = "new-session",
                lastSeenLiveSession = "old-session",
            ),
        )
        assertFalse(
            shouldAutoOpenWearDashboard(
                enabled = false,
                connection = WearConnectionStatus.LIVE,
                hasValidFrame = true,
                sessionToken = "new-session",
                lastSeenLiveSession = "old-session",
            ),
        )
        assertFalse(
            shouldAutoOpenWearDashboard(
                enabled = true,
                connection = WearConnectionStatus.CONNECTING,
                hasValidFrame = true,
                sessionToken = "new-session",
                lastSeenLiveSession = "old-session",
            ),
        )
        assertFalse(
            shouldAutoOpenWearDashboard(
                enabled = true,
                connection = WearConnectionStatus.LIVE,
                hasValidFrame = false,
                sessionToken = "new-session",
                lastSeenLiveSession = "old-session",
            ),
        )
        assertFalse(
            shouldAutoOpenWearDashboard(
                enabled = true,
                connection = WearConnectionStatus.LIVE,
                hasValidFrame = true,
                sessionToken = "same-session",
                lastSeenLiveSession = "same-session",
            ),
        )
    }

    private fun snapshot(
        serviceActive: Boolean,
        present: Boolean,
        connection: String,
        speedKmh: Float? = null,
        boardBatteryPercent: Int? = null,
        tripKm: Float? = null,
        mode: String? = null,
    ) = AppSnapshot(
        address = "02:00:00:00:00:01",
        name = "BF_SAMPLE",
        associationId = 7,
        observing = true,
        monitorDetail = "Synthetic test state",
        serviceActive = serviceActive,
        present = present,
        presenceDetail = "Synthetic test state",
        connection = connection,
        latestLog = null,
        frameCount = 0,
        lastFrame = null,
        speedKmh = speedKmh,
        boardBatteryPercent = boardBatteryPercent,
        packVoltageV = null,
        mode = mode,
        tripKm = tripKm,
        odometerKm = 12.3f,
        loadRaw = null,
        crcValid = null,
        childLimiterActive = null,
        limiterControlAvailable = false,
        limiterCommandStatus = null,
        error = null,
        lastEventAt = null,
    )
}

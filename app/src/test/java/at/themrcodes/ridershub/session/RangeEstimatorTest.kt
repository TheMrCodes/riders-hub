package at.themrcodes.ridershub.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeEstimatorTest {
    @Test
    fun collectsDataUntilTheFirstFivePercentWindowCompletes() {
        val result = RangeEstimator.estimate(
            currentBatteryPercent = 80,
            currentRestingVoltageV = null,
            depletionWindows = emptyList(),
            profileSessions = listOf(summary(2.0, mapOf(20 to 2.0))),
            calibrationPoints = emptyList(),
        )

        assertEquals(RangeEstimateStatus.COLLECTING_DATA, result.status)
        assertNull(result.remainingKm)
    }

    @Test
    fun estimatesFromCompletedDepletionWindows() {
        val windows = listOf(
            window("b", "2030-01-02T10:00:00Z", 3.0, 10, mapOf(20 to 3.0)),
            window("a", "2030-01-01T10:00:00Z", 2.0, 10, mapOf(20 to 2.0)),
        )

        val result = RangeEstimator.estimate(
            currentBatteryPercent = 50,
            currentRestingVoltageV = null,
            depletionWindows = windows,
            profileSessions = listOf(summary(5.0, mapOf(20 to 5.0))),
            calibrationPoints = emptyList(),
        )

        assertEquals(RangeEstimateStatus.PROVISIONAL, result.status)
        assertEquals(0.25, result.kmPerPercent!!, 0.0001)
        assertEquals(12.5, result.remainingKm!!, 0.0001)
    }

    @Test
    fun newestHundredKilometresDetermineTheCommonSpeedMix() {
        val windows = listOf(
            window("fast", "2030-01-02T10:00:00Z", 2.0, 5, mapOf(30 to 2.0)),
            window("slow", "2030-01-01T10:00:00Z", 4.0, 5, mapOf(10 to 4.0)),
        )
        val sessions = listOf(
            summary(60.0, mapOf(10 to 60.0), id = "new-slow"),
            summary(80.0, mapOf(30 to 80.0), id = "old-fast"),
        )

        val result = RangeEstimator.estimate(50, null, windows, sessions, emptyList())

        assertEquals(60.0, result.commonSpeedBucketDistributionPercent.getValue(10), 0.0001)
        assertEquals(40.0, result.commonSpeedBucketDistributionPercent.getValue(30), 0.0001)
        val slowUse = result.bucketBatteryPercentPer100Km.getValue(10)
        val fastUse = result.bucketBatteryPercentPer100Km.getValue(30)
        assertTrue(fastUse > slowUse)
        val expectedKmPerPercent = 1.0 / (slowUse / 100.0 * 0.6 + fastUse / 100.0 * 0.4)
        assertEquals(expectedKmPerPercent, result.kmPerPercent!!, 0.0001)
    }

    @Test
    fun activeRideSpeedMixAdjustsTheCrossTripBaseline() {
        val windows = listOf(
            window("fast", "2030-01-02T10:00:00Z", 2.0, 5, mapOf(30 to 2.0)),
            window("slow", "2030-01-01T10:00:00Z", 4.0, 5, mapOf(10 to 4.0)),
        )
        val historical = summary(20.0, mapOf(10 to 20.0), id = "history")
        val activeFast = summary(1.0, mapOf(30 to 1.0), id = "active")
            .copy(active = true, endedAt = null)

        val betweenTrips = RangeEstimator.estimate(
            50,
            null,
            windows,
            listOf(historical),
            emptyList(),
        )
        val duringFastTrip = RangeEstimator.estimate(
            50,
            null,
            windows,
            listOf(activeFast, historical),
            emptyList(),
        )

        assertTrue(duringFastTrip.remainingKm!! < betweenTrips.remainingKm!!)
        assertTrue(duringFastTrip.message.startsWith("Adjusted to this trip"))
    }

    @Test
    fun depletionModelIsLimitedToNewestHundredKilometres() {
        val windows = listOf(
            window("new", "2030-01-02T10:00:00Z", 60.0, 30, mapOf(20 to 60.0)),
            window("old", "2030-01-01T10:00:00Z", 80.0, 40, mapOf(20 to 80.0)),
        )

        val result = RangeEstimator.estimate(
            50,
            null,
            windows,
            listOf(summary(100.0, mapOf(20 to 100.0))),
            emptyList(),
        )

        assertEquals(100.0, result.observedDistanceKm, 0.0001)
        assertEquals(50.0, result.observedDepletionPercent, 0.0001)
    }

    @Test
    fun currentRideIsNotCountedTwiceInSpeedProfile() {
        val active = summary(1.0, mapOf(20 to 1.0), id = "same-ride")
            .copy(endedAt = null, active = true)
        val sessions = rangeEstimationSessions(
            completedTracks = listOf(active.copy(active = false)),
            activeRide = active,
        )

        assertEquals(1, sessions.size)
    }

    @Test
    fun historyRetentionKeepsEnoughRidesForHundredKilometres() {
        val rides = (1..150).map { index ->
            summary(1.0, mapOf(20 to 1.0), id = "ride-$index")
        }

        val retained = retainRangeProfileHistory(rides)

        assertEquals(100, retained.size)
        assertEquals("ride-1", retained.first().id)
        assertEquals("ride-100", retained.last().id)
    }

    private fun window(
        id: String,
        closedAt: String,
        distance: Double,
        depletion: Int,
        buckets: Map<Int, Double>,
    ) = RangeDepletionWindow(
        id = id,
        localBoardId = "board-a",
        startedAt = closedAt,
        lastObservedAt = closedAt,
        closedAt = closedAt,
        startBatteryPercent = 90,
        endBatteryPercent = 90 - depletion,
        minBatteryPercent = 90 - depletion,
        recordedDistanceKm = distance,
        speedBucketDistancesKm = buckets,
    )

    private fun summary(
        distance: Double,
        speedBuckets: Map<Int, Double>,
        id: String = "ride-$distance",
    ) = RideSummary(
        id = id,
        startedAt = "2030-01-01T10:00:00Z",
        endedAt = "2030-01-01T11:00:00Z",
        lastFrameAt = "2030-01-01T11:00:00Z",
        distanceKm = distance,
        movingSeconds = 600.0,
        maxSpeedKmh = 25.0,
        boardBatteryStart = 90,
        boardBatteryEnd = 85,
        boardBatteryMin = 85,
        packVoltageStart = 40.0,
        packVoltageEnd = 39.0,
        packVoltageMin = 39.0,
        packVoltageMax = 40.0,
        restingVoltageStart = null,
        restingVoltageEnd = null,
        odometerStartKm = 0.0,
        odometerEndKm = distance,
        frameCount = 100,
        boardFrameCount = 100,
        crcErrorCount = 0,
        segmentCount = 1,
        modes = setOf("Sport"),
        logFile = "ride.jsonl",
        active = false,
        speedBucketDistancesKm = speedBuckets,
    )
}

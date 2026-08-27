package at.themrcodes.ridershub.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeEstimatorTest {
    @Test
    fun collectsDataWithoutEnoughDepletion() {
        val result = RangeEstimator.estimate(
            currentBatteryPercent = 80,
            currentRestingVoltageV = 40.0,
            sessions = listOf(summary(distance = 0.5, startPercent = 80, endPercent = 79)),
            calibrationPoints = emptyList(),
        )

        assertEquals(RangeEstimateStatus.COLLECTING_DATA, result.status)
        assertNull(result.remainingKm)
    }

    @Test
    fun estimatesFromAccumulatedPercentageDrop() {
        val sessions = listOf(
            summary(distance = 3.0, startPercent = 90, endPercent = 80),
            summary(distance = 2.0, startPercent = 80, endPercent = 70),
        )

        val result = RangeEstimator.estimate(50, null, sessions, emptyList())

        assertEquals(RangeEstimateStatus.CALIBRATED, result.status)
        assertEquals(0.25, result.kmPerPercent!!, 0.0001)
        assertEquals(12.5, result.remainingKm!!, 0.0001)
    }

    @Test
    fun restingVoltageCanResolveSubPercentDepletion() {
        val points = listOf(
            CalibrationPoint("a", 20, 36.0),
            CalibrationPoint("b", 40, 37.0),
            CalibrationPoint("c", 60, 38.0),
            CalibrationPoint("d", 80, 39.0),
        )
        val sessions = listOf(
            summary(
                distance = 2.0,
                startPercent = 60,
                endPercent = 60,
                restStart = 38.0,
                restEnd = 37.5,
            ),
        )

        val result = RangeEstimator.estimate(50, 37.5, sessions, points)

        assertTrue(result.status != RangeEstimateStatus.COLLECTING_DATA)
        assertEquals(0.2, result.kmPerPercent!!, 0.0001)
    }

    @Test
    fun estimatesFromCurrentVisibleRide() {
        val active = summary(distance = 1.0, startPercent = 80, endPercent = 75)
            .copy(id = "active", endedAt = null, active = true)

        val result = RangeEstimator.estimate(
            currentBatteryPercent = 75,
            currentRestingVoltageV = null,
            sessions = rangeEstimationSessions(emptyList(), active),
            calibrationPoints = emptyList(),
        )

        assertEquals(RangeEstimateStatus.PROVISIONAL, result.status)
        assertEquals(0.2, result.kmPerPercent!!, 0.0001)
        assertEquals(15.0, result.remainingKm!!, 0.0001)
    }

    @Test
    fun currentRideIsNotCountedTwice() {
        val active = summary(distance = 1.0, startPercent = 80, endPercent = 75)
            .copy(id = "same-ride", endedAt = null, active = true)
        val sessions = rangeEstimationSessions(
            completedTracks = listOf(active.copy(active = false)),
            activeRide = active,
        )

        assertEquals(1, sessions.size)
    }

    @Test
    fun currentHighSpeedMixProducesShorterRangeThanLowSpeedMix() {
        val history = listOf(
            summary(
                distance = 4.0,
                startPercent = 100,
                endPercent = 92,
                speedBuckets = mapOf(10 to 4.0),
            ),
            summary(
                distance = 4.0,
                startPercent = 92,
                endPercent = 76,
                speedBuckets = mapOf(30 to 4.0),
            ),
        )
        val lowSpeedProfile = summary(
            distance = 0.2,
            startPercent = 50,
            endPercent = 50,
            speedBuckets = mapOf(10 to 0.2),
        ).copy(id = "active-low", active = true, endedAt = null)
        val highSpeedProfile = lowSpeedProfile.copy(
            id = "active-high",
            speedBucketDistancesKm = mapOf(30 to 0.2),
        )

        val lowSpeedRange = RangeEstimator.estimate(
            50,
            null,
            history + lowSpeedProfile,
            emptyList(),
        ).remainingKm!!
        val highSpeedRange = RangeEstimator.estimate(
            50,
            null,
            history + highSpeedProfile,
            emptyList(),
        ).remainingKm!!

        assertTrue(highSpeedRange < lowSpeedRange)
    }

    private fun summary(
        distance: Double,
        startPercent: Int,
        endPercent: Int,
        restStart: Double? = null,
        restEnd: Double? = null,
        speedBuckets: Map<Int, Double> = emptyMap(),
    ) = RideSummary(
        id = "ride-$distance-$startPercent",
        startedAt = "2030-01-01T10:00:00Z",
        endedAt = "2030-01-01T11:00:00Z",
        lastFrameAt = "2030-01-01T11:00:00Z",
        distanceKm = distance,
        movingSeconds = 600.0,
        maxSpeedKmh = 25.0,
        boardBatteryStart = startPercent,
        boardBatteryEnd = endPercent,
        boardBatteryMin = endPercent,
        packVoltageStart = restStart,
        packVoltageEnd = restEnd,
        packVoltageMin = restEnd,
        packVoltageMax = restStart,
        restingVoltageStart = restStart,
        restingVoltageEnd = restEnd,
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

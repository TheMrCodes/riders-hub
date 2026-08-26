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

    private fun summary(
        distance: Double,
        startPercent: Int,
        endPercent: Int,
        restStart: Double? = null,
        restEnd: Double? = null,
    ) = RideSummary(
        id = "ride-$distance-$startPercent",
        startedAt = "2026-08-25T10:00:00Z",
        endedAt = "2026-08-25T11:00:00Z",
        lastFrameAt = "2026-08-25T11:00:00Z",
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
    )
}

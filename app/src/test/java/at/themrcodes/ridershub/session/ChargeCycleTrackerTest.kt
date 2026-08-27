package at.themrcodes.ridershub.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class ChargeCycleTrackerTest {
    @Test
    fun aggregatesRidesAndSpeedProfileUntilRecharge() {
        val first = ChargeCycleTracker.recordRide(
            current = null,
            ride = ride("one", startPercent = 90, endPercent = 75, distanceKm = 4.0, bucket = 10),
            localBoardId = "board_local_one",
        )!!
        val second = ChargeCycleTracker.recordRide(
            current = first.activeCycle,
            ride = ride("two", startPercent = 76, endPercent = 60, distanceKm = 6.0, bucket = 25),
            localBoardId = "board_local_one",
        )!!

        assertNull(second.completedCycle)
        assertEquals(2, second.activeCycle.rideCount)
        assertEquals(10.0, second.activeCycle.recordedDistanceKm, 0.0001)
        assertEquals(30, second.activeCycle.observedDepletionPercent)
        assertEquals(mapOf(10 to 4.0, 25 to 6.0), second.activeCycle.speedBucketDistancesKm)
    }

    @Test
    fun closesCycleWhenRechargeIncreaseIsLargeEnough() {
        val first = ChargeCycleTracker.recordRide(
            null,
            ride("before", startPercent = 80, endPercent = 40, distanceKm = 8.0),
            "board_local_one",
        )!!.activeCycle
        val update = ChargeCycleTracker.recordRide(
            first,
            ride("after", startPercent = 95, endPercent = 90, distanceKm = 1.0),
            "board_local_one",
        )!!

        assertTrue(update.completedCycle!!.isClosed)
        assertEquals(ChargeCycleEndReason.INFERRED_RECHARGE, update.completedCycle.endReason)
        assertEquals(ChargeCycleStartReason.INFERRED_RECHARGE, update.activeCycle.startReason)
        assertEquals(55, update.activeCycle.inferredRechargeIncreasePercent)
        assertTrue(update.activeCycle.fullChargeObserved)
    }

    @Test
    fun smallBatteryReboundDoesNotCreateChargeBoundary() {
        val first = ChargeCycleTracker.recordRide(
            null,
            ride("before", startPercent = 70, endPercent = 50, distanceKm = 3.0),
            "board_local_one",
        )!!.activeCycle
        val update = ChargeCycleTracker.recordRide(
            first,
            ride(
                "after",
                startPercent = 50 + ChargeCycleTracker.MIN_INFERRED_RECHARGE_PERCENT - 1,
                endPercent = 45,
                distanceKm = 2.0,
            ),
            "board_local_one",
        )!!

        assertNull(update.completedCycle)
        assertEquals(2, update.activeCycle.rideCount)
    }

    @Test
    fun odometerSpanExposesMissingProfiledDistance() {
        val first = ChargeCycleTracker.recordRide(
            null,
            ride(
                "before-gap",
                startPercent = 90,
                endPercent = 80,
                distanceKm = 2.0,
                odometerStartKm = 100.0,
                odometerEndKm = 102.0,
            ),
            "board_local_one",
        )!!.activeCycle
        val update = ChargeCycleTracker.recordRide(
            first,
            ride(
                "after-gap",
                startPercent = 79,
                endPercent = 70,
                distanceKm = 2.0,
                odometerStartKm = 106.0,
                odometerEndKm = 108.0,
            ),
            "board_local_one",
        )!!

        assertEquals(8.0, update.activeCycle.odometerDistanceKm!!, 0.0001)
        assertEquals(4.0, update.activeCycle.profiledDistanceKm, 0.0001)
        assertEquals(0.5, update.activeCycle.profileCoverage!!, 0.0001)
    }

    @Test
    fun ignoresNonTrackAndInvalidBatteryObservations() {
        val tooShort = ride("short", 90, 89, 0.0).copy(movingSeconds = 0.0)
        val missingBattery = ride("missing", 90, 89, 1.0).copy(boardBatteryEnd = null)

        assertNull(ChargeCycleTracker.recordRide(null, tooShort, "board_local_one"))
        assertNull(ChargeCycleTracker.recordRide(null, missingBattery, "board_local_one"))
        assertFalse(tooShort.isTrack)
    }

    @Test
    fun durableSummaryRoundTripsWithoutLosingAnalysisInputs() {
        val summary = ChargeCycleTracker.recordRide(
            null,
            ride("round-trip", 95, 65, 7.5, bucket = 20),
            "board_local_one",
        )!!.activeCycle

        val restored = chargeCycleFromJson(JSONObject(summary.toJson().toString()))

        assertEquals(summary, restored)
        assertEquals(30, restored.observedDepletionPercent)
        assertEquals(1.0, restored.profileCoverage!!, 0.0001)
    }

    @Test
    fun localBoardKeyIsStableAcrossAddressFormatting() {
        assertEquals(
            pseudonymousBoardId("02:AB:00:CD:00:01"),
            pseudonymousBoardId(" 02:ab:00:cd:00:01 "),
        )
    }

    private fun ride(
        id: String,
        startPercent: Int,
        endPercent: Int,
        distanceKm: Double,
        bucket: Int = 15,
        odometerStartKm: Double = 100.0,
        odometerEndKm: Double = odometerStartKm + distanceKm,
    ) = RideSummary(
        id = id,
        startedAt = "2030-01-01T10:00:00Z",
        endedAt = "2030-01-01T10:30:00Z",
        lastFrameAt = "2030-01-01T10:30:00Z",
        distanceKm = distanceKm,
        movingSeconds = if (distanceKm > 0.0) 600.0 else 0.0,
        maxSpeedKmh = 25.0,
        boardBatteryStart = startPercent,
        boardBatteryEnd = endPercent,
        boardBatteryMin = endPercent,
        packVoltageStart = 41.0,
        packVoltageEnd = 39.0,
        packVoltageMin = 39.0,
        packVoltageMax = 41.0,
        restingVoltageStart = 40.8,
        restingVoltageEnd = 39.2,
        odometerStartKm = odometerStartKm,
        odometerEndKm = odometerEndKm,
        frameCount = 100,
        boardFrameCount = 100,
        crcErrorCount = 0,
        segmentCount = 1,
        modes = setOf("Sport"),
        logFile = "synthetic-ride.jsonl",
        active = false,
        speedBucketDistancesKm = if (distanceKm > 0.0) mapOf(bucket to distanceKm) else emptyMap(),
    )
}

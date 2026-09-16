package at.themrcodes.ridershub.session

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GapSpeedProfileTest {
    private fun ride(id: String, board: String, distance: Double, bucket: Int) =
        summaryFromJson(JSONObject().put("id", id).put("local_board_id", board)
            .put("started_at", "2030-01-01T00:00:00Z").put("distance_km", distance)
            .put("log_file", "synthetic.jsonl").put("active", false)
            .put("speed_bucket_distances_km", JSONObject().put(bucket.toString(), distance)))

    @Test fun usesOnlyThisBoardsLatestHundredMeasuredKilometres() {
        val result = GapSpeedProfile.estimate(1.5, "board-a", listOf(
            ride("unrelated", "board-b", 100.0, 40),
            ride("recent", "board-a", 60.0, 20),
            ride("older", "board-a", 80.0, 30),
        ))
        assertEquals(0.9, result.getValue(20), 0.000001)
        assertEquals(0.6, result.getValue(30), 0.000001)
        assertEquals(1.5, result.values.sum(), 0.000001)
        assertFalse(result.containsKey(40))
    }

    @Test fun missingHistoryLeavesTheDistributionUnknown() {
        assertTrue(GapSpeedProfile.estimate(1.0, "board-a",
            listOf(ride("other", "board-b", 50.0, 20))).isEmpty())
        assertTrue(GapSpeedProfile.distribute(1.0, emptyMap()).isEmpty())
        assertTrue(GapSpeedProfile.distribute(Double.NaN, mapOf(20 to 1.0)).isEmpty())
        assertEquals(mapOf(20 to 1.0), GapSpeedProfile.distribute(
            1.0, mapOf(20 to 1.0, 21 to 4.0, 30 to Double.NaN, 40 to -1.0),
        ))
    }

    @Test fun rangeWindowPersistsObservedAndInferredDistancesSeparately() {
        val first = RangeCalibrationTracker.observe(null, "board-a", 60, "2030-01-01T00:00:00Z")!!
        val window = RangeCalibrationTracker.observe(
            first.activeWindow, "board-a", 55, "2030-01-01T00:10:00Z",
            distanceKm = 5.0, speedBucketDistancesKm = mapOf(20 to 3.0),
            estimatedSpeedBucketDistancesKm = mapOf(30 to 2.0),
        )!!.completedWindow!!
        val json = window.toJson()
        val restored = rangeDepletionWindowFromJson(JSONObject(json.toString()))
        assertEquals(window, restored)
        assertEquals(5.0, restored.recordedDistanceKm, 0.0)
        assertEquals(mapOf(20 to 3.0), restored.speedBucketDistancesKm)
        assertEquals(mapOf(30 to 2.0), restored.estimatedSpeedBucketDistancesKm)
        val estimate = RangeEstimator.estimate(55, null, listOf(restored), emptyList(), emptyList())
        assertEquals(setOf(20, 30), estimate.bucketBatteryPercentPer100Km.keys)
        // The inferred 30 km/h distribution is never pooled as measured common behaviour.
        assertEquals(setOf(20), estimate.commonSpeedBucketDistributionPercent.keys)
        json.remove("estimated_speed_bucket_distances_km")
        assertTrue(rangeDepletionWindowFromJson(json).estimatedSpeedBucketDistancesKm.isEmpty())
    }

    @Test fun chargeSummaryStoresGapAllocationWithoutInflatingMeasuredCoverage() {
        val source = ride("first", "board-a", 0.0, 20).copy(
            boardBatteryStart = 55, boardBatteryEnd = 55, boardBatteryMin = 55,
            odometerStartKm = 100.0, odometerEndKm = 100.0,
        )
        val first = ChargeCycleTracker.recordFrame(null, source, "board-a", null, true).activeCycle
        val gap = source.copy(id = "gap", distanceKm = 1.0,
            odometerStartKm = 101.0, odometerEndKm = 101.0, speedBucketDistancesKm = emptyMap())
        val after = ChargeCycleTracker.recordFrame(
            first, gap, "board-a", null, false, mapOf(20 to 0.6, 30 to 0.4),
        ).activeCycle
        assertEquals(0.0, after.profiledDistanceKm, 0.0)
        assertEquals(1.0, after.effectiveSpeedBucketDistancesKm.values.sum(), 0.000001)
        assertEquals(after, chargeCycleFromJson(JSONObject(after.toJson().toString())))
    }

    @Test fun inferredOnlyWindowDoesNotLearnItsOwnAssumedSpeedCosts() {
        val window = RangeCalibrationTracker.observe(
            null, "board-a", 60, "2030-01-01T00:00:00Z",
        )!!.activeWindow
        val completed = RangeCalibrationTracker.observe(
            window, "board-a", 55, "2030-01-01T00:10:00Z",
            distanceKm = 1.0, estimatedSpeedBucketDistancesKm = mapOf(20 to 0.5, 30 to 0.5),
        )!!.completedWindow!!
        val estimate = RangeEstimator.estimate(55, null, listOf(completed), emptyList(), emptyList())
        assertTrue(estimate.bucketBatteryPercentPer100Km.isEmpty())
    }
}

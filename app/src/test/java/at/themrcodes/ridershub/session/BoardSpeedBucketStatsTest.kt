package at.themrcodes.ridershub.session

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BoardSpeedBucketStatsTest {
    private fun window(id: String, measured: Map<Int, Double>, inferred: Map<Int, Double> = emptyMap(),
                       board: String = "board-a", distance: Double = measured.values.sum() + inferred.values.sum()) =
        RangeDepletionWindow(id, board, "2030-01-01T00:00:00Z", "2030-01-01T00:10:00Z",
            "2030-01-01T00:10:00Z", 60, 55, 55, distance, measured, inferred)
    private fun ride(id: String, board: String, km: Double) = summaryFromJson(JSONObject()
        .put("id", id).put("local_board_id", board).put("started_at", "2030-01-01T00:00:00Z")
        .put("distance_km", km).put("log_file", "synthetic.jsonl").put("active", false)
        .put("speed_bucket_distances_km", JSONObject().put("20", km)))

    @Test fun accumulatesBeyondRollingHistoryAndSeparatesInferredConfidence() {
        val registry = BoardSpeedBucketStatsRegistry()
        registry.recordDistance("board-a", mapOf(20 to 80.0, 30 to 40.0), mapOf(30 to 8.0), 1.0)
        val restored = BoardSpeedBucketStatsRegistry(registry.toJson().toString())
        restored.recordDistance("board-a", mapOf(20 to 30.0))
        restored.recordDistance("board-b", mapOf(20 to 999.0))
        val snapshot = restored.snapshot("board-a")!!
        assertEquals(150.0, snapshot.measuredDistanceKm, 0.0)
        assertEquals(8.0, snapshot.inferredDistanceKm, 0.0)
        assertEquals(1.0, snapshot.unattributedDistanceKm, 0.0)
        assertEquals(158.0, snapshot.attributedDistanceKm, 0.0)
        assertEquals(159.0, snapshot.accountedDistanceKm, 0.0)
        assertEquals(100.0, snapshot.buckets.sumOf { it.distanceSharePercent }, 1e-9)
        assertEquals(110.0, snapshot.buckets.first().confidenceKm, 0.0)
        assertNull(snapshot.buckets.first().fullBatteryRangeKm)
        assertEquals(110.0 / 150 * 100, snapshot.buckets.first().distanceSharePercent, 1e-9)
    }

    @Test fun migrationIsBoardScopedIdempotentAndDoesNotRecountAnActiveRide() {
        val r = BoardSpeedBucketStatsRegistry()
        val rides = listOf(ride("ride-a", "board-a", 3.0), ride("ride-a", "board-a", 3.0),
            ride("ride-b", "board-b", 9.0))
        val w = window("window-a", mapOf(20 to 2.0), mapOf(30 to 1.0))
        r.seed("board-a", rides, listOf(w, w, window("other", mapOf(20 to 9.0), board = "board-b")))
        val saved = r.snapshot("board-a")!!
        val restored = BoardSpeedBucketStatsRegistry(r.toJson().toString())
        restored.seed("board-a", rides, listOf(w))
        restored.recordWindow("board-a", w)
        assertEquals(saved, restored.snapshot("board-a"))
        assertEquals(3.0, saved.measuredDistanceKm, 0.0)
        assertEquals(1.0, saved.inferredDistanceKm, 0.0)
        assertEquals(1L, saved.completedWindows)
        assertTrue(saved.seededFromRetainedHistory)
        restored.recordDistance("board-a", mapOf(20 to 0.5))
        assertEquals(3.5, restored.snapshot("board-a")!!.measuredDistanceKm, 0.0)
    }

    @Test fun sufficientStatisticsMatchBatchFitAndSurviveRestartWithoutSourceWindows() {
        val windows = listOf(window("first", mapOf(20 to 2.0, 30 to 0.5)),
            window("second", mapOf(20 to 0.5, 30 to 1.0), mapOf(20 to 0.5)))
        val r = BoardSpeedBucketStatsRegistry()
        r.recordDistance("board-a", mapOf(20 to 3.0, 30 to 1.5))
        r.recordWindow("board-a", windows[0])
        val restored = BoardSpeedBucketStatsRegistry(r.toJson().toString())
        restored.recordWindow("board-a", windows[1])
        val actual = restored.snapshot("board-a")!!
        val batch = RangeEstimator.estimate(55, null, windows, emptyList(), emptyList())
        actual.buckets.forEach {
            assertEquals(batch.bucketBatteryPercentPer100Km.getValue(it.startKmh), it.batteryPercentPer100Km!!, 1e-8)
            assertEquals(10000 / it.batteryPercentPer100Km, it.fullBatteryRangeKm!!, 1e-8)
        }
        assertEquals(100.0, actual.buckets.sumOf { it.consumptionSharePercent!! }, 1e-8)
        assertEquals(2.5, actual.buckets.first().calibrationMeasuredKm, 0.0)
        assertEquals(0.5, actual.buckets.first().calibrationInferredKm, 0.0)
        assertEquals(actual, BoardSpeedBucketStatsRegistry(restored.toJson().toString()).snapshot("board-a"))
    }

    @Test fun unsupportedAndInferredOnlyWindowsDoNotInventBucketConsumption() {
        val r = BoardSpeedBucketStatsRegistry()
        r.recordDistance("board-a", mapOf(20 to 2.0), mapOf(30 to 2.0))
        r.recordWindow("board-a", window("gap", emptyMap(), mapOf(30 to 2.0)))
        r.recordWindow("board-a", window("poor", mapOf(20 to 0.2), distance = 2.0))
        r.recordWindow("board-a", window("wrong-board", mapOf(20 to 5.0), board = "board-b"))
        val s = r.snapshot("board-a")!!
        assertEquals(2L, s.completedWindows)
        s.buckets.forEach {
            assertNull(it.fullBatteryRangeKm)
            assertEquals(0L, it.contributingWindows)
            assertEquals(0.0, it.calibrationMeasuredKm, 0.0)
        }
    }

    @Test fun lifetimeModelRetainsMoreThanFiveHundredWindowsAndFlagsItsBounds() {
        var r = BoardSpeedBucketStatsRegistry()
        repeat(600) { n ->
            r.recordDistance("board-a", mapOf(20 to 7.5))
            r.recordWindow("board-a", window("window-$n", mapOf(20 to 7.5)))
            if (n == 300) r = BoardSpeedBucketStatsRegistry(r.toJson().toString())
        }
        val s = r.snapshot("board-a")!!
        assertEquals(600L, s.completedWindows)
        assertEquals(4500.0, s.measuredDistanceKm, 0.0)
        assertEquals(150.0, s.buckets.single().fullBatteryRangeKm!!, 1e-9)
        assertTrue(s.buckets.single().modelLimitReached)
        assertTrue(s.consumptionRates().isEmpty())
        // Persistent size depends on buckets, not on the number of retained raw windows.
        assertTrue(r.toJson().toString().length < 2000)
    }

    @Test fun badEntriesCannotPoisonAnotherBoardOrDistanceTotals() {
        val r = BoardSpeedBucketStatsRegistry()
        r.recordDistance("good", mapOf(20 to 3.0, 21 to 5.0, 30 to Double.NaN, 40 to -2.0))
        val json = r.toJson().put("bad", JSONObject().put("schema_version", 1))
        val restored = BoardSpeedBucketStatsRegistry(json.toString())
        assertEquals(3.0, restored.snapshot("good")!!.measuredDistanceKm, 0.0)
        assertNull(restored.snapshot("bad"))
        assertNull(BoardSpeedBucketStatsRegistry("broken").snapshot("good"))
    }
}

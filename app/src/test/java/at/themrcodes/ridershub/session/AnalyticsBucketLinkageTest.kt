package at.themrcodes.ridershub.session

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnalyticsBucketLinkageTest {
    private fun stats(): BoardSpeedBucketStatistics {
        val r = BoardSpeedBucketStatsRegistry()
        r.recordDistance("board-a", mapOf(20 to 80.0, 30 to 20.0), mapOf(30 to 10.0))
        repeat(4) { n ->
            val bucket = if (n % 2 == 0) 20 else 30
            val km = if (bucket == 20) 2.5 else 1.25
            r.recordWindow("board-a", RangeDepletionWindow("window-$n", "board-a",
                "2030-01-01T00:00:00Z", "2030-01-01T00:10:00Z", "2030-01-01T00:10:00Z",
                60, 55, 55, km, mapOf(bucket to km)))
        }
        return BoardSpeedBucketStatsRegistry(r.toJson().toString()).snapshot("board-a")!!
    }
    private fun ride(active: Boolean) = summaryFromJson(JSONObject().put("id", "ride-a")
        .put("local_board_id", "board-a").put("started_at", "2030-01-01T00:00:00Z")
        .put("distance_km", 1.0).put("log_file", "synthetic.jsonl").put("active", active)
        .put("speed_bucket_distances_km", JSONObject().put("30", 1.0)))
    private fun range(s: BoardSpeedBucketStatistics, rides: List<RideSummary> = emptyList(), board: String = "board-a") =
        RangeEstimator.estimate(50, null, emptyList(), rides, emptyList(), 45.0, board, s)
    private fun cycle() = ChargeCycleSummary("cycle-a", "board-a", "2030-01-01T00:00:00Z",
        "2030-01-01T01:00:00Z", "2030-01-01T01:00:00Z", ChargeCycleStartReason.INFERRED_RECHARGE,
        ChargeCycleEndReason.INFERRED_RECHARGE, 30, 80, 60, 60, 80,
        null, null, null, null, 5.0, 600.0, mapOf(30 to 5.0), 100.0, 105.0, 1)

    @Test fun rangeUsesSavedConsumptionAndDistanceMixAfterOriginalHistoryIsGone() {
        val s = stats()
        val result = range(s, listOf(ride(false)))
        val rates = s.consumptionRates()
        val commonRate = rates.getValue(20) * .8 + rates.getValue(30) * .2
        assertTrue(result.usesAccumulatedBucketConsumption)
        assertEquals(45.0 / commonRate, result.remainingKm!!, 1e-9)
        assertEquals(mapOf(20 to 80.0, 30 to 20.0), result.commonSpeedBucketDistributionPercent)
        assertEquals(s, result.speedBucketStatistics)
        assertEquals(80.0, result.speedBucketStatistics!!.buckets.first().confidenceKm, 0.0)
        assertEquals(7.5, result.observedDistanceKm, 0.0)
        assertEquals(rates.mapValues { it.value * 100 }, result.bucketBatteryPercentPer100Km)
    }

    @Test fun activeTripChangesForecastMixButKeepsTheSharedConsumptionModel() {
        val s = stats()
        val result = range(s, listOf(ride(true)))
        assertEquals(45.0 / s.consumptionRates().getValue(30), result.remainingKm!!, 1e-9)
        assertEquals(range(s).bucketBatteryPercentPer100Km, result.bucketBatteryPercentPer100Km)
        assertEquals(mapOf(20 to 80.0, 30 to 20.0), result.commonSpeedBucketDistributionPercent)
    }

    @Test fun rangeRejectsStatisticsFromAnotherBoard() {
        val result = range(stats(), board = "board-b")
        assertEquals(RangeEstimateStatus.COLLECTING_DATA, result.status)
        assertNull(result.speedBucketStatistics)
        assertNull(result.remainingKm)
        assertFalse(result.usesAccumulatedBucketConsumption)
    }

    @Test fun capacityNormalizesUsingSameRatesAndMeasuredReferenceMixAsRange() {
        val s = stats()
        val result = BatteryLongevityEstimator.estimate(ChargeCycleStoreSnapshot(emptyList(), listOf(cycle())),
            "board-a", null, s)
        val rates = s.consumptionRates()
        val reference = rates.getValue(20) * .8 + rates.getValue(30) * .2
        assertEquals(100 * (5 * rates.getValue(30)) / (20 * reference), result.currentFullRangeKm!!, 1e-9)
        assertEquals(s, result.speedBucketStatistics)
        assertEquals(1, result.usableCycleCount)
        // Distance-equivalent cycle age still uses observed depletion, not a fitted bucket's range.
        assertEquals(25.0, result.cycleAge!!.referenceFullRangeKm, 1e-9)
        val without = BatteryLongevityEstimator.estimate(ChargeCycleStoreSnapshot(emptyList(), listOf(cycle())))
        val wrong = BatteryLongevityEstimator.estimate(ChargeCycleStoreSnapshot(emptyList(), listOf(cycle())),
            "board-a", null, s.copy(localBoardId = "board-b"))
        assertEquals(without, wrong)
    }

    @Test fun accumulatedEvidenceDoesNotBypassCapacityCoverageOrRechargeRequirements() {
        val s = stats()
        val candidates = listOf(cycle().copy(odometerEndKm = 110.0),
            cycle().copy(startReason = ChargeCycleStartReason.FIRST_OBSERVATION, inferredRechargeIncreasePercent = null))
        candidates.forEach { c ->
            val result = BatteryLongevityEstimator.estimate(ChargeCycleStoreSnapshot(emptyList(), listOf(c)),
                "board-a", null, s)
            assertEquals(BatteryLongevityStatus.COLLECTING_DATA, result.status)
            assertEquals(0, result.usableCycleCount)
            assertNull(result.currentFullRangeKm)
            assertEquals(s, result.speedBucketStatistics)
        }
    }

    @Test fun gapDistributionUsesMeasuredHistoryWithoutFeedingInferredGapsBackIntoItself() {
        val s = stats()
        val result = GapSpeedProfile.estimate(1.0, "board-a", listOf(ride(false)), s)
        assertEquals(mapOf(20 to .8, 30 to .2), result)
        val fallback = GapSpeedProfile.estimate(1.0, "board-b", emptyList(), s)
        assertTrue(fallback.isEmpty())
    }

    @Test fun archiveEvidenceIdentifiesTheModelAndPreservesConfidenceSeparatelyFromInferredKm() {
        val s = stats()
        val evidence = s.buckets.first { it.startKmh == 30 }
        val json = BatteryFrameDiagnostics("tracking", basePercentPerKm = evidence.batteryPercentPer100Km!! / 100,
            bucketModelSource = "cumulative", bucketEvidence = evidence).toJson()
        val restored = JSONObject(json.toString())
        assertEquals("cumulative", restored.getString("bucket_model_source"))
        val support = restored.getJSONObject("bucket_model_evidence")
        assertEquals(20.0, support.getDouble("confidence_km"), 0.0)
        assertEquals(10.0, support.getDouble("inferred_distance_km"), 0.0)
        assertEquals(2L, support.getLong("contributing_windows"))
        assertTrue(BatteryFrameDiagnostics("invalid_crc").toJson().isNull("bucket_model_evidence"))
    }
}

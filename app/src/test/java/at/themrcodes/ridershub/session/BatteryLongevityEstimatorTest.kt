package at.themrcodes.ridershub.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class BatteryLongevityEstimatorTest {
    @Test
    fun excludesInitialObservationUntilARechargeIsInferred() {
        val initial = cycle(
            id = "initial",
            observedAt = "2030-01-02T10:00:00Z",
            distanceKm = 10.0,
            depletion = 20,
            buckets = mapOf(15 to 10.0),
        ).copy(
            startReason = ChargeCycleStartReason.FIRST_OBSERVATION,
            inferredRechargeIncreasePercent = null,
        )

        val snapshot = BatteryLongevityEstimator.estimate(
            ChargeCycleStoreSnapshot(
                activeCycles = listOf(initial),
                completedCycles = emptyList(),
            ),
        )

        assertEquals(BatteryLongevityStatus.COLLECTING_DATA, snapshot.status)
        assertEquals(0, snapshot.observedCycleCount)
        assertEquals(0, snapshot.usableCycleCount)
        assertTrue(snapshot.observations.isEmpty())
        assertNull(snapshot.currentFullRangeKm)
        assertTrue(snapshot.message.contains("battery rises by at least 10 percentage points"))
    }

    @Test
    fun firstInferredRechargeCreatesFirstVisibleCycle() {
        val baseline = cycle(
            id = "baseline",
            observedAt = "2030-01-02T10:00:00Z",
            distanceKm = 10.0,
            depletion = 20,
            buckets = mapOf(15 to 10.0),
        ).copy(
            startReason = ChargeCycleStartReason.FIRST_OBSERVATION,
            inferredRechargeIncreasePercent = null,
        )
        val confirmed = cycle(
            id = "confirmed",
            observedAt = "2030-01-03T10:00:00Z",
            distanceKm = 10.0,
            depletion = 20,
            buckets = mapOf(15 to 10.0),
        )

        val snapshot = BatteryLongevityEstimator.estimate(
            ChargeCycleStoreSnapshot(
                activeCycles = listOf(confirmed),
                completedCycles = listOf(baseline),
            ),
        )

        assertEquals(1, snapshot.observedCycleCount)
        assertEquals(1, snapshot.usableCycleCount)
        assertEquals(listOf("confirmed"), snapshot.observations.map { it.cycleId })
    }

    @Test
    fun normalizesDifferentSpeedProfilesBeforeComparingCapacity() {
        val snapshot = BatteryLongevityEstimator.estimate(
            ChargeCycleStoreSnapshot(
                activeCycles = emptyList(),
                completedCycles = listOf(
                    cycle("slow", "2030-01-02T10:00:00Z", 10.0, 20, mapOf(10 to 10.0)),
                    cycle("fast", "2030-01-03T10:00:00Z", 10.0, 40, mapOf(30 to 10.0)),
                ),
            ),
        )

        assertEquals(2, snapshot.usableCycleCount)
        val slow = snapshot.observations.first { it.cycleId == "slow" }.normalizedFullRangeKm
        val fast = snapshot.observations.first { it.cycleId == "fast" }.normalizedFullRangeKm
        assertTrue(kotlin.math.abs(slow - fast) < 2.0)
        assertTrue(requireNotNull(snapshot.currentFullRangeKm) in 30.0..36.0)
    }

    @Test
    fun rejectsCycleWhoseRecordedProfileMissesOdometerDistance() {
        val incomplete = cycle(
            id = "gap",
            observedAt = "2030-01-03T10:00:00Z",
            distanceKm = 4.0,
            depletion = 20,
            buckets = mapOf(15 to 4.0),
        ).copy(odometerStartKm = 100.0, odometerEndKm = 110.0)

        val snapshot = BatteryLongevityEstimator.estimate(
            ChargeCycleStoreSnapshot(emptyList(), listOf(incomplete)),
        )

        assertEquals(BatteryLongevityStatus.COLLECTING_DATA, snapshot.status)
        assertEquals(1, snapshot.observedCycleCount)
        assertEquals(0, snapshot.usableCycleCount)
    }

    @Test
    fun chartAggregatesByRequestedTimeAndWeightsByDepletion() {
        val observations = listOf(
            observation("one", "2030-01-02T10:00:00Z", 20.0, 10.0),
            observation("two", "2030-01-02T18:00:00Z", 40.0, 30.0),
            observation("three", "2030-01-10T10:00:00Z", 50.0, 20.0),
        )

        val daily = BatteryLongevityChart.aggregate(
            observations,
            LongevityGranularity.DAY,
            ZoneId.of("UTC"),
        )
        val weekly = BatteryLongevityChart.aggregate(
            observations,
            LongevityGranularity.WEEK,
            ZoneId.of("UTC"),
        )

        assertEquals(2, daily.size)
        assertEquals(35.0, daily.first().fullRangeKm, 0.0001)
        assertEquals(2, weekly.size)
        assertEquals("W01", weekly.first().label)
    }

    @Test
    fun chartDrillDownIncludesEarlierPopulatedPeriodsOutsideSelectedParent() {
        val observations = listOf(
            observation("jan", "2030-01-15T10:00:00Z", 30.0, 20.0),
            observation("feb", "2030-02-15T10:00:00Z", 35.0, 20.0),
            observation("next-year", "2031-01-15T10:00:00Z", 40.0, 20.0),
        )
        val yearly = BatteryLongevityChart.aggregate(
            observations,
            LongevityGranularity.YEAR,
            ZoneId.of("UTC"),
        )
        val selected = yearly.last()

        val months = BatteryLongevityChart.aggregate(
            observations = observations,
            granularity = LongevityGranularity.MONTH,
            zoneId = ZoneId.of("UTC"),
            anchor = selected.latestObservationAt,
        )

        assertEquals(listOf("Jan", "Feb", "Jan"), months.map { it.label })
        assertEquals(selected.latestObservationAt, months.last().latestObservationAt)
    }

    @Test
    fun chartShowsEightPopulatedPeriodsEndingAtAnchorAndSkipsEmptyPeriods() {
        val observations = (0..11).map { i ->
            observation("point-$i", Instant.parse("2030-01-01T10:00:00Z").plusSeconds(i * 86400L * 3).toString(), 30.0, 20.0)
        }
        val all = BatteryLongevityChart.aggregate(observations, LongevityGranularity.DAY, ZoneId.of("UTC"))
        assertEquals(8, all.size)
        assertEquals(observations[4].observedAt, all.first().latestObservationAt)
        val selected = BatteryLongevityChart.aggregate(observations, LongevityGranularity.DAY,
            ZoneId.of("UTC"), anchor = observations[8].observedAt)
        assertEquals(8, selected.size)
        assertEquals(observations[1].observedAt, selected.first().latestObservationAt)
        assertEquals(observations[8].observedAt, selected.last().latestObservationAt)
        assertTrue(BatteryLongevityChart.aggregate(emptyList(), LongevityGranularity.DAY, ZoneId.of("UTC")).isEmpty())
    }

    @Test
    fun chartAnchorIncludesWholeLocalPeriodAndSurvivesZoomOutAcrossYearBoundary() {
        val observations = listOf(
            observation("before", "2029-12-30T12:00:00Z", 20.0, 10.0),
            observation("early", "2029-12-31T23:15:00Z", 30.0, 10.0),
            observation("late", "2030-01-01T20:00:00Z", 40.0, 10.0),
            observation("after", "2030-01-02T12:00:00Z", 50.0, 10.0),
        )
        val zone = ZoneId.of("Europe/Vienna")
        val anchor = observations[1].observedAt
        val days = BatteryLongevityChart.aggregate(observations, LongevityGranularity.DAY, zone, anchor)
        assertEquals(2, days.size)
        assertEquals(35.0, days.last().fullRangeKm, 0.0001)
        val years = BatteryLongevityChart.aggregate(observations, LongevityGranularity.YEAR, zone, anchor)
        assertEquals(listOf("2029", "2030"), years.map { it.label })
        val dst = listOf(
            observation("dst-early", "2030-03-31T00:15:00Z", 20.0, 10.0),
            observation("dst-late", "2030-03-31T21:15:00Z", 40.0, 10.0),
            observation("dst-next", "2030-03-31T22:15:00Z", 50.0, 10.0),
        )
        val dstDays = BatteryLongevityChart.aggregate(dst, LongevityGranularity.DAY, zone, dst.first().observedAt)
        assertEquals(1, dstDays.size)
        assertEquals(30.0, dstDays.single().fullRangeKm, 0.0001)
    }

    @Test
    fun lifetimeIncludesPriorUseAndUnrecordedGapsWithoutCountingTopUpsAsFullCycles() {
        val first = cycle("first", "2030-01-02T10:00:00Z", 10.0, 20, mapOf(15 to 10.0))
        val later = cycle("later", "2030-01-03T10:00:00Z", 10.0, 20, mapOf(15 to 10.0))
            .copy(odometerStartKm = 150.0, odometerEndKm = 160.0)
        val snapshot = BatteryLongevityEstimator.estimate(
            ChargeCycleStoreSnapshot(emptyList(), listOf(later, first)),
        )
        val age = requireNotNull(snapshot.cycleAge)
        assertEquals(50.0, age.referenceFullRangeKm, 0.0001)
        assertEquals(2.0, age.estimatedPriorFullCycles, 0.0001)
        assertEquals(3.2, age.estimatedLifetimeFullCycles, 0.0001)
        assertEquals(2, snapshot.observedCycleCount)
        assertEquals(listOf(2.2, 3.2), snapshot.observations.map { it.estimatedLifetimeFullCycles })
    }

    @Test
    fun identicalConsumptionAtDifferentBoardAgesUsesEachBoardsOwnOdometer() {
        val young = cycle("young", "2030-01-02T10:00:00Z", 10.0, 20, mapOf(15 to 10.0))
        val old = young.copy(
            id = "old", localBoardId = "other-synthetic-board",
            odometerStartKm = 1000.0, odometerEndKm = 1010.0,
            lastObservedAt = "2030-01-03T10:00:00Z", closedAt = "2030-01-03T10:00:00Z",
        )
        val snapshot = BatteryLongevityEstimator.estimate(
            ChargeCycleStoreSnapshot(emptyList(), listOf(young, old)),
        )
        assertEquals(1, snapshot.observedCycleCount)
        assertEquals(20.2, requireNotNull(snapshot.cycleAge).estimatedLifetimeFullCycles, 0.0001)
        assertEquals(50.0, requireNotNull(snapshot.currentFullRangeKm), 0.0001)
    }

    @Test
    fun ambiguousOdometerDoesNotInventAnAge() {
        val first = cycle("first", "2030-01-02T10:00:00Z", 10.0, 20, mapOf(15 to 10.0))
        for (start in listOf(null, Double.NaN, -1.0, 50.0)) {
            val later = first.copy(
                id = "later", startedAt = "2030-01-03T10:00:00Z",
                odometerStartKm = start, odometerEndKm = 60.0,
            )
            val snapshot = BatteryLongevityEstimator.estimate(
                ChargeCycleStoreSnapshot(emptyList(), listOf(first, later)),
            )
            assertNull(snapshot.cycleAge)
        }
    }

    @Test
    fun legacySmallRechargeBoundariesAreExcludedFromVisibleChargeCount() {
        val old = cycle("old", "2030-01-02T10:00:00Z", 10.0, 20, mapOf(15 to 10.0))
            .copy(inferredRechargeIncreasePercent = 5)
        val snapshot = BatteryLongevityEstimator.estimate(
            ChargeCycleStoreSnapshot(emptyList(), listOf(old)),
        )
        assertEquals(0, snapshot.observedCycleCount)
        assertNull(snapshot.cycleAge)
    }

    private fun cycle(
        id: String,
        observedAt: String,
        distanceKm: Double,
        depletion: Int,
        buckets: Map<Int, Double>,
    ) = ChargeCycleSummary(
        id = id,
        localBoardId = "synthetic-board",
        startedAt = Instant.parse(observedAt).minusSeconds(3600).toString(),
        lastObservedAt = observedAt,
        closedAt = observedAt,
        startReason = ChargeCycleStartReason.INFERRED_RECHARGE,
        endReason = ChargeCycleEndReason.INFERRED_RECHARGE,
        inferredRechargeIncreasePercent = 50,
        batteryStartPercent = 90,
        batteryEndPercent = 90 - depletion,
        batteryMinPercent = 90 - depletion,
        batteryMaxPercent = 90,
        restingVoltageStart = 41.0,
        restingVoltageEnd = 38.0,
        restingVoltageMin = 38.0,
        restingVoltageMax = 41.0,
        recordedDistanceKm = distanceKm,
        movingSeconds = 900.0,
        speedBucketDistancesKm = buckets,
        odometerStartKm = 100.0,
        odometerEndKm = 100.0 + distanceKm,
        rideCount = 1,
    )

    private fun observation(
        id: String,
        at: String,
        rangeKm: Double,
        depletion: Double,
    ) = BatteryCapacityObservation(
        cycleId = id,
        observedAt = Instant.parse(at),
        normalizedFullRangeKm = rangeKm,
        observedDistanceKm = 5.0,
        observedDepletionPercent = depletion,
        fullChargeObserved = true,
    )
}

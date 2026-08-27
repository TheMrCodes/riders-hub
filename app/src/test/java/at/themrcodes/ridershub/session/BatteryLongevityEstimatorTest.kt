package at.themrcodes.ridershub.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class BatteryLongevityEstimatorTest {
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
    fun chartFocusDrillsIntoOnlyTheSelectedBarInterval() {
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
        val selected = yearly.first()

        val months = BatteryLongevityChart.aggregate(
            observations = observations,
            granularity = LongevityGranularity.MONTH,
            zoneId = ZoneId.of("UTC"),
            focusStart = selected.start,
            focusEndExclusive = selected.endExclusive,
        )

        assertEquals(listOf("Jan", "Feb"), months.map { it.label })
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

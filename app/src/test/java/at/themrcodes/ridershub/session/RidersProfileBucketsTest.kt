package at.themrcodes.ridershub.session

import org.junit.Assert.*
import org.junit.Test

class RidersProfileBucketsTest {
    private fun bucket(start: Int, km: Double = 10.0) = SpeedBucketStatistics(
        startKmh = start,
        measuredDistanceKm = km,
        distanceSharePercent = 7.0,
        inferredDistanceKm = 0.0,
        calibrationMeasuredKm = 1.0,
        calibrationInferredKm = 0.0,
        contributingWindows = 1,
        batteryPercentPer100Km = 200.0,
        fullBatteryRangeKm = 50.0,
        consumptionSharePercent = null,
        modelLimitReached = false,
    )

    private fun statistics(vararg buckets: SpeedBucketStatistics) = BoardSpeedBucketStatistics(
        localBoardId = "synthetic-board",
        buckets = buckets.toList(),
        unattributedDistanceKm = 0.0,
        completedWindows = 1,
        observedDepletionPercent = 5.0,
        seededFromRetainedHistory = false,
    )

    @Test fun trimsSparseTailsAndInteriorBucketsAndNormalizesRetainedDistance() {
        val stats = statistics(bucket(40, 0.2), bucket(20, 15.0), bucket(0, 0.5),
            bucket(10, 5.0), bucket(15, 4.99), bucket(35, 0.0))

        val chart = stats.profileBuckets(energyUse = false)

        assertEquals(listOf(10, 20), chart.map { it.startKmh })
        assertEquals(listOf(25.0, 75.0), chart.map { it.distanceSharePercent })
        assertEquals(100.0, chart.sumOf { it.distanceSharePercent }, 1e-9)
    }

    @Test fun inferredDistanceCannotSupplyMeasuredSupport() {
        val below = bucket(10, 4.999).copy(inferredDistanceKm = 100.0,
            calibrationInferredKm = 100.0)
        val atThreshold = bucket(20, 5.0)
        val stats = statistics(below, atThreshold, bucket(30, Double.NaN),
            bucket(40, Double.POSITIVE_INFINITY), bucket(-5), bucket(22))

        assertEquals(listOf(20), stats.profileBuckets(false).map { it.startKmh })
        assertEquals(listOf(20), stats.profileBuckets(true).map { it.startKmh })
        assertEquals(100.0, stats.profileBuckets(false).single().distanceSharePercent, 0.0)
    }

    @Test fun energyChartRequiresCalibrationAndUsableRateAsWellAsDistance() {
        val stats = statistics(bucket(0, 4.99), bucket(5).copy(calibrationMeasuredKm = 0.0),
            bucket(10).copy(contributingWindows = 0), bucket(15).copy(modelLimitReached = true),
            bucket(20).copy(batteryPercentPer100Km = null),
            bucket(25).copy(batteryPercentPer100Km = Double.NaN),
            bucket(30).copy(calibrationMeasuredKm = Double.POSITIVE_INFINITY), bucket(35),
            bucket(40).copy(batteryPercentPer100Km = 0.0))

        assertEquals(listOf(35), stats.profileBuckets(true).map { it.startKmh })
        assertEquals((5..40 step 5).toList(), stats.profileBuckets(false).map { it.startKmh })
    }

    @Test fun insufficientDataHasNoChartBuckets() {
        assertTrue(statistics().profileBuckets(false).isEmpty())
        assertTrue(statistics(bucket(10, 0.5)).profileBuckets(false).isEmpty())
        assertTrue(statistics(bucket(10).copy(contributingWindows = 0)).profileBuckets(true).isEmpty())
    }

    @Test fun chartSelectionPreservesEvidenceAndForecastInputs() {
        val stats = statistics(bucket(0, 0.2).copy(inferredDistanceKm = 1.0), bucket(20, 15.0))
        val originalBuckets = stats.buckets.toList()
        val measured = stats.measuredProfile()
        val rates = stats.consumptionRates()
        val accounted = stats.accountedDistanceKm

        stats.profileBuckets(false)
        stats.profileBuckets(true)

        assertEquals(originalBuckets, stats.buckets)
        assertEquals(measured, stats.measuredProfile())
        assertEquals(rates, stats.consumptionRates())
        assertEquals(accounted, stats.accountedDistanceKm, 0.0)
    }
}

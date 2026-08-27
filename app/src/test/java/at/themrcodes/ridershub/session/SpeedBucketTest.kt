package at.themrcodes.ridershub.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedBucketTest {
    @Test
    fun usesFiveKilometrePerHourBucketsStartingAtZero() {
        assertEquals(0, speedBucketStartKmh(0.0))
        assertEquals(0, speedBucketStartKmh(4.999))
        assertEquals(5, speedBucketStartKmh(5.0))
        assertEquals(25, speedBucketStartKmh(29.9))
    }

    @Test
    fun accumulatesDistanceWithinBucket() {
        val first = addSpeedBucketDistance(emptyMap(), speedKmh = 12.0, distanceKm = 0.1)
        val second = addSpeedBucketDistance(first, speedKmh = 14.9, distanceKm = 0.2)

        assertEquals(0.3, second.getValue(10), 0.0001)
    }
}

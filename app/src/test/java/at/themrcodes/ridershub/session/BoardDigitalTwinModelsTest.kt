package at.themrcodes.ridershub.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardDigitalTwinModelsTest {
    @Test fun remoteBatteryObservationIsAnIntervalAndClipsAtPhysicalBounds() {
        assertEquals(BatteryObservationInterval(60, 57.5, 62.5),
            RemoteBatteryObservationModel.interval(60))
        assertEquals(BatteryObservationInterval(0, 0.0, 2.5),
            RemoteBatteryObservationModel.interval(0))
        assertEquals(BatteryObservationInterval(100, 97.5, 100.0),
            RemoteBatteryObservationModel.interval(100))
    }

    @Test fun inferredDriveUsesBucketRatesAndFallsBackWithoutInventingMissingRates() {
        val transition = DigitalTwinDriveModel.inferredTransition(
            inferredSpeedBucketDistancesKm = mapOf(20 to 0.6, 30 to 0.4),
            bucketPercentPer100Km = mapOf(20 to 200.0),
            fallbackKmPerPercent = 0.25,
        )!!
        assertEquals(1.0, transition.distanceKm, 0.0)
        assertEquals(2.8, transition.expectedBatteryUsePercent, 1e-12)
        assertEquals(2.8, transition.effectivePercentPerKm, 1e-12)
        assertNull(DigitalTwinDriveModel.inferredTransition(
            inferredSpeedBucketDistancesKm = mapOf(20 to 0.6, 30 to 0.4),
            bucketPercentPer100Km = mapOf(20 to 200.0),
            fallbackKmPerPercent = null,
        ))
    }

    @Test fun inferredDriveRejectsDistanceThatCannotBeRepresentedByTheModel() {
        assertNull(DigitalTwinDriveModel.inferredTransition(
            inferredSpeedBucketDistancesKm = emptyMap(),
            bucketPercentPer100Km = emptyMap(),
            fallbackKmPerPercent = 0.25,
        ))
        assertNull(DigitalTwinDriveModel.inferredTransition(
            inferredSpeedBucketDistancesKm = mapOf(20 to 1.0),
            bucketPercentPer100Km = mapOf(20 to 0.0),
            fallbackKmPerPercent = null,
        ))
    }
}

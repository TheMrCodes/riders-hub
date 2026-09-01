package at.themrcodes.ridershub.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RangeCalibrationTrackerTest {
    @Test
    fun zeroDropDistancesAccumulateUntilFivePercentIsObserved() {
        var update = observe(null, 80, "a", 1.0, 10)
        assertNull(update.completedWindow)
        update = observe(update.activeWindow, 80, "b", 1.0, 20)
        assertNull(update.completedWindow)
        update = observe(update.activeWindow, 75, "c", 1.0, 30)

        assertNotNull(update.completedWindow)
        val completed = requireNotNull(update.completedWindow)
        assertEquals(5, completed.observedDepletionPercent)
        assertEquals(3.0, completed.recordedDistanceKm, 0.0001)
        assertEquals(1.0, completed.speedBucketDistancesKm.getValue(10), 0.0001)
        assertEquals(1.0, completed.speedBucketDistancesKm.getValue(20), 0.0001)
        assertEquals(1.0, completed.speedBucketDistancesKm.getValue(30), 0.0001)
        assertEquals(75, update.activeWindow.startBatteryPercent)
    }

    @Test
    fun rechargeDropsAnIncompleteDepletionWindow() {
        var update = observe(null, 80, "a", 2.0, 20)
        update = observe(update.activeWindow, 78, "b", 1.0, 20)
        update = observe(update.activeWindow, 84, "c", 0.0, 20, allowRechargeReset = true)

        assertNull(update.completedWindow)
        assertEquals(84, update.activeWindow.startBatteryPercent)
        assertEquals(0.0, update.activeWindow.recordedDistanceKm, 0.0001)

        update = observe(update.activeWindow, 79, "d", 1.0, 30)
        assertNotNull(update.completedWindow)
        assertEquals(1.0, requireNotNull(update.completedWindow).recordedDistanceKm, 0.0001)
    }

    @Test
    fun batteryReboundDuringOneRideIsNotTreatedAsARecharge() {
        var update = observe(null, 80, "a", 1.0, 20)
        update = observe(update.activeWindow, 78, "b", 1.0, 20)
        update = observe(update.activeWindow, 84, "c", 1.0, 20)

        assertNull(update.completedWindow)
        assertEquals(80, update.activeWindow.startBatteryPercent)
        assertEquals(78, update.activeWindow.minBatteryPercent)
        assertEquals(3.0, update.activeWindow.recordedDistanceKm, 0.0001)
    }

    @Test
    fun depletionWindowPersistenceRoundTrips() {
        var update = observe(null, 80, "a", 1.0, 10)
        update = observe(update.activeWindow, 75, "b", 1.5, 20)
        val completed = requireNotNull(update.completedWindow)

        assertEquals(completed, rangeDepletionWindowFromJson(completed.toJson()))
        assertEquals(
            update.activeWindow,
            rangeDepletionWindowFromJson(update.activeWindow.toJson()),
        )
    }

    private fun observe(
        current: RangeDepletionWindow?,
        battery: Int,
        at: String,
        distance: Double,
        speedBucket: Int,
        allowRechargeReset: Boolean = false,
    ): RangeCalibrationUpdate = requireNotNull(
        RangeCalibrationTracker.observe(
            current = current,
            localBoardId = "board-a",
            batteryPercent = battery,
            observedAt = at,
            distanceKm = distance,
            speedBucketDistancesKm = if (distance > 0.0) mapOf(speedBucket to distance) else emptyMap(),
            allowRechargeReset = allowRechargeReset,
        ),
    )
}

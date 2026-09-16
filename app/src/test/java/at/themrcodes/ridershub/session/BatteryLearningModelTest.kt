package at.themrcodes.ridershub.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BatteryLearningModelTest {
    @Test fun speedBucketUnitConversionAtTheMinimumRateKeepsLearningCoverage() {
        val minimum = 1.0 / 1.5
        val converted = minimum * 100.0 / 100.0
        assertEquals(minimum, converted.validBatteryRate()!!, 0.0)
        val ride = Replay(baseRate = converted)
        ride.anchor()
        ride.completeInterval(90, distancePerTick = 0.05)
        assertEquals(1, ride.model.acceptedWindows)
        assertTrue(ride.model.correctionFactor > 1.0)
        assertTrue(ride.model.lastPredictionErrorPercent != null)
        assertNull((minimum - 0.001).validBatteryRate())
    }

    @Test fun partialFirstPlateauDoesNotTrainTheModel() {
        val ride = Replay()
        ride.tick(100, elapsed = 0.0)
        repeat(10) { ride.tick(100, distance = 0.01) }
        ride.dropAndConfirm(95)
        assertEquals(0, ride.model.acceptedWindows)
    }

    @Test fun repeatedObservedErrorsReduceErrorOnTheNextUnseenInterval() {
        val ride = Replay(baseRate = 2.5)
        ride.anchor()
        val initialError = abs(5.0 - ride.model.rate(2.5)!!)
        repeat(8) { index -> ride.completeInterval(90 - index * 5) }
        val nextPrediction = ride.model.rate(2.5)!! // next unseen 1-km, 5-point interval
        assertEquals(8, ride.model.acceptedWindows)
        assertTrue(abs(5.0 - nextPrediction) < initialError * 0.7)
        assertTrue(ride.model.lastPredictionErrorPercent!! < initialError)
    }

    @Test fun overpredictionAndChangingConsumptionAreCorrectedInBothDirections() {
        val over = Replay(baseRate = 10.0)
        over.anchor()
        repeat(8) { over.completeInterval(90 - it * 5) }
        assertTrue(abs(over.model.rate(10.0)!! - 5.0) < 3.5)
        val changed = Replay(baseRate = 10.0, model = over.model)
        changed.anchor()
        val previousError = abs(changed.model.rate(10.0)!! - 2.5)
        repeat(8) { changed.completeInterval(90 - it * 5, distancePerTick = 0.02) }
        assertTrue(abs(changed.model.rate(10.0)!! - 2.5) < previousError * 0.9)
    }

    @Test fun learnsWithoutAnExistingRangeModelAndSurvivesSerialization() {
        val ride = Replay(baseRate = null)
        ride.anchor()
        ride.completeInterval(90)
        assertNull(ride.model.rate(null))
        assertNull(ride.model.lastPredictionErrorPercent)
        val restored = BatteryLearningModel.fromJson(JSONObject(ride.model.toJson().toString()))
        assertEquals(ride.model, restored)
        val nextRide = Replay(baseRate = null, model = restored)
        nextRide.anchor()
        nextRide.completeInterval(90)
        nextRide.completeInterval(85)
        assertTrue(nextRide.model.rate(null)!! in 4.9..5.0)
    }

    @Test fun briefSagAndReboundCannotTrainOrBridgeAnInterval() {
        val ride = Replay()
        ride.anchor()
        repeat(100) { ride.tick(95, distance = 0.01) }
        ride.tick(90, reliable = false)
        repeat(10) { ride.tick(90, reliable = false) }
        ride.tick(95)
        ride.dropAndConfirm(90)
        assertEquals(0, ride.model.acceptedWindows)
    }

    @Test fun gapsDiscardCoverageButPreserveTheLearnedModel() {
        for (elapsed in listOf(0.0, -1.0, 3.0, 120.0, Double.NaN)) {
            val ride = Replay()
            ride.anchor()
            ride.completeInterval(90)
            val learned = ride.model
            ride.tick(90, elapsed = elapsed, distance = 100.0)
            ride.completeInterval(85)
            assertEquals(learned, ride.model)
        }
    }

    @Test fun confirmationDelayDoesNotMoveTheTrainingBoundary() {
        val ride = Replay()
        ride.tick(100, elapsed = 0.0)
        ride.tick(95)
        repeat(30) { ride.tick(95, distance = 0.01) }
        repeat(70) { ride.tick(95, distance = 0.01) }
        ride.tick(90)
        repeat(30) { ride.tick(90, distance = 0.01) }
        assertEquals(5.0, ride.model.learnedPercentPerKm!!, 0.00001)
    }

    @Test fun loadedOrUnstableObservationsCannotConfirmALearningBoundary() {
        val ride = Replay()
        ride.anchor()
        repeat(100) { ride.tick(95, distance = 0.01) }
        ride.tick(90, reliable = false)
        repeat(120) { ride.tick(90, reliable = false) }
        assertEquals(0, ride.model.acceptedWindows)
        repeat(30) { ride.tick(90, reliable = true) }
        assertEquals(1, ride.model.acceptedWindows)
    }

    @Test fun stationaryDropsAndIncompleteBaseCoverageDoNotTrainACorrection() {
        val stationary = Replay()
        stationary.anchor()
        stationary.dropAndConfirm(90)
        assertEquals(0, stationary.model.acceptedWindows)
        val mixed = Replay()
        mixed.anchor()
        mixed.baseRate = null
        repeat(50) { mixed.tick(95, distance = 0.01) }
        mixed.baseRate = 2.5
        repeat(50) { mixed.tick(95, distance = 0.01) }
        mixed.dropAndConfirm(90)
        assertEquals(1, mixed.model.acceptedWindows)
        assertEquals(1.0, mixed.model.correctionFactor, 0.0)
        assertNull(mixed.model.lastPredictionErrorPercent)
    }

    @Test fun subFivePointStepsAccumulateAndBoardsRemainIndependent() {
        val first = Replay()
        val second = Replay()
        first.anchor()
        repeat(50) { first.tick(95, distance = 0.01) }
        first.dropAndConfirm(92)
        assertEquals(0, first.model.acceptedWindows)
        repeat(50) { first.tick(92, distance = 0.01) }
        first.dropAndConfirm(90)
        assertEquals(1, first.model.acceptedWindows)
        assertEquals(BatteryLearningModel(), second.model)
    }

    @Test fun corruptOrUnknownPersistedModelsUseSafeDefaults() {
        assertEquals(BatteryLearningModel(), BatteryLearningModel.fromJson(JSONObject("{\"version\":99}")))
        val corrupt = BatteryLearningModel.fromJson(JSONObject()
            .put("version", 1).put("correction_factor", -1)
            .put("learned_percent_per_km", -1).put("accepted_windows", -1))
        assertEquals(BatteryLearningModel(), corrupt)
    }

    private class Replay(
        var baseRate: Double? = 2.5,
        var model: BatteryLearningModel = BatteryLearningModel(),
    ) {
        private val tracker = BatteryLearningTracker()
        private var reported = 100
        fun tick(
            percent: Int,
            elapsed: Double = 1.0,
            distance: Double = 0.0,
            reliable: Boolean = true,
        ) {
            reported = percent
            model = tracker.observe(model, percent, elapsed, distance, baseRate, reliable)
        }
        fun anchor() { tick(100, elapsed = 0.0); dropAndConfirm(95) }
        fun dropAndConfirm(percent: Int) { tick(percent); repeat(30) { tick(percent) } }
        fun completeInterval(percent: Int, distancePerTick: Double = 0.01) {
            repeat(100) { tick(reported, distance = distancePerTick) }
            dropAndConfirm(percent)
        }
    }
}

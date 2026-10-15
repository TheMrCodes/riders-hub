package at.themrcodes.ridershub.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

class PhysicsPowerModelTest {
    private val rolling = 0.25
    private val aero = 0.002
    private val mass = 6_000.0
    private val idle = 400.0
    private val eta = 0.5
    private val resistance = 0.003

    /**
     * Synthetic 10 Hz ride: a fixed load lag, rectified load reporting (charge current hidden),
     * and a pack voltage that still sags/rises with the true signed current.
     */
    private fun ride(
        processor: PhysicsFrameProcessor,
        model: BoardPhysicsModel,
        seconds: Int,
        startMs: Long = 1_000_000L,
        speedKmh: (Double) -> Double,
        grade: (Double) -> Double = { 0.0 },
    ): WindowMotion {
        val n = seconds * 10
        val speeds = DoubleArray(n) { speedKmh(it / 10.0) }
        val trueCurrent = DoubleArray(n) { i ->
            val v = speeds[i] / 3.6
            val a = if (i == 0) 0.0 else (speeds[i] - speeds[i - 1]) / 3.6 / 0.1
            val w = v * (rolling + aero * v * v + a + 9.81 * grade(i / 10.0))
            val power = (if (w > 0) mass * w else eta * mass * w) + idle
            // Electrical power is exactly V I with V = OCV - R I.
            (44.0 - kotlin.math.sqrt(44.0 * 44.0 - 4.0 * resistance * power)) / (2.0 * resistance)
        }
        var motion = WindowMotion.ZERO
        for (i in 0 until n) {
            val delayed = trueCurrent[max(0, i - PhysicsPowerConstants.LOAD_LAG_FRAMES)]
            val voltage = 44.0 - resistance * trueCurrent[i]
            motion += processor.observe(model, startMs + i * 100L, speeds[i], voltage, max(0.0, delayed))
        }
        return motion
    }

    /** Smooth throttle: cosine ramps up, a speed oscillation, a cosine stop and a pause. */
    private fun stopGo(t: Double): Double {
        val phase = t % 40.0
        return when {
            phase < 8 -> 15.0 * (1 - kotlin.math.cos(PI * phase / 8.0))
            phase < 25 -> 30.0 - 8.0 * sin(2 * PI * (phase - 8.0) / 17.0)
            phase < 31 -> 15.0 * (1 + kotlin.math.cos(PI * (phase - 25.0) / 6.0))
            else -> 0.0
        }
    }

    @Test fun recoversScaleFreeShapeAndRecoveryFromRectifiedLoad() {
        val model = BoardPhysicsModel()
        val processor = PhysicsFrameProcessor()
        ride(processor, model, 1_200, speedKmh = ::stopGo)
        model.completeTrip("trip-1")
        // A second trip classifies braking with the fitted shape and learns recovery.
        ride(PhysicsFrameProcessor(), model, 1_200, startMs = 9_000_000L, speedKmh = ::stopGo)
        model.completeTrip("trip-2")
        val shape = assertNotNull(model.shape()).let { model.shape()!! }
        assertEquals(rolling, shape.rollingMps2, 0.03)
        assertEquals(aero, shape.aeroPerM, 0.0006)
        // Rectified braking frames hide part of P0, so idle is only bounded, not recovered.
        assertTrue(shape.idleWPerKg in 0.0..idle / mass * 1.5)
        assertEquals(resistance, requireNotNull(model.resistanceVoltsPerRaw), 0.0005)
        assertEquals(eta, requireNotNull(shape.recoveryFraction), 0.12)
        assertTrue(requireNotNull(shape.rSquared) > 0.8)
    }

    @Test fun stopGoBrakingWorkMatchesKineticEnergyOnFlatGround() {
        val model = BoardPhysicsModel()
        val motion = ride(PhysicsFrameProcessor(), model, 400, speedKmh = { t ->
            val phase = t % 40.0
            when { phase < 10 -> 3.0 * phase; phase < 20 -> 30.0; phase < 25 -> 30.0 * (25 - phase) / 5.0; else -> 0.0 }
        })
        val v = 30.0 / 3.6
        // Ten full stops, each braking roughly 0.5 v^2 less what drag already removed.
        val perStop = motion.brakingStopGoJPerKg / 10.0
        assertTrue("per-stop braking $perStop", perStop in 0.6 * 0.5 * v * v..1.0 * 0.5 * v * v)
        assertEquals(0.0, motion.brakingDescentJPerKg, 1e-9)
        // The first aligned sample starts after the smoother warms up, mid-ramp.
        assertEquals(0.0, motion.netKineticJPerKg, 1.0)
    }

    @Test fun inferredGradeAccumulatesClimbAfterFit() {
        val model = BoardPhysicsModel()
        ride(PhysicsFrameProcessor(), model, 1_200, speedKmh = ::stopGo)
        model.completeTrip("trip-1")
        // 600 s at 20 km/h with 3% up then 3% down: about 50 m each way.
        val motion = ride(PhysicsFrameProcessor(), model, 600, startMs = 20_000_000L,
            speedKmh = { 20.0 }, grade = { t -> if (t < 300) 0.03 else -0.03 })
        assertTrue("climb ${motion.climbM}", motion.climbM in 30.0..70.0)
        assertTrue(motion.brakingDescentJPerKg >= 0.0)
    }

    @Test fun gapsResetAlignmentInsteadOfDifferentiatingAcrossThem() {
        val aligner = PhysicsFrameAligner()
        repeat(40) { assertTrue(aligner.observe(it * 100L, 20.0, 44.0, 10.0) == null || it >= 32) }
        assertNull(aligner.observe(40 * 100L + 5_000L, 0.0, 44.0, 0.0))
        assertTrue(aligner.discontinuity)
    }

    @Test fun modelAndMotionRoundTripExactly() {
        val model = BoardPhysicsModel()
        val motion = ride(PhysicsFrameProcessor(), model, 600, speedKmh = ::stopGo)
        model.completeTrip("trip")
        val restored = BoardPhysicsModel.fromJson(model.toJson())
        assertEquals(model.toJson().toString(), restored.toJson().toString())
        assertEquals(model.shape(), restored.shape())
        assertEquals(motion, WindowMotion.fromJson(motion.toJson()))
        assertNull(BoardPhysicsModel.fromJson(org.json.JSONObject("{\"schema_version\":9}")).shape())
    }

    @Test fun physicsRatesVaryBySpeedConserveDepletionAndAddOverhead() {
        val registry = BoardSpeedBucketStatsRegistry()
        val model = registry.physicsModel("board-a")
        ride(PhysicsFrameProcessor(), model, 1_200, speedKmh = ::stopGo)
        model.completeTrip("trip-1")
        ride(PhysicsFrameProcessor(), model, 1_200, startMs = 9_000_000L, speedKmh = ::stopGo)
        model.completeTrip("trip-2")
        // Before any depletion window the stated mass and pack energy already give rates.
        registry.recordDistance("board-a", mapOf(20 to 1.0))
        val prior = requireNotNull(registry.snapshot("board-a"))
        assertEquals(1.0, requireNotNull(prior.packEnergyFactor), 0.0)
        assertEquals(RiderBoardProfile.DEFAULT.nominalBatteryWh, requireNotNull(prior.effectiveBatteryWh), 0.0)
        val priorCruise = prior.buckets.associate { it.startKmh to requireNotNull(it.batteryPercentPer100Km) }
        val heavier = RiderBoardProfile(riderMassKg = 2 * RiderBoardProfile.DEFAULT.riderMassKg + RiderBoardProfile.DEFAULT.boardMassKg)
        val heavierCruise = requireNotNull(registry.snapshot("board-a", heavier)).buckets.single { it.startKmh == 20 }
        assertEquals(2.0 * priorCruise.getValue(20), requireNotNull(heavierCruise.batteryPercentPer100Km), 1e-9)

        val motion = WindowMotion(brakingStopGoJPerKg = 400.0, alignedSeconds = 600.0)
        repeat(4) { i ->
            // Unsorted insertion and inexact values: the round trip must still be bit-identical.
            val buckets = linkedMapOf(30 to 2.0 + 0.1 * i, 10 to 1.0 / 3.0, 20 to 2.0 - 1.0 / 3.0 - 0.1 * i)
            registry.recordDistance("board-a", buckets)
            registry.recordWindow("board-a", RangeDepletionWindow(
                id = "w$i", localBoardId = "board-a", startedAt = "2030-01-01T00:0$i:00Z",
                lastObservedAt = "2030-01-01T00:0$i:30Z", closedAt = "2030-01-01T00:0$i:30Z",
                startBatteryPercent = 90 - 5 * i, endBatteryPercent = 85 - 5 * i,
                minBatteryPercent = 85 - 5 * i, recordedDistanceKm = 4.0,
                speedBucketDistancesKm = buckets, motion = motion,
            ))
        }
        val snapshot = requireNotNull(registry.snapshot("board-a"))
        assertNotNull(snapshot.physicsShape)
        val cruise = snapshot.buckets.associate { it.startKmh to requireNotNull(it.batteryPercentPer100Km) / 100.0 }
        assertTrue(cruise.getValue(30) > cruise.getValue(20))
        assertTrue(snapshot.stopGoPercentPerKm > 0.0)
        // The learned factor moves the stated-pack prediction toward the observed 20 %:
        // factor = (observed + prior) / (stated prediction + prior), prior worth 10 %.
        val modeled = (0 until 4).sumOf { i ->
            mapOf(30 to 2.0 + 0.1 * i, 10 to 1.0 / 3.0, 20 to 2.0 - 1.0 / 3.0 - 0.1 * i).entries
                .sumOf { (b, km) -> km * cruise.getValue(b) } + 4.0 * snapshot.overheadPercentPerKm
        }
        val factor = requireNotNull(snapshot.packEnergyFactor)
        val stated = modeled / factor
        assertEquals((20.0 + 10.0) / (stated + 10.0), factor, 0.01)
        assertTrue(kotlin.math.abs(modeled - 20.0) < kotlin.math.abs(stated - 20.0))
        val effective = snapshot.consumptionRates()
        effective.forEach { (b, rate) -> assertEquals(cruise.getValue(b) + snapshot.overheadPercentPerKm, rate, 1e-12) }
        assertEquals(snapshot, BoardSpeedBucketStatsRegistry(registry.toJson().toString()).snapshot("board-a"))
    }

    @Test fun physicsSnapshotIsBitIdenticalAfterRoundTripWithUnsortedBuckets() {
        val registry = BoardSpeedBucketStatsRegistry()
        val model = registry.physicsModel("board-a")
        ride(PhysicsFrameProcessor(), model, 1_200, speedKmh = ::stopGo)
        model.completeTrip("trip-1")
        repeat(3) { i ->
            // One large bucket inserted before tiny ones: (big + tiny) + tiny rounds differently
            // from tiny + tiny + big, so any unsorted summation would change the snapshot.
            val buckets = linkedMapOf<Int, Double>().apply {
                put(40, 3.0 + i)
                for (b in 35 downTo 5 step 5) put(b, 3e-16)
            }
            registry.recordDistance("board-a", buckets)
            registry.recordWindow("board-a", RangeDepletionWindow(
                id = "u$i", localBoardId = "board-a", startedAt = "2030-01-02T00:0$i:00Z",
                lastObservedAt = "2030-01-02T00:0$i:30Z", closedAt = "2030-01-02T00:0$i:30Z",
                startBatteryPercent = 90 - 10 * i, endBatteryPercent = 80 - 10 * i,
                minBatteryPercent = 80 - 10 * i, recordedDistanceKm = buckets.values.sum(),
                speedBucketDistancesKm = buckets,
                motion = WindowMotion(brakingStopGoJPerKg = 123.456 + i, netKineticJPerKg = -0.1, alignedSeconds = 300.0),
            ))
        }
        val snapshot = requireNotNull(registry.snapshot("board-a"))
        assertNotNull(snapshot.physicsShape)
        assertEquals(snapshot, BoardSpeedBucketStatsRegistry(registry.toJson().toString()).snapshot("board-a"))
    }
}

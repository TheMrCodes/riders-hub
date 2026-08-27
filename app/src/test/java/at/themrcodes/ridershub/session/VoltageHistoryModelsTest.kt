package at.themrcodes.ridershub.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoltageHistoryModelsTest {
    @Test
    fun samplesAtTenSecondCadenceAndRecoversFromClockReset() {
        assertTrue(isVoltageSampleDue(1_000L, null))
        assertFalse(isVoltageSampleDue(10_999L, 1_000L))
        assertTrue(isVoltageSampleDue(11_000L, 1_000L))
        assertTrue(isVoltageSampleDue(500L, 1_000L))
    }

    @Test
    fun aggregatesVoltageSpeedAndUnknownLoadStatistics() {
        val first = sample(
            observedAt = "2030-01-01T10:00:00Z",
            voltage = 41.0,
            speed = 12.0,
            loadRaw = 100,
        )
        val second = sample(
            observedAt = "2030-01-01T10:00:10Z",
            voltage = 40.0,
            speed = 14.0,
            loadRaw = 200,
        )
        val bins = addVoltageCorrelationSample(
            addVoltageCorrelationSample(emptyMap(), first),
            second,
        )
        val bin = bins.values.single()

        assertEquals(2L, bin.sampleCount)
        assertEquals(40.5, bin.meanPackVoltageV, 0.0001)
        assertEquals(13.0, bin.meanSpeedKmh, 0.0001)
        assertEquals(150.0, bin.meanLoadRaw, 0.0001)
        assertEquals(41.0 * 12.0 + 40.0 * 14.0, bin.packVoltageSpeedProductSum, 0.0001)
        assertEquals(41.0 * 100.0 + 40.0 * 200.0, bin.packVoltageLoadRawProductSum, 0.0001)
    }

    @Test
    fun restingAndMovingReadingsRemainSeparate() {
        val resting = sample(speed = 0.0)
        val moving = sample(speed = 2.0)
        val bins = addVoltageCorrelationSample(
            addVoltageCorrelationSample(emptyMap(), resting),
            moving,
        )

        assertEquals(2, bins.size)
        assertTrue(bins.keys.any { it.operatingState == VoltageOperatingState.RESTING })
        assertTrue(bins.keys.any { it.operatingState == VoltageOperatingState.MOVING })
    }

    @Test
    fun activeRideAggregateRoundTripsThroughJson() {
        val original = VoltageCorrelationBin.from(sample())
            .add(sample(observedAt = "2030-01-01T10:00:10Z", voltage = 40.5))

        val restored = voltageCorrelationBinFromJson(JSONObject(original.toJson().toString()))

        assertEquals(original, restored)
    }

    @Test
    fun rejectsImplausibleVoltageBeforeItReachesHistory() {
        val invalid = sample(voltage = 0.0)

        assertTrue(addVoltageCorrelationSample(emptyMap(), invalid).isEmpty())
    }

    private fun sample(
        observedAt: String = "2030-01-01T10:00:00Z",
        voltage: Double = 41.0,
        speed: Double = 12.0,
        loadRaw: Int = 100,
    ) = VoltageCorrelationSample(
        observedAt = observedAt,
        batteryPercent = 80,
        packVoltageV = voltage,
        speedKmh = speed,
        loadRaw = loadRaw,
        odometerKm = 100.0,
        rideDistanceKm = 1.0,
    )
}

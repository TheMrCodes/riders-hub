package at.themrcodes.ridershub.wear.shared

import org.junit.Assert.*
import org.junit.Test

class WearRaceContractTest {
    @Test fun modeMigrationAndCyclePreserveExistingPreference() {
        assertEquals(WearDashboardMode.AMBIENT, WearDashboardMode.restore(null, false))
        assertEquals(WearDashboardMode.LIVE, WearDashboardMode.restore(null, true))
        assertEquals(WearDashboardMode.RACE, WearDashboardMode.restore("RACE", false))
        assertEquals(WearDashboardMode.LIVE, WearDashboardMode.restore("unknown", true))
        var mode = WearDashboardMode.AMBIENT
        for (expected in listOf(WearDashboardMode.LIVE, WearDashboardMode.RACE, WearDashboardMode.AMBIENT)) {
            mode = mode.next()
            assertEquals(expected, mode)
            assertEquals(mode, WearDisplayModeState.decode(WearDisplayModeState(mode).encode()).mode)
        }
    }

    @Test fun signedNullableSamplesRoundTripWithoutClampingToDial() {
        for (power in listOf(null, -900.0, -500.0, 0.0, 2000.0, 3100.0)) {
            val sample = WearRaceSample(1000, 24.5, power, 15)
            assertEquals(sample, WearRaceSample.decode(sample.encode()))
        }
        val unavailable = WearRaceSample(0, null, null, 50)
        assertEquals(unavailable, WearRaceSample.decode(unavailable.encode()))
    }

    @Test fun malformedAndInvalidSamplesAreRejected() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -50_001.0, 50_001.0)) {
            assertThrows(IllegalArgumentException::class.java) { WearRaceSample(0, 1.0, value, 20) }
        }
        for (warning in listOf(0, 16, 55)) {
            assertThrows(IllegalArgumentException::class.java) { WearRaceSample(0, 1.0, null, warning) }
        }
        assertThrows(IllegalArgumentException::class.java) { WearRaceSample(-1, null, null, 20) }
        assertThrows(IllegalArgumentException::class.java) { WearRaceSample(0, 201.0, null, 20) }
        val bytes = WearRaceSample(0, null, null, 20).encode()
        assertThrows(Exception::class.java) { WearRaceSample.decode(bytes.copyOf(7)) }
        assertThrows(IllegalArgumentException::class.java) { WearRaceSample.decode(bytes + byteArrayOf(0)) }
        bytes[4] = 2
        assertThrows(IllegalArgumentException::class.java) { WearRaceSample.decode(bytes) }
        assertThrows(IllegalArgumentException::class.java) { WearDisplayModeState.decode(byteArrayOf(1, 9)) }
    }

    @Test fun sourceFreshnessExpiresButToleratesClockSkew() {
        val sample = WearRaceSample(1000, 1.0, 2.0, 20)
        assertTrue(sample.isFresh(4000))
        assertFalse(sample.isFresh(4001))
        assertTrue(sample.isFresh(999))
    }

    @Test fun transportDropsIntermediateFramesAndDoesNotQueueCatchUp() {
        val cadence = WearRaceCadence()
        for (time in 0L..2100L step 50) {
            assertEquals(time == 0L || time == 2000L, cadence.durableDue(time))
            assertFalse(cadence.fastDue(WearDashboardMode.LIVE, time))
            assertFalse(cadence.fastDue(WearDashboardMode.AMBIENT, time))
            assertEquals(time % 250 == 0L, cadence.fastDue(WearDashboardMode.RACE, time))
        }
        assertTrue(cadence.fastDue(WearDashboardMode.RACE, 9000))
        assertFalse(cadence.fastDue(WearDashboardMode.RACE, 9001))
    }

    @Test fun briefRecoveryAndItsEndBypassTheNormalCadence() {
        val cadence = WearRaceCadence()
        val received = mutableListOf<WearRaceSample>()
        for ((time, power) in listOf(0L to 1000.0, 50L to 800.0, 100L to -192.0,
            150L to -200.0, 200L to 0.0, 250L to 400.0, 450L to 500.0)) {
            if (cadence.fastDue(WearDashboardMode.RACE, time, power)) {
                received += WearRaceSample.decode(WearRaceSample(time, 18.0, power, 20).encode())
            }
        }
        assertEquals(listOf(0L, 100L, 200L, 450L), received.map { it.updatedAtEpochMs })
        assertEquals(listOf(1000.0, -192.0, 0.0, 500.0), received.map { it.estimatedPowerWatts })
    }

    @Test fun recoveryTransitionsRespectModeAndRoundedZeroAndClearOnUnavailable() {
        val cadence = WearRaceCadence()
        assertTrue(cadence.fastDue(WearDashboardMode.RACE, 0, 1.0))
        assertFalse(cadence.fastDue(WearDashboardMode.RACE, 20, -.1))
        assertFalse(cadence.fastDue(WearDashboardMode.LIVE, 30, -192.0))
        assertTrue(cadence.fastDue(WearDashboardMode.RACE, 40, -192.0))
        assertTrue(cadence.fastDue(WearDashboardMode.RACE, 50, null))
        assertFalse(cadence.fastDue(WearDashboardMode.RACE, 60, 0.0))
    }
}

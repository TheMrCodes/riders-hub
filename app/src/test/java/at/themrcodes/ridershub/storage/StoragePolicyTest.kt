package at.themrcodes.ridershub.storage

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class StoragePolicyTest {
    private val start = Instant.parse("2030-01-01T09:00:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")
    private fun off() = StorageState(boardId = "synthetic-board", inactivitySince = start - STORAGE_INACTIVITY_MS)
    private fun enabled() = StoragePolicy.enable(off(), start)
    private fun observe(state: StorageState, day: Int, percent: Int) =
        StoragePolicy.observe(state, StorageObservation(start + day * STORAGE_DAY_MS, percent), 20)
    private fun calibrated(): StorageState = observe(observe(observe(enabled(), 0, 60), 7, 55), 14, 50)

    @Test fun offerStartsAfterExactlyFourteenDaysAndWaitsForAnAnswer() {
        val state = StorageState(boardId = "synthetic-board", inactivitySince = start)
        assertFalse(state.offerDue(start + STORAGE_INACTIVITY_MS - 1))
        assertEquals(StorageReminder.OFFER, state.reminder(start + STORAGE_INACTIVITY_MS))
        assertEquals(StorageReminder.OFFER, state.reminder(start + 50 * STORAGE_DAY_MS))
        assertNull(StoragePolicy.decline(state).reminder(start + 50 * STORAGE_DAY_MS))
    }

    @Test fun enableRequiresNewObservationAndUnknownBoardsCannotEnable() {
        assertEquals(StorageReminder.BASELINE, enabled().reminder(start))
        assertNull(enabled().latest)
        assertFalse(StoragePolicy.enable(StorageState(), start).enabled)
        assertEquals(enabled(), StoragePolicy.observe(enabled(), StorageObservation(start - 1, 60), 20))
    }

    @Test fun samplesAreWeeklyAndRepeatedFramesCannotCompleteCalibration() {
        val first = observe(enabled(), 0, 60)
        var state = first
        repeat(100) { state = StoragePolicy.observe(state, StorageObservation(start + it + 1, 60), 20) }
        assertEquals(first, state)
        assertNull(state.reminder(start + STORAGE_WEEK_MS - 1))
        assertEquals(StorageReminder.SAMPLE, state.reminder(start + STORAGE_WEEK_MS))
        val second = observe(state, 7, 55)
        assertNull(second.lossPerDay)
        assertEquals(2, second.observations.size)
    }

    @Test fun fitsThreeObservationsAndSchedulesHalfRemainingLifetime() {
        val state = calibrated()
        assertEquals(5.0 / 7, state.lossPerDay!!, 0.00001)
        assertEquals(start + 84 * STORAGE_DAY_MS, state.estimatedZeroAt!!)
        assertEquals(start + 49 * STORAGE_DAY_MS, state.chargeDueAt!!)
        assertNull(state.reminder(start + 48 * STORAGE_DAY_MS))
        assertEquals(StorageReminder.CHARGE, state.reminder(start + 49 * STORAGE_DAY_MS))
        assertEquals(StorageReminder.CHARGE, state.reminder(start + 70 * STORAGE_DAY_MS))
    }

    @Test fun actualElapsedTimeIsUsedForLateSamples() {
        val state = observe(observe(observe(enabled(), 0, 60), 14, 50), 28, 40)
        assertEquals(5.0 / 7, state.lossPerDay!!, 0.00001)
        assertEquals(start + 56 * STORAGE_DAY_MS, state.chargeDueAt!!)
    }

    @Test fun flatRisingOrUnmeasurableReadingsContinueWeeklySampling() {
        listOf(listOf(60, 60, 60), listOf(60, 55, 60), listOf(60, 59, 58)).forEach { values ->
            val state = observe(observe(observe(enabled(), 0, values[0]), 7, values[1]), 14, values[2])
            assertNull(state.lossPerDay)
            assertNull(state.chargeDueAt)
            assertEquals(StorageReminder.SAMPLE, state.reminder(start + 21 * STORAGE_DAY_MS))
        }
    }

    @Test fun additionalSamplingCanRecoverFromAnInitiallyFlatBattery() {
        var state = observe(observe(observe(enabled(), 0, 60), 7, 60), 14, 60)
        state = observe(state, 21, 55)
        assertNotNull(state.lossPerDay)
    }

    @Test fun lowStartingBatteryRequestsChargingDuringCalibration() {
        val state = observe(enabled(), 0, 15)
        assertEquals(StorageReminder.CHARGE, state.reminder(start))
        assertNull(state.lossPerDay)
    }

    @Test fun lowReadingBetweenWeeklySamplesBringsDeadlineForward() {
        val state = observe(calibrated(), 15, 15)
        assertEquals(start + 15 * STORAGE_DAY_MS, state.chargeDueAt!!)
    }

    @Test fun unchargedCheckInNeverPostponesCharging() {
        val original = calibrated()
        val unchanged = observe(original, 49, 50)
        assertEquals(original.chargeDueAt, unchanged.chargeDueAt)
        assertEquals(StorageReminder.CHARGE, unchanged.reminder(start + 49 * STORAGE_DAY_MS))
        val rebound = observe(original, 49, 55)
        assertEquals(original.chargeDueAt, rebound.chargeDueAt)
    }

    @Test fun confirmedChargeKeepsLearnedLossAndStartsANewSegment() {
        val original = calibrated()
        val charged = observe(original, 49, 60)
        assertEquals(1, charged.observations.size)
        assertEquals(original.lossPerDay, charged.lossPerDay)
        assertEquals(start + 91 * STORAGE_DAY_MS, charged.chargeDueAt!!)
        assertNull(charged.reminder(start + 49 * STORAGE_DAY_MS))
        assertEquals(start + 49 * STORAGE_DAY_MS, charged.cycleStartedAt!!)
    }

    @Test fun partialChargeThatLeavesBatteryLowDoesNotClearReminder() {
        val low = observe(enabled(), 0, 5)
        val charged = observe(low, 1, 15)
        assertEquals(StorageReminder.CHARGE, charged.reminder(start + STORAGE_DAY_MS))
    }

    @Test fun chargeDuringCalibrationRestartsThreePointCollection() {
        val state = observe(observe(observe(enabled(), 0, 60), 7, 55), 8, 80)
        assertEquals(1, state.observations.size)
        assertNull(state.lossPerDay)
        assertEquals(start + 15 * STORAGE_DAY_MS, state.nextSampleAt!!)
    }

    @Test fun onlyRidesStrictlyOverPointOneKmEndStorageAndRearmOffer() {
        val state = calibrated()
        listOf(0.0, 0.099, 0.1, Double.NaN).forEach { assertEquals(state, StoragePolicy.ride(state, it, start)) }
        val ridden = StoragePolicy.ride(state, 0.1001, start)
        assertFalse(ridden.enabled)
        assertNull(ridden.lossPerDay)
        assertTrue(ridden.offerDue(start + STORAGE_INACTIVITY_MS))
        assertFalse(StoragePolicy.ride(StoragePolicy.decline(off()), 1.0, start).declined)
    }

    @Test fun tenOClockUsesLocalCalendarAcrossSpringAndAutumnDst() {
        val zone = ZoneId.of("Europe/Vienna")
        listOf("2030-03-30T09:01:00Z" to "2030-03-31T08:00:00Z", "2030-10-26T08:01:00Z" to "2030-10-27T09:00:00Z").forEach { (now, expected) ->
            assertEquals(Instant.parse(expected).toEpochMilli(), storageAlarmAt(off(), Instant.parse(now).toEpochMilli(), zone)!!)
        }
    }

    @Test fun oneNotificationPerLocalDayAndNoNotificationsBeforeTen() {
        val ten = start + 3_600_000
        assertFalse(storageCanNotify(off(), ten - 1, utc))
        assertTrue(storageCanNotify(off(), ten, utc))
        val posted = off().copy(lastNotificationDate = "2030-01-01")
        assertFalse(storageCanNotify(posted, ten, utc))
        assertTrue(storageCanNotify(posted, ten + STORAGE_DAY_MS, utc))
        assertEquals(ten + STORAGE_DAY_MS, storageAlarmAt(posted, ten, utc)!!)
        assertNull(storageAlarmAt(StoragePolicy.decline(off()), ten, utc))
    }

    @Test fun schedulerWakesWeeklyBeforeChargingThenDailyWhenOverdue() {
        val state = calibrated()
        val now = start + 14 * STORAGE_DAY_MS
        assertEquals(start + 21 * STORAGE_DAY_MS + 3_600_000, storageAlarmAt(state, now, utc)!!)
        val overdue = state.copy(nextEvaluationAt = start + 56 * STORAGE_DAY_MS, lastNotificationDate = "2030-02-19")
        assertEquals(start + 50 * STORAGE_DAY_MS + 3_600_000, storageAlarmAt(overdue, start + 49 * STORAGE_DAY_MS + 3_600_000, utc)!!)
    }

    @Test fun stateRoundTripPreservesDeadlineAndNotificationDeduplication() {
        val state = calibrated().copy(lastNotificationDate = "2030-01-15")
        assertEquals(state, decodeStorageState(encodeStorageState(state)))
        assertEquals(StorageState(), decodeStorageState("invalid"))
    }

    @Test fun cardIsCompletelyAbsentOutsideStorageAndCoversEveryStage() {
        assertNull(storageProgress(off(), start, utc))
        assertNull(storageProgress(StoragePolicy.decline(off()), start, utc))
        assertEquals("SETUP", storageProgress(enabled(), start, utc)!!.stage)
        assertEquals("LEARNING", storageProgress(observe(enabled(), 0, 60), start, utc)!!.stage)
        assertEquals("MONITORING", storageProgress(calibrated(), start + 14 * STORAGE_DAY_MS, utc)!!.stage)
        assertEquals("CHARGE DUE", storageProgress(calibrated(), start + 49 * STORAGE_DAY_MS, utc)!!.stage)
    }

    @Test fun stableReadingNeedsThirtyContinuousStationarySeconds() {
        val window = StorageReadingWindow()
        for (second in 0..29) assertFalse(window.accept(60, 0.0, true, second * 1_000L))
        assertTrue(window.accept(60, 0.0, true, 30_000))
        assertFalse(window.accept(60, 2.0, true, 31_000))
        assertFalse(window.accept(60, 0.0, true, 32_000))
        assertFalse(window.accept(60, 0.0, true, 70_000))
        assertFalse(window.accept(70, 0.0, true, 71_000))
        assertFalse(window.accept(70, 0.0, false, 72_000))
        assertFalse(window.accept(70, 0.0, true, 73_000))
    }

    @Test fun liveTravelIgnoresStationaryChecksAndDoesNotBridgeGaps() {
        val travel = StorageTravelWindow()
        for (second in 0..60) assertEquals(0.0, travel.observe("check", 0.0, true, second * 1_000L), 0.0)
        travel.observe("ride", 36.0, true, 100_000)
        assertEquals(0.0, travel.observe("ride", 36.0, true, 110_000), 0.0)
        assertEquals(0.0, travel.distanceKm, 0.0)
        for (second in 111..121) travel.observe("ride", 36.0, true, second * 1_000L)
        assertTrue(travel.distanceKm > 0.1)
        assertEquals(0.0, travel.observe("next-check", 0.0, true, 122_000), 0.0)
    }
}

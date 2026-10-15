package at.themrcodes.ridershub.storage

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import at.themrcodes.ridershub.protocol.TelemetryFrame
import at.themrcodes.ridershub.session.RideSummary
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class StorageIntegrationTest {
    private val app = RuntimeEnvironment.getApplication()
    private val preferences = app.getSharedPreferences("storage_mode", Context.MODE_PRIVATE)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val start = Instant.parse("2030-01-01T10:00:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")

    @Before fun setup() {
        preferences.edit().clear().commit()
        notifications.cancelAll()
        StorageNotifications.createChannel(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun seeded(enabled: Boolean = true): StorageModeStore {
        preferences.edit().putString("state", encodeStorageState(StorageState(
            boardId = "synthetic-board", inactivitySince = start - STORAGE_INACTIVITY_MS,
            requestedAt = start.takeIf { enabled },
        ))).commit()
        return StorageModeStore(app)
    }

    private fun ride() = RideSummary(
        id = "synthetic-ride", startedAt = Instant.ofEpochMilli(start).toString(), endedAt = null, lastFrameAt = null,
        distanceKm = 12.0, movingSeconds = 0.0, maxSpeedKmh = 0.0, boardBatteryStart = null, boardBatteryEnd = null,
        boardBatteryMin = null, packVoltageStart = null, packVoltageEnd = null, packVoltageMin = null, packVoltageMax = null,
        restingVoltageStart = null, restingVoltageEnd = null, odometerStartKm = null, odometerEndKm = null,
        frameCount = 0, boardFrameCount = 0, crcErrorCount = 0, segmentCount = 1, modes = emptySet(), logFile = "",
        active = true, localBoardId = "synthetic-board",
    )

    private fun sample(store: StorageModeStore, day: Int, percent: Int, speed: Double = 0.0, valid: Boolean = true) {
        for (second in 0..31) {
            val frame = TelemetryFrame(byteArrayOf(), 1, "Eco", percent, speed to speed, speed, 44.0, 0, 12.0, 100.0, 0, 0, valid)
            store.onTelemetry(ride(), frame, start + day * STORAGE_DAY_MS + second * 1_000, day * STORAGE_DAY_MS + second * 1_000L)
        }
    }

    @Test fun liveTelemetryCollectsWeeklyReadingsAndChargeClearsOnlyOnNewEvidence() {
        val store = seeded()
        sample(store, 0, 60)
        sample(store, 7, 55)
        sample(store, 14, 50)
        assertEquals(3, store.snapshot().observations.size)
        val due = store.snapshot().chargeDueAt!!
        assertEquals(StorageReminder.CHARGE, store.snapshot().reminder(due))
        sample(store, 49, 55) // One coarse step is insufficient evidence of charge.
        assertEquals(due, store.snapshot().chargeDueAt!!)
        sample(store, 50, 70)
        assertTrue(store.snapshot().chargeDueAt!! > due)
        assertEquals(1, store.snapshot().observations.size)
    }

    @Test fun badCrcMovingReadingsAndStaleTripCountersCannotCreateSamplesOrEndStorage() {
        val store = seeded()
        sample(store, 0, 60, valid = false)
        assertNull(store.snapshot().latest)
        sample(store, 1, 60, speed = 1.0)
        assertNull(store.snapshot().latest)
        sample(store, 2, 60)
        assertEquals(1, store.snapshot().observations.size)
        assertTrue(store.snapshot().enabled) // Retained remote trip of 12 km is not a new ride.
    }

    @Test fun aRealRideAutomaticallyDisablesStorageAndCancelsTheNotification() {
        val store = seeded()
        store.evaluate(start, utc)
        assertEquals(1, shadowOf(notifications).allNotifications.size)
        sample(store, 0, 60, speed = 36.0)
        assertFalse(store.snapshot().enabled)
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test fun notificationSurvivesStatePersistenceAndRepeatsOnTheNextDay() {
        val store = seeded(false)
        store.evaluate(start, utc)
        val first = shadowOf(notifications).allNotifications.single()
        assertEquals("Put your board in storage mode?", first.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(first.contentIntent.isImmutable)
        assertTrue(shadowOf(first.contentIntent).savedIntent.getBooleanExtra("open_storage", false))
        notifications.cancelAll()
        val restored = StorageModeStore(app)
        restored.evaluate(start + 1_000, utc)
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
        restored.evaluate(start + STORAGE_DAY_MS, utc)
        assertEquals(1, shadowOf(notifications).allNotifications.size)
        restored.answer(false, start + STORAGE_DAY_MS)
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
        restored.evaluate(start + 2 * STORAGE_DAY_MS, utc)
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test fun deniedPermissionsKeepTheReminderOutstanding() {
        val store = seeded()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        store.evaluate(start, utc)
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
        assertNull(store.snapshot().lastNotificationDate)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        store.evaluate(start, utc)
        assertEquals(1, shadowOf(notifications).allNotifications.size)
    }

    @Test fun boardSwitchDropsPreviousStorageStateAndNotification() {
        val store = seeded()
        store.evaluate(start, utc)
        store.selectBoard("another-synthetic-board", start)
        assertFalse(store.snapshot().enabled)
        assertNull(store.snapshot().latest)
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test fun schedulerReplacesItsAlarmWhenTimezoneChangesAndCancelsAfterDecline() {
        val previous = TimeZone.getDefault()
        try {
            val state = seeded(false).snapshot().copy(inactivitySince = start - STORAGE_INACTIVITY_MS - STORAGE_DAY_MS)
            val alarm = shadowOf(app.getSystemService(AlarmManager::class.java))
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            StorageReminderReceiver.schedule(app, state, start - 3_600_000)
            assertEquals(start, alarm.nextScheduledAlarm!!.triggerAtTime)
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Vienna"))
            StorageReminderReceiver.schedule(app, state, start - 3_600_000)
            assertEquals(start - 3_600_000, alarm.nextScheduledAlarm!!.triggerAtTime)
            StorageReminderReceiver.schedule(app, StoragePolicy.decline(state), start)
            assertNull(alarm.nextScheduledAlarm)
        } finally { TimeZone.setDefault(previous) }
    }
}

package at.themrcodes.ridershub.session

import android.app.Application
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class ArchiveReplayTest {
    private val context = RuntimeEnvironment.getApplication()
    private val preferences = context.getSharedPreferences("ride_store", Context.MODE_PRIVATE)
    private lateinit var store: RideStore

    @Before fun setup() {
        context.getExternalFilesDir("telemetry")!!.deleteRecursively()
        preferences.edit().clear()
            .putString(AnalyticsHistoryMigration.VERSION_KEY, AnalyticsHistoryMigration.VERSION)
            .putString(AnalyticsHistoryMigration.MODEL_VERSION_KEY, AnalyticsHistoryMigration.MODEL_VERSION)
            .commit()
        val constructor = RideStore::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        store = constructor.newInstance(context)
        awaitWorker()
    }

    @After fun cleanup() {
        worker().shutdownNow()
        worker().awaitTermination(10, TimeUnit.SECONDS)
    }

    @Test fun manualReplayBypassesCurrentMarkersAndUpdatesHistoryAndProfileTogether() {
        val archive = archiveRecoveryFixture(context.getExternalFilesDir("telemetry")!!, "synthetic-trip", true)
        val original = archive.storageFile.readBytes()
        store.replayCurrentArchive()
        store.replayCurrentArchive()
        awaitWorker()
        assertFalse(store.archiveReplayState().running)
        assertFalse(store.archiveReplayState().failed)
        assertEquals(1, JSONArray(preferences.getString("ride_history", "[]")).length())
        assertEquals(1, JSONObject(requireNotNull(preferences.getString(AnalyticsHistoryMigration.MANIFEST_KEY, "{}"))).getInt("sessions"))
        assertEquals(1, store.snapshot(null, null).recentTracks.size)
        assertNotNull(store.snapshot(null, null).speedBucketStatistics)
        assertTrue(File(context.filesDir, "analytics-migration/" + preferences.getString("analytics_history_backup", "missing")).exists())
        store.replayCurrentArchive()
        awaitWorker()
        assertFalse(store.archiveReplayState().failed)
        assertEquals(1, JSONArray(preferences.getString("ride_history", "[]")).length())
        assertArrayEquals(original, archive.storageFile.readBytes())
    }

    @Test fun corruptArchiveKeepsPreviouslySavedUiState() {
        val archive = archiveRecoveryFixture(context.getExternalFilesDir("telemetry")!!, "synthetic-trip", true)
        preferences.edit().putString("unrelated_setting", "synthetic-value").commit()
        val before = preferences.all.toMap()
        archive.storageFile.writeBytes(archive.storageFile.readBytes().also { it[0] = 0 })
        store.replayCurrentArchive()
        awaitWorker()
        assertTrue(store.archiveReplayState().failed)
        assertEquals(before, preferences.all.filterKeys { it != "analytics_history_migration_error" })
        assertFalse(preferences.contains("ride_history"))
        assertFalse(preferences.contains(AnalyticsHistoryMigration.MANIFEST_KEY))
    }

    @Test fun emptyArchiveDoesNotReplaceSavedUiState() {
        val before = preferences.all.toMap()
        store.replayCurrentArchive()
        awaitWorker()
        assertTrue(store.archiveReplayState().failed)
        assertEquals("No saved archives found on this phone.", store.archiveReplayState().message)
        assertEquals(before, preferences.all)
    }

    @Test fun recordingPreventsManualReplay() {
        store.openSegment("02:00:00:00:00:01", "Synthetic remote")
        val before = preferences.all.toMap()
        store.replayCurrentArchive()
        assertFalse(store.archiveReplayState().running)
        assertTrue(store.archiveReplayState().failed)
        assertEquals(before, preferences.all)
    }

    private fun worker(): ExecutorService = RideStore::class.java.getDeclaredField("archiveExecutor")
        .apply { isAccessible = true }.get(store) as ExecutorService

    private fun awaitWorker() { worker().submit {}.get(30, TimeUnit.SECONDS) }
}

package at.themrcodes.ridershub.api

import android.app.Application
import at.themrcodes.ridershub.log.TelemetryPartitionUpload
import at.themrcodes.ridershub.charger.ChargerFileUpload
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class ApiDatabaseTest {
    private val app = RuntimeEnvironment.getApplication()
    private var database = ApiDatabase(app)
    private val scopeA = deliveryScopeId("destination-one", "server-a")
    private val scopeB = deliveryScopeId("destination-one", "server-b")

    @After fun close() { database.close(); app.deleteDatabase("self_hosted_api.db") }

    @Test fun boardIdentitySurvivesReinstallationAndMatchesArchiveIdentity() {
        val key = "board_6d42c15bb166be5ddc684cb8"
        val expected = "00351e63-1177-3f8f-b968-bd16bc3beee0"
        val oldSource = database.sourceId()
        assertEquals(expected, database.exportId("board", key))
        database.close()
        app.deleteDatabase("self_hosted_api.db")
        database = ApiDatabase(app)
        assertNotEquals(oldSource, database.sourceId())
        assertEquals(expected, database.exportId("board", key))
        assertNotEquals(expected, database.exportId("board", "board_111111111111111111111111"))
    }

    @Test fun lifecycleJournalDeduplicatesAcrossAcknowledgementAndReopen() {
        val row = enqueue(scopeA, "started")
        val initial = database.pendingMetrics(scopeA).single().payload
        database.acknowledgeMetric(row)
        database.close()
        database = ApiDatabase(app)
        assertEquals(row, enqueue(scopeA, "started"))
        assertTrue(database.pendingMetrics(scopeA).isEmpty())
        assertEquals(1L, JSONObject(initial).getLong("sequence"))
        assertEquals(2L, database.nextSequence())
        assertTrue(database.pendingMetrics(scopeB).isEmpty())
        enqueue(scopeB, "started")
        assertEquals(1, database.pendingMetrics(scopeB).size)
    }

    @Test fun failedLifecycleTransactionDoesNotConsumeSequenceOrInsertPartialEvent() {
        assertThrows(IllegalStateException::class.java) {
            database.enqueueLifecycle(scopeA, "broken") { _, _ -> error("Synthetic failure") }
        }
        assertEquals(1L, database.nextSequence())
        assertTrue(database.pendingMetrics(scopeA).isEmpty())
    }

    @Test fun backlogCanBeDrainedInMultipleBatchesWithoutLosingRows() {
        repeat(47) { enqueue(scopeA, "event-$it") }
        var count = 0
        while (true) {
            val rows = database.pendingMetrics(scopeA)
            if (rows.isEmpty()) break
            rows.forEach { database.acknowledgeMetric(it.rowId); count++ }
        }
        assertEquals(47, count)
    }

    @Test fun consentAndArchiveAcknowledgementsAreIsolatedByUrlAndServer() {
        database.setAnalyticsApproved("destination-one", "server-a", true)
        assertFalse(database.analyticsApproved("destination-two", "server-a"))
        assertFalse(database.analyticsApproved("destination-one", "server-b"))
        database.setAnalyticsApproved("destination-one", "server-b", true)
        assertTrue(database.analyticsApproved("destination-one", "server-a"))
        database.acknowledgeArchive(scopeA, "source", upload())
        assertFalse(database.archiveNeedsUpload(scopeA, "source", upload()))
        assertTrue(database.archiveNeedsUpload(scopeB, "source", upload()))
        assertTrue(database.archiveNeedsUpload(scopeA, "source", upload(final = true)))
        database.acknowledgeArchive(scopeA, "source", upload(final = true))
        assertFalse(database.archiveNeedsUpload(scopeA, "source", upload(final = true)))
    }

    @Test fun conflictStaysBlockedUntilDeliberateResync() {
        database.recordArchiveFailure(scopeA, "source", upload(), "HTTP 409")
        assertFalse(database.archiveNeedsUpload(scopeA, "source", upload()))
        assertFalse(database.archiveNeedsUpload(scopeA, "source", upload(generation = 2)))
        database.clearArchiveAcknowledgements(scopeA)
        assertTrue(database.archiveNeedsUpload(scopeA, "source", upload()))
    }

    @Test fun chargerFileAcknowledgementIsSeparateAndConflictBlocksUntilResync() {
        val upload = ChargerFileUpload(File(app.cacheDir, "synthetic.coa"), "file-one", 3, "digest")
        assertTrue(database.chargerFileNeedsUpload(scopeA, "source", upload))
        database.acknowledgeChargerFile(scopeA, "source", upload)
        assertFalse(database.chargerFileNeedsUpload(scopeA, "source", upload))
        assertTrue(database.chargerFileNeedsUpload(scopeB, "source", upload))
        val second = upload.copy(fileId = "file-two")
        database.blockChargerFile(scopeA, "source", second, "HTTP 409")
        assertFalse(database.chargerFileNeedsUpload(scopeA, "source", second.copy(digestBase64 = "changed")))
        database.clearArchiveAcknowledgements(scopeA)
        assertTrue(database.chargerFileNeedsUpload(scopeA, "source", second))
    }

    @Test fun channelPausesAndRetryAfterSurviveDatabaseReopen() {
        database.pauseChannel(scopeA, "metrics")
        database.deferChannel(scopeA, "analytics", 1000)
        database.close()
        database = ApiDatabase(app)
        assertFalse(database.channelReady(scopeA, "metrics", 2000))
        assertFalse(database.channelReady(scopeA, "analytics", 999))
        assertTrue(database.channelReady(scopeA, "analytics", 1000))
        assertTrue(database.channelReady(scopeB, "metrics", 0))
        database.resumeUnauthorized(scopeA)
        assertTrue(database.channelReady(scopeA, "metrics", 0))
    }

    private fun enqueue(scope: String, event: String): Long = database.enqueueLifecycle(scope, event) { sequence, id ->
        JSONObject().put("kind", "ride.started").put("message_id", id).put("sequence", sequence)
            .put("occurred_at", "2030-01-01T00:00:00Z")
    }

    private fun upload(generation: Int = 1, final: Boolean = false) = TelemetryPartitionUpload(
        1, generation, final, File(app.cacheDir, "synthetic.rha"), "compressed", "logical-$generation")
}

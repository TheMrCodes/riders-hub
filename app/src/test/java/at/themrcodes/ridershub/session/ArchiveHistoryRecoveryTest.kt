package at.themrcodes.ridershub.session

import at.themrcodes.ridershub.log.TelemetryArchive
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class ArchiveHistoryRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun restoresAuthoritativeTripTotalsAndExtractableReferencesFromWorkingAndSealedArchives() {
        for (sealed in listOf(false, true)) {
            val root = temporary.newFolder()
            val reference = archiveRecoveryFixture(root, "synthetic-trip", sealed)
            val before = reference.storageFile.readBytes()
            val ride = ArchiveHistoryRecovery.recover(reference.archiveDirectory, temporary.newFolder()).single()
            assertEquals(reference.encoded, ride.logFile)
            assertEquals(1.25, ride.distanceKm, 0.0)
            assertEquals(12.0, ride.movingSeconds, 0.0)
            assertEquals(2, ride.boardFrameCount)
            assertEquals(50, ride.boardBatteryMin)
            assertEquals(42.0, ride.packVoltageMax!!, 0.0)
            assertEquals(setOf("ECO"), ride.modes)
            assertFalse(ride.active)
            assertEquals(pseudonymousBoardId("02:00:00:00:00:01"), ride.localBoardId)
            assertArrayEquals(before, reference.storageFile.readBytes())
            val extracted = File(root, "extracted.jsonl")
            assertTrue(TelemetryArchive().extractEntry(reference.archiveDirectory, reference.partitionSequence, reference.entryId, extracted))
            assertEquals("synthetic-trip", JSONObject(extracted.readLines().first()).getString("session_id"))
        }
    }

    @Test fun repeatedRecoveryPreservesExistingPresentationTotalsWithoutDuplicateTrips() {
        val root = temporary.newFolder()
        archiveRecoveryFixture(root, "synthetic-trip", false)
        val recovered = ArchiveHistoryRecovery.recover(File(root, "archive"), temporary.newFolder())
        val old = recovered.single().copy(distanceKm = 1.4, logFile = "old-recording.jsonl", localBoardId = null)
        val merged = ArchiveHistoryRecovery.merge(recovered, listOf(old))
        assertEquals(1, merged.size)
        assertEquals(1.4, merged.single().distanceKm, 0.0)
        assertEquals(recovered.single().logFile, merged.single().logFile)
        assertEquals(recovered.single().localBoardId, merged.single().localBoardId)
        assertEquals(merged, ArchiveHistoryRecovery.merge(recovered, merged))
    }

    @Test fun corruptPackagesFailWithoutReturningPartialHistoryOrRepairingSources() {
        val root = temporary.newFolder()
        val saved = archiveRecoveryFixture(root, "synthetic-trip", true)
        saved.storageFile.writeBytes(saved.storageFile.readBytes().also { it[0] = 0 })
        val before = saved.storageFile.readBytes()
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveHistoryRecovery.recover(saved.archiveDirectory, temporary.newFolder())
        }
        assertArrayEquals(before, saved.storageFile.readBytes())
    }

    /** Optional private audit, using ignored files supplied by the caller. */
    @Test fun recoverLocalBackupWhenRequested() {
        val path = System.getenv("RIDERS_ARCHIVE_RECOVERY_AUDIT")
        assumeTrue(path != null)
        val root = File(requireNotNull(path))
        val directory = File(root, "before/telemetry/archive")
        val recovered = ArchiveHistoryRecovery.recover(directory, temporary.newFolder())
        val history = ArchiveHistoryRecovery.merge(recovered, emptyList())
        val json = org.json.JSONArray(history.map { it.toJson() }).toString()
        val rebuilt = AnalyticsHistoryMigration.rebuild(directory, temporary.newFolder(), mapOf("ride_history" to json))
        assertEquals(recovered.size, rebuilt.report.getInt("sessions"))
        assertTrue(history.isNotEmpty())
        File(root, "recovered-history.json").writeText(json)
        File(root, "recovered-profile.json").writeText(JSONObject(rebuilt.replacement).toString())
    }
}

internal fun archiveRecoveryFixture(root: File, id: String, sealed: Boolean) = run {
    val start = Instant.parse("2030-01-01T10:00:00Z")
    val rows = mutableListOf<JSONObject>()
    fun row(type: String, second: Long) = JSONObject().put("seq", rows.size + 1)
        .put("type", type).put("wall_time", start.plusSeconds(second).toString())
    rows += row("session_start", 0).put("session_id", id).put("device_address", "02:00:00:00:00:01")
    repeat(3) { i ->
        rows += row("telemetry_frame", i.toLong()).put("crc_valid", i != 1)
            .put("board_battery_percent", if (i == 1) 1 else 50)
            .put("pack_voltage_v", if (i == 1) 60.0 else 42.0)
            .put("speed_kmh", 18.0).put("odometer_km", 100.0 + i * .005)
            .put("mode", if (i == 1) "SPORT" else "ECO")
    }
    rows += row("session_end", 3).put("session_id", id).put("distance_km", 1.25)
        .put("moving_seconds", 12.0).put("max_speed_kmh", 18.0).put("frame_count", 3)
        .put("crc_error_count", 1).put("segment_count", 1).put("board_battery_start", 50)
        .put("board_battery_end", 50).put("pack_voltage_start", 42.0).put("pack_voltage_end", 42.0)
    val source = File(root, "$id.jsonl").also { it.writeText(rows.joinToString("\n", postfix = "\n")) }
    TelemetryArchive(targetPartitionBytes = if (sealed) 200L else Long.MAX_VALUE).archiveFinalized(source)
}

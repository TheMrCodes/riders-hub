package at.themrcodes.ridershub.session

import at.themrcodes.ridershub.log.TelemetryArchive
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

class AnalyticsHistoryMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archive(
        root: File,
        id: String,
        address: String,
        start: Instant,
        percentages: List<Int>,
        seal: Boolean = false,
        odometers: List<Double>? = null,
    ) {
        require(odometers == null || odometers.size == percentages.size)
        val file = File(root, "$id.jsonl")
        val rows = mutableListOf<JSONObject>()
        fun row(type: String, time: Instant) = JSONObject().put("seq", rows.size + 1)
            .put("type", type).put("wall_time", time.toString()).put("session_id", id)
            .put("device_address", address)
        rows += row("session_start", start)
        percentages.forEachIndexed { index, p ->
            rows += row("telemetry_frame", start.plusSeconds(index.toLong()))
                .put("board_battery_percent", p).put("pack_voltage_v", 42.0)
                .put("speed_kmh", 18.0).put("odometer_km", odometers?.get(index) ?: 100.0 + index * .005)
                .put("crc_valid", true)
        }
        rows += row("session_end", start.plusSeconds(percentages.size.toLong()))
        file.writeText(rows.joinToString("\n", postfix = "\n"))
        TelemetryArchive(targetPartitionBytes = if (seal) 200L else 1000000L).archiveFinalized(file)
    }

    @Test fun migrationRunsForLegacyDataAndNeverRepeatsAfterVersionMarker() {
        assertFalse(AnalyticsHistoryMigration.required(emptyMap()))
        assertTrue(AnalyticsHistoryMigration.required(emptyMap(), rawArchiveAvailable = true))
        val old = mapOf("ride_history" to "[{}]")
        assertTrue(AnalyticsHistoryMigration.required(old))
        assertTrue(AnalyticsHistoryMigration.required(old +
            (AnalyticsHistoryMigration.VERSION_KEY to AnalyticsHistoryMigration.VERSION)))
        val current = old + mapOf(
            AnalyticsHistoryMigration.VERSION_KEY to AnalyticsHistoryMigration.VERSION,
            AnalyticsHistoryMigration.MODEL_VERSION_KEY to AnalyticsHistoryMigration.MODEL_VERSION,
        )
        assertFalse(AnalyticsHistoryMigration.required(current))
        assertFalse(AnalyticsHistoryMigration.required(current + (AnalyticsHistoryMigration.VERSION_KEY to "99")))
        assertTrue(AnalyticsHistoryMigration.required(current +
            (AnalyticsHistoryMigration.MODEL_VERSION_KEY to "older-model")))
    }

    @Test fun replayIsRepeatableSeparatesBoardsAndDoesNotTurnFivePointRisesIntoCharges() {
        val root = temporary.newFolder()
        val start = Instant.parse("2030-01-01T10:00:00Z")
        val samples = List(40) { 50 } + List(40) { 55 } + List(40) { 50 } + List(40) { 65 } + List(200) { 60 }
        archive(root, "first", "02:00:00:00:00:01", start, samples, true)
        archive(root, "second", "02:00:00:00:00:02", start, List(100) { 50 })
        val before = File(root, "archive").listFiles()!!.associate { it.name to it.readBytes().toList() }
        val result = AnalyticsHistoryMigration.rebuild(File(root, "archive"), temporary.newFolder(), emptyMap())
        val again = AnalyticsHistoryMigration.rebuild(File(root, "archive"), temporary.newFolder(), emptyMap())
        assertEquals(result.replacement, again.replacement)
        assertEquals(before, File(root, "archive").listFiles()!!.associate { it.name to it.readBytes().toList() })
        val boards = result.report.getJSONArray("boards")
        assertEquals(2, boards.length())
        assertEquals(1, boards.getJSONObject(0).getInt("observed_charges"))
        assertEquals(0, boards.getJSONObject(1).getInt("observed_charges"))
        val registry = BoardSpeedBucketStatsRegistry(result.replacement.getValue("board_speed_bucket_stats_v1"))
        assertEquals(.495, registry.snapshot(pseudonymousBoardId("02:00:00:00:00:02"))!!.measuredDistanceKm, 1e-9)
        val manifest = JSONObject(result.replacement.getValue(AnalyticsHistoryMigration.MANIFEST_KEY))
        assertEquals(AnalyticsHistoryMigration.VERSION.toInt(), manifest.getInt("projection_version"))
        assertEquals(AnalyticsHistoryMigration.MODEL_VERSION, manifest.getString("model_version"))
        assertEquals(2, manifest.getInt("sessions"))
        assertEquals(samples.size + 100, manifest.getInt("frames"))
        assertEquals(64, manifest.getString("source_sha256").length)
        assertTrue(JSONObject(result.replacement.getValue("battery_estimates_v1")).length() > 0)
        assertTrue(JSONObject(result.replacement.getValue("battery_learning_models_v1")).length() > 0)
        assertEquals(2, JSONObject(result.replacement.getValue("raw_load_zero_v1")).length())
        assertEquals(manifest.toString(), result.report.getJSONObject("replay").toString())
    }

    @Test fun replayIgnoresLegacyEstimatorStateAndStartsOnlyFromRawFrames() {
        val root = temporary.newFolder()
        archive(root, "session", "02:00:00:00:00:01", Instant.parse("2030-01-01T10:00:00Z"),
            List(40) { 60 } + List(40) { 55 })
        val empty = AnalyticsHistoryMigration.rebuild(
            File(root, "archive"), temporary.newFolder(), emptyMap(),
        )
        val poisonedLegacy = mapOf(
            "battery_estimates_v1" to JSONObject().put("legacy-board",
                BatteryEstimateState(1.0, 0).toJson()).toString(),
            "battery_learning_models_v1" to JSONObject().put("legacy-board",
                BatteryLearningModel(correctionFactor = 4.0, learnedPercentPerKm = 20.0,
                    acceptedWindows = 999).toJson()).toString(),
        )
        val replayed = AnalyticsHistoryMigration.rebuild(
            File(root, "archive"), temporary.newFolder(), poisonedLegacy,
        )
        assertEquals(empty.replacement, replayed.replacement)
        assertFalse(JSONObject(replayed.replacement.getValue("battery_estimates_v1")).has("legacy-board"))
        assertFalse(JSONObject(replayed.replacement.getValue("battery_learning_models_v1")).has("legacy-board"))
    }

    @Test fun confirmedChargeStartsAtFirstElevatedFrameAndKeepsConfirmationDistance() {
        val root = temporary.newFolder()
        val start = Instant.parse("2030-01-01T10:00:00Z")
        archive(root, "session", "02:00:00:00:00:01", start,
            List(10) { 40 } + List(40) { 55 },
            odometers = List(10) { 100.0 + it * .005 } + List(40) { 102.2 + it * .005 })

        val result = AnalyticsHistoryMigration.rebuild(
            File(root, "archive"), temporary.newFolder(), emptyMap(),
        )
        val active = org.json.JSONArray(result.replacement.getValue("active_charge_cycles_v1"))
            .getJSONObject(0)
        assertEquals("INFERRED_RECHARGE", active.getString("start_reason"))
        assertEquals(start.plusSeconds(10).toString(), active.getString("started_at"))
        assertEquals(0.195, active.getDouble("recorded_distance_km"), 1e-9)
        assertEquals(1, result.report.getJSONObject("stream").getInt("confirmed_charge_boundaries"))
        assertEquals(0, result.report.getJSONObject("stream").getInt("hard_odometer_gaps"))
        assertEquals(2.155, result.report.getJSONObject("stream").getDouble("charge_spanning_gap_km"), 1e-9)
        val board = pseudonymousBoardId("02:00:00:00:00:01")
        assertEquals(2.155, BoardSpeedBucketStatsRegistry(
            result.replacement.getValue("board_speed_bucket_stats_v1"),
        ).snapshot(board)!!.unattributedDistanceKm, 1e-9)
    }

    @Test fun twoKmRuleRetainsSmallerGapAndRejectsBoundaryAtThreshold() {
        val root = temporary.newFolder()
        archive(root, "session", "02:00:00:00:00:01", Instant.parse("2030-01-01T10:00:00Z"),
            listOf(50, 50, 50), odometers = listOf(100.0, 101.9, 103.9))

        val stream = AnalyticsHistoryMigration.rebuild(
            File(root, "archive"), temporary.newFolder(), emptyMap(),
        ).report.getJSONObject("stream")
        assertEquals(1, stream.getInt("hard_odometer_gaps"))
        assertEquals(1.9, stream.getDouble("retained_sub_2km_gap_km"), 1e-9)
    }

    @Test fun incompleteOrMissingArchivesFailWithoutEditingSources() {
        val root = temporary.newFolder()
        archive(root, "session", "02:00:00:00:00:01", Instant.parse("2030-01-01T10:00:00Z"), List(40) { 50 })
        val archive = File(root, "archive").listFiles()!!.single()
        archive.appendBytes(byteArrayOf(1, 2, 3))
        val bytes = archive.readBytes()
        assertThrows(IllegalArgumentException::class.java) {
            AnalyticsHistoryMigration.rebuild(File(root, "archive"), temporary.newFolder(), emptyMap())
        }
        assertArrayEquals(bytes, archive.readBytes())
        assertThrows(IllegalArgumentException::class.java) {
            AnalyticsHistoryMigration.rebuild(temporary.newFolder(), temporary.newFolder(), emptyMap())
        }
    }

    @Test fun missingRetainedTripBlocksReplacement() {
        val root = temporary.newFolder()
        archive(root, "session", "02:00:00:00:00:01", Instant.parse("2030-01-01T10:00:00Z"), List(40) { 50 })
        val missing = JSONObject().put("id", "missing").put("started_at", "2030-01-01T10:00:00Z").put("log_file", "")
        assertThrows(IllegalArgumentException::class.java) {
            AnalyticsHistoryMigration.rebuild(File(root, "archive"), temporary.newFolder(), mapOf("ride_history" to "[$missing]"))
        }
    }

    /** Optional local audit. Inputs/outputs remain outside tracked fixtures. */
    @Test fun replayLocalBackupWhenRequested() {
        val path = System.getenv("RIDERS_ANALYTICS_AUDIT")
        assumeTrue(path != null)
        val root = File(requireNotNull(path))
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val nodes = factory.newDocumentBuilder().parse(File(root, "before/ride_store.xml")).documentElement.childNodes
        val prefs = buildMap { repeat(nodes.length) { i ->
            val node = nodes.item(i)
            if (node.nodeName == "string") put(node.attributes.getNamedItem("name").nodeValue, node.textContent)
        } }
        // Personal profile values come from the local environment, never from tracked files.
        fun env(name: String) = System.getenv(name)?.toDoubleOrNull()
        val profile = RiderBoardProfile(
            riderMassKg = env("RIDERS_AUDIT_RIDER_KG") ?: RiderBoardProfile.DEFAULT_RIDER_MASS_KG,
            boardMassKgOverride = env("RIDERS_AUDIT_BOARD_KG"),
            batteryWhOverride = env("RIDERS_AUDIT_BATTERY_WH"),
        ).normalized()
        val result = AnalyticsHistoryMigration.rebuild(File(root, "before/telemetry/archive"), temporary.newFolder(), prefs, profile)
        val again = AnalyticsHistoryMigration.rebuild(File(root, "before/telemetry/archive"), temporary.newFolder(), prefs, profile)
        assertEquals(result.replacement, again.replacement)
        File(root, "rebuilt.json").writeText(JSONObject(result.replacement).toString())
        File(root, "comparison.json").writeText(result.report.toString(2))
    }
}

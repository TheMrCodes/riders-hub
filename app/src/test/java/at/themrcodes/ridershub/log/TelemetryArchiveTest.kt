package at.themrcodes.ridershub.log

import at.themrcodes.ridershub.session.BatteryFrameDiagnostics
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Comparator

class TelemetryArchiveTest {
    private val temporaryRoots = mutableListOf<File>()

    @Test
    fun uploadSnapshotUsesCommittedPrefixWithoutRepairingTheSourceTail() {
        val root = temporaryRoot()
        val archive = TelemetryArchive(targetPartitionBytes = 100 * 1024L)
        val saved = archive.archiveFinalized(telemetryFile(root, "synthetic-one"), "synthetic-one")
        saved.storageFile.appendBytes(byteArrayOf(0x52, 0x48, 0x45))
        val original = saved.storageFile.readBytes()
        val uploads = archive.prepareUploads(saved.archiveDirectory, File(root, "exports"))
        assertEquals(1, uploads.size)
        assertEquals(1, uploads.single().generation)
        assertArrayEquals(original, saved.storageFile.readBytes())
    }

    @Test
    fun corruptSealedPartitionIsReportedWhileOtherPartitionsRemainUploadable() {
        val root = temporaryRoot()
        val archive = TelemetryArchive(targetPartitionBytes = 200L)
        val first = archive.archiveFinalized(telemetryFile(root, "synthetic-one"), "synthetic-one")
        archive.archiveFinalized(telemetryFile(root, "synthetic-two"), "synthetic-two")
        RandomAccessFile(first.storageFile, "rw").use { it.seek(0); it.writeByte(0) }
        val invalid = mutableListOf<Int>()
        val uploads = archive.prepareUploads(first.archiveDirectory, File(root, "exports"), invalid::add)
        assertEquals(listOf(1), invalid)
        assertEquals(listOf(2), uploads.map { it.partitionSequence })
    }

    @Test
    fun localZipPreservesEveryPartitionAndCleansScratch() {
        val root = temporaryRoot()
        val archive = TelemetryArchive(targetPartitionBytes = 200L)
        val sealed = archive.archiveFinalized(telemetryFile(root, "synthetic-sealed"), "synthetic-sealed")
        val workingArchive = TelemetryArchive(targetPartitionBytes = 100 * 1024L)
        workingArchive.archiveFinalized(telemetryFile(root, "synthetic-working"), "synthetic-working")
        File(sealed.archiveDirectory, ".unrelated.tmp").writeText("Synthetic temporary data")
        val originals = sealed.archiveDirectory.listFiles()!!.filter { it.extension in setOf("rha", "rhp") }
            .associate { "archive/${it.name}" to it.readBytes() }
        assertTrue(originals.keys.any { it.endsWith(".rha") })
        assertTrue(originals.keys.any { it.endsWith(".rhp") })
        val scratch = File(root, "scratch")
        val output = java.io.ByteArrayOutputStream()
        assertEquals(originals.size, archive.exportZip(sealed.archiveDirectory, scratch, output))
        val entries = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(originals.keys, entries.keys)
        originals.forEach { (name, bytes) -> assertArrayEquals(bytes, entries[name]) }
        assertTrue(scratch.listFiles()!!.isEmpty())
        originals.forEach { (name, bytes) -> assertArrayEquals(bytes, File(sealed.archiveDirectory, name.substringAfter('/')).readBytes()) }
    }

    @Test
    fun localZipCleansSnapshotWhenDestinationFails() {
        val root = temporaryRoot()
        val archive = TelemetryArchive()
        val saved = archive.archiveFinalized(telemetryFile(root, "synthetic-failure"), "synthetic-failure")
        val scratch = File(root, "scratch")
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) { throw java.io.IOException("Synthetic full destination") }
        }
        assertTrue(runCatching { archive.exportZip(saved.archiveDirectory, scratch, output) }.isFailure)
        assertTrue(scratch.listFiles()!!.isEmpty())
        assertTrue(saved.storageFile.exists())
    }

    @Test
    fun emptyLocalArchiveDoesNotProduceASuccessfulBackup() {
        val root = temporaryRoot()
        val scratch = File(root, "scratch")
        assertTrue(runCatching {
            TelemetryArchive().exportZip(File(root, "missing"), scratch, java.io.ByteArrayOutputStream())
        }.isFailure)
        assertTrue(scratch.listFiles()!!.isEmpty())
    }

    @After
    fun cleanUp() {
        temporaryRoots.forEach { root ->
            if (root.exists()) {
                Files.walk(root.toPath()).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }

    @Test
    fun appendsOriginalJsonlToWorkingPartitionAndExtractsIt() {
        val root = temporaryRoot()
        val source = telemetryFile(root, "session-one")
        val original = source.readBytes()
        val archive = TelemetryArchive(targetPartitionBytes = 100 * 1024L)

        val result = archive.archiveFinalized(source, "session-one")

        assertFalse(source.exists())
        assertTrue(result.sourceDeleted)
        assertTrue(result.storageFile.isFile)
        assertEquals("rhp", result.storageFile.extension)
        assertEquals(42, result.recordCount)
        assertEquals(REMOTE_ONE, result.remoteAddress)

        val restored = File(root, "restored.jsonl")
        assertTrue(
            archive.extractEntry(
                result.archiveDirectory,
                result.partitionSequence,
                result.entryId,
                restored,
            ),
        )
        assertArrayEquals(original, restored.readBytes())
    }

    @Test
    fun sameRemoteAppendsWithoutRewritingExistingPartitionBytes() {
        val root = temporaryRoot()
        // This test exercises append integrity, so diagnostic payload growth must not
        // accidentally turn it into a partition-sealing test.
        val archive = TelemetryArchive(targetPartitionBytes = Long.MAX_VALUE)
        val first = archive.archiveFinalized(telemetryFile(root, "session-one"), "session-one")
        val originalPrefix = first.storageFile.readBytes()
        val second = archive.archiveFinalized(telemetryFile(root, "session-two"), "session-two")

        assertEquals(first.storageFile, second.storageFile)
        val appended = second.storageFile.readBytes()
        assertArrayEquals(originalPrefix, appended.copyOf(originalPrefix.size))
        assertTrue(appended.size > originalPrefix.size)
    }

    @Test
    fun reachingTargetFinalizesSameSequenceAsCompressedPackage() {
        val root = temporaryRoot()
        val archive = TelemetryArchive(targetPartitionBytes = 200L, clock = { SEAL_TIME })
        val source = telemetryFile(root, "session-one")
        val original = source.readBytes()

        val result = archive.archiveFinalized(source, "session-one")

        assertEquals(1, result.partitionSequence)
        assertEquals("telemetry-000001.rha", result.storageFile.name)
        assertEquals(SEAL_TIME, result.partitionSealedAtEpochMs)
        assertFalse(File(result.archiveDirectory, "telemetry-000001.rhp").exists())
        val restored = File(root, "from-package.jsonl")
        assertTrue(
            archive.extractEntry(
                result.archiveDirectory,
                result.partitionSequence,
                result.entryId,
                restored,
            ),
        )
        assertArrayEquals(original, restored.readBytes())
        val frames = restored.readLines().map(::JSONObject).filter { it.getString("type") == "telemetry_frame" }
        assertEquals(40, frames.size)
        frames.forEachIndexed { index, frame ->
            assertEquals(index + 2, frame.getInt("seq"))
            assertEquals(index * 100_000_000L, frame.getLong("elapsed_realtime_ns"))
            val diagnostics = frame.getJSONObject("battery_estimation")
            if (index == 19) {
                assertTrue(frame.isNull("estimated_battery_percent"))
                assertEquals("invalid_crc", diagnostics.getString("status"))
            } else {
                assertEquals(60.0 - index * .01, frame.getDouble("estimated_battery_percent"), 0.0)
                assertEquals(frame.getDouble("estimated_battery_percent"), diagnostics.getDouble("estimated_percent"), 0.0)
            }
        }
    }

    @Test
    fun remoteSwitchFinalizesOldPartitionAndStartsNextSequence() {
        val root = temporaryRoot()
        val archive = TelemetryArchive(targetPartitionBytes = 100 * 1024L, clock = { SEAL_TIME })
        val first = archive.archiveFinalized(
            telemetryFile(root, "session-one", REMOTE_ONE),
            "session-one",
        )

        val second = archive.archiveFinalized(
            telemetryFile(root, "session-two", REMOTE_TWO),
            "session-two",
        )

        assertEquals(1, first.partitionSequence)
        assertEquals(2, second.partitionSequence)
        assertTrue(File(first.archiveDirectory, "telemetry-000001.rha").isFile)
        assertFalse(File(first.archiveDirectory, "telemetry-000001.rhp").exists())
        assertEquals("telemetry-000002.rhp", second.storageFile.name)
        assertEquals(REMOTE_TWO, second.remoteAddress)
    }

    @Test
    fun incompleteTrailingAppendIsDiscardedBeforeNextEntry() {
        val root = temporaryRoot()
        val archive = TelemetryArchive(targetPartitionBytes = 100 * 1024L)
        val first = archive.archiveFinalized(telemetryFile(root, "session-one"), "session-one")
        val cleanLength = first.storageFile.length()
        first.storageFile.appendBytes(byteArrayOf(0x52, 0x48, 0x45))
        assertTrue(first.storageFile.length() > cleanLength)

        val second = archive.archiveFinalized(telemetryFile(root, "session-two"), "session-two")

        assertEquals(first.partitionSequence, second.partitionSequence)
        val restored = File(root, "after-recovery.jsonl")
        assertTrue(
            archive.extractEntry(
                second.archiveDirectory,
                second.partitionSequence,
                second.entryId,
                restored,
            ),
        )
        assertTrue(restored.readText().contains("session-two"))
    }

    @Test
    fun refusesIncompleteLogAndLeavesSourceUntouched() {
        val root = temporaryRoot()
        val source = File(root, "incomplete.jsonl")
        source.writeText(record(1, "session_start", "session-one", REMOTE_ONE) + "\n")

        val result = runCatching {
            TelemetryArchive().archiveFinalized(source, "session-one")
        }

        assertTrue(result.isFailure)
        assertTrue(source.isFile)
        assertTrue(File(root, "archive").listFiles().isNullOrEmpty())
    }

    @Test
    fun retryReusesVerifiedEntryInsteadOfDuplicatingIt() {
        val root = temporaryRoot()
        val source = telemetryFile(root, "session-one")
        val original = source.readBytes()
        val archive = TelemetryArchive()
        val first = archive.archiveFinalized(source, "session-one")
        source.writeBytes(original)

        val second = archive.archiveFinalized(source, "session-one")

        assertFalse(source.exists())
        assertEquals(first.partitionSequence, second.partitionSequence)
        assertEquals(first.entryId, second.entryId)
        assertEquals(first.storageFile.length(), second.storageFile.length())
    }

    @Test
    fun corruptedEntryIsDiscardedAndCannotBeExtracted() {
        val root = temporaryRoot()
        val archive = TelemetryArchive()
        val result = archive.archiveFinalized(telemetryFile(root, "session-one"), "session-one")
        RandomAccessFile(result.storageFile, "rw").use { file ->
            val payloadByte = 24L + 48L + 5L
            file.seek(payloadByte)
            file.write(file.read().xor(0x40))
        }

        assertFalse(
            archive.extractEntry(
                result.archiveDirectory,
                result.partitionSequence,
                result.entryId,
                File(root, "must-not-extract.jsonl"),
            ),
        )
        assertEquals(24L, result.storageFile.length())
    }

    @Test
    fun workingPartitionSnapshotIsCompleteButDoesNotSealSourcePartition() {
        val root = temporaryRoot()
        val archive = TelemetryArchive(targetPartitionBytes = 100 * 1024L, clock = { SEAL_TIME })
        val stored = archive.archiveFinalized(telemetryFile(root, "session-one"), "session-one")
        val working = stored.storageFile
        assertEquals("rhp", working.extension)
        val original = working.readBytes()
        val exports = File(root, "exports")

        val upload = archive.prepareUploads(stored.archiveDirectory, exports).single()

        assertFalse(upload.final)
        assertEquals(1, upload.generation)
        assertEquals("rha", upload.file.extension)
        assertTrue(upload.compressedDigestBase64.isNotBlank())
        assertTrue(upload.uncompressedDigestBase64.isNotBlank())
        assertTrue(working.isFile)
        assertArrayEquals(original, working.readBytes())
        val restored = File(root, "snapshot-entry.jsonl")
        assertTrue(archive.extractEntry(exports, upload.partitionSequence, stored.entryId, restored))
        assertTrue(restored.readText().contains("session-one"))
    }

    private fun temporaryRoot(): File = Files.createTempDirectory("riders-hub-archive-test-")
        .toFile()
        .also(temporaryRoots::add)

    private fun telemetryFile(
        root: File,
        sessionId: String,
        remoteAddress: String = REMOTE_ONE,
    ): File {
        val source = File(root, "$sessionId.jsonl")
        val records = buildList {
            add(record(1, "session_start", sessionId, remoteAddress))
            repeat(40) { index ->
                val battery = if (index == 19) BatteryFrameDiagnostics("invalid_crc")
                    else BatteryFrameDiagnostics("updated", 60.0 - index * .01)
                add(
                    JSONObject()
                        .put("seq", index + 2)
                        .put("type", "telemetry_frame")
                        .put("wall_time", "2030-01-01T10:00:00Z")
                        .put("elapsed_realtime_ns", index * 100_000_000L)
                        .put("session_id", sessionId)
                        .put("raw_hex", "ac 00 19 00 02 50 00 00 00 00 00 00 00 00 00 00")
                        .put("pack_voltage_v", 40.0)
                        .put("estimated_battery_percent", battery.estimatedPercent ?: JSONObject.NULL)
                        .put("battery_estimation", battery.toJson())
                        .toString(),
                )
            }
            add(record(42, "session_end", sessionId, remoteAddress))
        }
        source.writeText(records.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
        return source
    }

    private fun record(
        sequence: Int,
        type: String,
        sessionId: String,
        remoteAddress: String,
    ): String = JSONObject()
        .put("seq", sequence)
        .put("type", type)
        .put("wall_time", "2030-01-01T10:00:00Z")
        .put("session_id", sessionId)
        .put("device_address", remoteAddress)
        .toString()

    private companion object {
        const val REMOTE_ONE = "02:00:00:00:00:01"
        const val REMOTE_TWO = "02:00:00:00:00:02"
        const val SEAL_TIME = 1_900_000_000_000L
    }
}

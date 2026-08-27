package at.themrcodes.ridershub.log

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

data class TelemetryArchiveReference(
    val archiveDirectory: File,
    val partitionSequence: Int,
    val entryId: String,
    val remoteAddress: String,
    val storageFile: File,
    val partitionSealedAtEpochMs: Long?,
    val recordCount: Int,
    val uncompressedBytes: Long,
    val sourceDeleted: Boolean,
) {
    val encoded: String
        get() = "rha:${File(archiveDirectory, partitionBaseName(partitionSequence)).absolutePath}#$entryId"
}

/**
 * Direct append-only telemetry partitions with immutable compressed analytics packages.
 *
 * Finalized JSONL sessions are appended unchanged to an RHP working partition. The remote MAC is
 * stored once in the partition header. A compact RHC1 footer makes each append distinguishable
 * from an interrupted write. At the size target or a remote switch, the whole partition is tagged,
 * compressed and verified as an immutable RHA package with the same sequence. An incomplete final
 * append is truncated; earlier committed entries remain usable.
 */
class TelemetryArchive(
    private val targetPartitionBytes: Long = DEFAULT_TARGET_PARTITION_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(targetPartitionBytes > WORKING_HEADER_SIZE + ENTRY_HEADER_SIZE + ENTRY_COMMIT_SIZE)
    }

    fun archiveFinalized(
        source: File,
        expectedSessionId: String? = null,
        expectedRemoteAddress: String? = null,
    ): TelemetryArchiveReference = synchronized(ARCHIVE_LOCK) {
        require(source.isFile) { "Telemetry source does not exist: ${source.name}" }
        val validation = validateFinalizedJsonl(source, expectedSessionId, expectedRemoteAddress)
        val remoteAddress = parseRemoteAddress(validation.remoteAddress)
        val sourceFingerprint = fingerprint(source)
        val entryId = entryIdBytes(validation.sessionId)
        val archiveDirectory = File(requireNotNull(source.parentFile), ARCHIVE_DIRECTORY_NAME)
            .also { it.mkdirs() }

        findExistingEntry(archiveDirectory, entryId)?.let { existing ->
            require(
                existing.entry.payloadBytes == sourceFingerprint.length &&
                    existing.entry.sourceCrc32 == sourceFingerprint.crc32 &&
                    existing.remoteAddress.contentEquals(remoteAddress),
            ) { "Existing archive entry differs from the finalized telemetry source" }
            val deleted = source.delete()
            return existing.toReference(entryId.toHex(), validation.recordCount, deleted)
        }

        val working = selectWorkingPartition(archiveDirectory, remoteAddress)
        val archivedAt = clock()
        appendEntry(
            working.file,
            entryId,
            source,
            sourceFingerprint,
            validation.recordCount,
            archivedAt,
        )
        var scan = scanWorkingPartition(working.file, entryId, verifyTargetPayload = true)
        check(scan.structurallyValid && scan.target != null) {
            "Appended telemetry entry could not be verified"
        }

        var sealedAt: Long? = null
        var storageFile = working.file
        if (working.file.length() >= targetPartitionBytes) {
            val packaged = packagePartition(working.file, scan)
            sealedAt = packaged.sealedAtEpochMs
            storageFile = packaged.file
        }

        val deleted = source.delete()
        TelemetryArchiveReference(
            archiveDirectory = archiveDirectory,
            partitionSequence = working.sequence,
            entryId = entryId.toHex(),
            remoteAddress = remoteAddress.toRemoteAddress(),
            storageFile = storageFile,
            partitionSealedAtEpochMs = sealedAt,
            recordCount = validation.recordCount,
            uncompressedBytes = sourceFingerprint.length,
            sourceDeleted = deleted,
        )
    }

    fun extractEntry(
        archiveDirectory: File,
        partitionSequence: Int,
        entryId: String,
        destination: File,
    ): Boolean = synchronized(ARCHIVE_LOCK) {
        val id = entryId.hexToBytesOrNull()?.takeIf { it.size == ENTRY_ID_BYTES } ?: return false
        val working = File(archiveDirectory, workingName(partitionSequence))
        if (working.isFile) {
            val scan = scanWorkingPartition(working, id, verifyTargetPayload = true)
            val entry = scan.target
            if (scan.structurallyValid && entry != null) {
                return copyEntryPayload(entry, destination)
            }
        }
        val packaged = File(archiveDirectory, packageName(partitionSequence))
        if (!packaged.isFile) return false
        val temporary = File.createTempFile("rhp-extract-", ".tmp", archiveDirectory)
        return try {
            val packageInfo = extractAndVerifyPackage(packaged, temporary) ?: return false
            val scan = scanWorkingPartition(temporary, id, verifyTargetPayload = true)
            val entry = scan.target
            scan.structurallyValid &&
                entry != null &&
                packageInfo.entryCount == scan.entryCount &&
                copyEntryPayload(entry, destination)
        } finally {
            temporary.delete()
        }
    }

    private fun selectWorkingPartition(directory: File, remoteAddress: ByteArray): WorkingPartition {
        val openPartitions = workingFiles(directory).mapNotNull { file ->
            val scan = scanWorkingPartition(file, targetEntryId = null, verifyTargetPayload = false)
            if (scan.structurallyValid) file to scan else null
        }
        val reusable = openPartitions.lastOrNull { (_, scan) ->
            scan.remoteAddress.contentEquals(remoteAddress) && scan.fileLength < targetPartitionBytes
        }

        openPartitions.forEach { (file, scan) ->
            if (reusable?.first != file && scan.entryCount > 0) packagePartition(file, scan)
        }
        if (reusable != null) {
            return WorkingPartition(reusable.first, reusable.second.sequence)
        }

        val sequence = nextPartitionSequence(directory)
        val file = File(directory, workingName(sequence))
        writeWorkingHeader(file, sequence, remoteAddress)
        return WorkingPartition(file, sequence)
    }

    private fun appendEntry(
        working: File,
        entryId: ByteArray,
        source: File,
        fingerprint: FileFingerprint,
        recordCount: Int,
        archivedAtEpochMs: Long,
    ) {
        val header = entryHeader(
            entryId,
            fingerprint.length,
            recordCount,
            fingerprint.crc32,
            archivedAtEpochMs,
        )
        val storedCrc = CRC32().apply {
            update(header)
            FileInputStream(source).use { updateFrom(it) }
        }.value
        val footer = entryCommitFooter(
            entryId,
            ENTRY_HEADER_SIZE + fingerprint.length,
            storedCrc,
        )
        FileOutputStream(working, true).use { rawOutput ->
            val output = BufferedOutputStream(rawOutput, BUFFER_SIZE)
            try {
                output.write(header)
                FileInputStream(source).use { it.copyTo(output, BUFFER_SIZE) }
                output.write(footer)
                output.flush()
                rawOutput.fd.sync()
            } finally {
                output.close()
            }
        }
    }

    private fun packagePartition(working: File, scan: WorkingScan): PackageInfo {
        require(scan.structurallyValid)
        val sequence = scan.sequence
        val destination = File(requireNotNull(working.parentFile), packageName(sequence))
        val fingerprint = fingerprint(working)

        if (destination.exists()) {
            val existing = verifyPackage(destination)
            require(
                existing != null &&
                    existing.partitionBytes == fingerprint.length &&
                    existing.partitionCrc32 == fingerprint.crc32 &&
                    existing.remoteAddress.contentEquals(scan.remoteAddress),
            ) { "Existing analytics package conflicts with working partition" }
            check(working.delete()) { "Verified working partition could not be removed" }
            return existing
        }

        val compressed = File.createTempFile("rhp-package-", ".deflate.tmp", working.parentFile)
        val candidate = File(working.parentFile, ".${destination.name}.tmp")
        try {
            compressFile(working, compressed)
            val sealedAt = clock()
            val header = packageHeader(
                sequence = sequence,
                remoteAddress = scan.remoteAddress,
                sealedAtEpochMs = sealedAt,
                partitionBytes = fingerprint.length,
                compressedBytes = compressed.length(),
                entryCount = scan.entryCount,
                partitionCrc32 = fingerprint.crc32,
            )
            if (candidate.exists()) check(candidate.delete())
            FileOutputStream(candidate).use { rawOutput ->
                val output = BufferedOutputStream(rawOutput, BUFFER_SIZE)
                try {
                    output.write(header)
                    FileInputStream(compressed).use { it.copyTo(output, BUFFER_SIZE) }
                    output.flush()
                    rawOutput.fd.sync()
                } finally {
                    output.close()
                }
            }
            val candidateInfo = requireNotNull(verifyPackage(candidate)) {
                "Analytics package candidate could not be verified"
            }
            moveNewFile(candidate, destination)
            val committed = requireNotNull(verifyPackage(destination)) {
                "Committed analytics package could not be verified"
            }
            check(committed.samePackageAs(candidateInfo))
            check(working.delete()) { "Verified working partition could not be removed" }
            return committed
        } finally {
            compressed.delete()
            candidate.delete()
        }
    }

    private fun verifyPackage(file: File): PackageInfo? {
        if (!file.isFile || file.length() < PACKAGE_HEADER_SIZE) return null
        val header = runCatching { readPackageHeader(file) }.getOrNull() ?: return null
        if (header.compressedBytes != file.length() - PACKAGE_HEADER_SIZE) return null
        val temporary = File.createTempFile("rhp-verify-", ".tmp", requireNotNull(file.parentFile))
        return try {
            val extracted = extractAndVerifyPackage(file, temporary) ?: return null
            val scan = scanWorkingPartition(temporary, targetEntryId = null, verifyTargetPayload = false)
            if (!scan.structurallyValid ||
                scan.sequence != extracted.sequence ||
                !scan.remoteAddress.contentEquals(extracted.remoteAddress) ||
                scan.entryCount != extracted.entryCount
            ) {
                null
            } else {
                extracted.copy(file = file)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun extractAndVerifyPackage(file: File, destination: File): PackageInfo? = runCatching {
        val header = readPackageHeader(file)
        if (header.compressedBytes != file.length() - PACKAGE_HEADER_SIZE) return null
        FileInputStream(file).use { raw ->
            raw.channel.position(PACKAGE_HEADER_SIZE.toLong())
            InflaterInputStream(
                BoundedInputStream(BufferedInputStream(raw, BUFFER_SIZE), header.compressedBytes),
            ).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                    output.flush()
                    output.fd.sync()
                }
            }
        }
        val fingerprint = fingerprint(destination)
        if (fingerprint.length != header.partitionBytes ||
            fingerprint.crc32 != header.partitionCrc32
        ) {
            null
        } else {
            header.copy(file = file)
        }
    }.getOrNull()

    private fun findExistingEntry(directory: File, entryId: ByteArray): ExistingEntry? {
        workingFiles(directory).forEach { working ->
            val scan = scanWorkingPartition(working, entryId, verifyTargetPayload = true)
            if (scan.structurallyValid && scan.target != null) {
                return ExistingEntry(
                    scan.target,
                    scan.remoteAddress,
                    scan.sequence,
                    working,
                    sealedAtEpochMs = null,
                )
            }
        }
        packageFiles(directory).asReversed().forEach { packaged ->
            val temporary = File.createTempFile("rhp-dedup-", ".tmp", directory)
            try {
                val packageInfo = extractAndVerifyPackage(packaged, temporary) ?: return@forEach
                val scan = scanWorkingPartition(temporary, entryId, verifyTargetPayload = true)
                if (scan.structurallyValid && scan.target != null) {
                    return ExistingEntry(
                        scan.target,
                        packageInfo.remoteAddress,
                        packageInfo.sequence,
                        packaged,
                        packageInfo.sealedAtEpochMs,
                    )
                }
            } finally {
                temporary.delete()
            }
        }
        return null
    }

    private fun scanWorkingPartition(
        file: File,
        targetEntryId: ByteArray?,
        verifyTargetPayload: Boolean,
    ): WorkingScan {
        if (!file.isFile || file.length() < WORKING_HEADER_SIZE) return WorkingScan.invalid(file)
        return runCatching {
            RandomAccessFile(file, "rw").use { input ->
                if (input.readInt() != WORKING_MAGIC ||
                    input.readInt() != FORMAT_VERSION ||
                    input.readInt() != WORKING_HEADER_SIZE
                ) {
                    return WorkingScan.invalid(file)
                }
                val sequence = input.readInt()
                val remoteAddress = ByteArray(REMOTE_ADDRESS_BYTES).also(input::readFully)
                input.readUnsignedShort()
                var target: ArchiveEntry? = null
                var entryCount = 0
                while (input.filePointer < input.length()) {
                    val headerOffset = input.filePointer
                    if (input.length() - headerOffset < ENTRY_HEADER_SIZE) {
                        input.setLength(headerOffset)
                        break
                    }
                    val header = ByteArray(ENTRY_HEADER_SIZE).also(input::readFully)
                    val entry = parseEntryHeader(file, headerOffset, header)
                    if (entry == null) {
                        input.setLength(headerOffset)
                        break
                    }
                    val footerOffset = entry.payloadOffset + entry.payloadBytes
                    if (footerOffset < entry.payloadOffset ||
                        input.length() - footerOffset < ENTRY_COMMIT_SIZE
                    ) {
                        input.setLength(headerOffset)
                        break
                    }
                    input.seek(footerOffset)
                    if (input.readInt() != ENTRY_COMMIT_MAGIC) {
                        input.setLength(headerOffset)
                        break
                    }
                    val committedId = ByteArray(ENTRY_ID_BYTES).also(input::readFully)
                    val committedBytes = input.readLong()
                    val storedCrc = input.readInt().toLong() and 0xffff_ffffL
                    if (!committedId.contentEquals(entry.entryId) ||
                        committedBytes != ENTRY_HEADER_SIZE + entry.payloadBytes ||
                        crcOfRange(file, headerOffset, committedBytes) != storedCrc
                    ) {
                        input.setLength(headerOffset)
                        break
                    }
                    val isTarget = targetEntryId != null && entry.entryId.contentEquals(targetEntryId)
                    if (isTarget) {
                        if (verifyTargetPayload && !verifyEntryPayload(entry)) {
                            input.setLength(headerOffset)
                            break
                        }
                        target = entry
                    }
                    entryCount += 1
                }
                WorkingScan(
                    file = file,
                    structurallyValid = true,
                    sequence = sequence,
                    remoteAddress = remoteAddress,
                    entryCount = entryCount,
                    fileLength = file.length(),
                    target = target,
                )
            }
        }.getOrElse { WorkingScan.invalid(file) }
    }

    private fun verifyEntryPayload(entry: ArchiveEntry): Boolean {
        val crc = CRC32()
        var bytes = 0L
        var newlines = 0
        var lastByte = -1
        FileInputStream(entry.file).use { raw ->
            raw.channel.position(entry.payloadOffset)
            BoundedInputStream(BufferedInputStream(raw, BUFFER_SIZE), entry.payloadBytes).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    crc.update(buffer, 0, read)
                    bytes += read
                    repeat(read) { index ->
                        if (buffer[index] == '\n'.code.toByte()) newlines += 1
                        lastByte = buffer[index].toInt() and 0xff
                    }
                }
            }
        }
        val records = newlines + if (bytes > 0 && lastByte != '\n'.code) 1 else 0
        return bytes == entry.payloadBytes &&
            crc.value == entry.sourceCrc32 &&
            records == entry.recordCount
    }

    private fun copyEntryPayload(entry: ArchiveEntry, destination: File): Boolean = runCatching {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.tmp")
        try {
            FileInputStream(entry.file).use { raw ->
                raw.channel.position(entry.payloadOffset)
                BoundedInputStream(BufferedInputStream(raw, BUFFER_SIZE), entry.payloadBytes).use { input ->
                    FileOutputStream(temporary).use { output ->
                        input.copyTo(output, BUFFER_SIZE)
                        output.flush()
                        output.fd.sync()
                    }
                }
            }
            if (fingerprint(temporary).let { it.length != entry.payloadBytes || it.crc32 != entry.sourceCrc32 }) {
                return false
            }
            moveReplaceableOutput(temporary, destination)
            true
        } finally {
            temporary.delete()
        }
    }.getOrDefault(false)

    private fun writeWorkingHeader(file: File, sequence: Int, remoteAddress: ByteArray) {
        require(!file.exists())
        FileOutputStream(file).use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(WORKING_MAGIC)
                data.writeInt(FORMAT_VERSION)
                data.writeInt(WORKING_HEADER_SIZE)
                data.writeInt(sequence)
                data.write(remoteAddress)
                data.writeShort(0)
                data.flush()
                output.fd.sync()
            }
        }
    }

    private fun entryHeader(
        entryId: ByteArray,
        payloadBytes: Long,
        recordCount: Int,
        sourceCrc32: Long,
        archivedAtEpochMs: Long,
    ): ByteArray = ByteArrayOutputStream(ENTRY_HEADER_SIZE).use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(ENTRY_MAGIC)
            output.writeShort(FORMAT_VERSION)
            output.writeShort(ENTRY_HEADER_SIZE)
            output.write(entryId)
            output.writeLong(payloadBytes)
            output.writeInt(recordCount)
            output.writeInt(sourceCrc32.toInt())
            output.writeLong(archivedAtEpochMs)
        }
        bytes.toByteArray().also { check(it.size == ENTRY_HEADER_SIZE) }
    }

    private fun parseEntryHeader(file: File, headerOffset: Long, header: ByteArray): ArchiveEntry? =
        runCatching {
            DataInputStream(ByteArrayInputStream(header)).use { input ->
                if (input.readInt() != ENTRY_MAGIC ||
                    input.readUnsignedShort() != FORMAT_VERSION ||
                    input.readUnsignedShort() != ENTRY_HEADER_SIZE
                ) {
                    return null
                }
                val id = ByteArray(ENTRY_ID_BYTES).also(input::readFully)
                val payloadBytes = input.readLong()
                val recordCount = input.readInt()
                val sourceCrc = input.readInt().toLong() and 0xffff_ffffL
                val archivedAt = input.readLong()
                if (payloadBytes < 0 || recordCount <= 0 || archivedAt <= 0) return null
                ArchiveEntry(
                    file,
                    id,
                    headerOffset + ENTRY_HEADER_SIZE,
                    payloadBytes,
                    recordCount,
                    sourceCrc,
                    archivedAt,
                )
            }
        }.getOrNull()

    private fun entryCommitFooter(entryId: ByteArray, entryBytes: Long, storedCrc32: Long): ByteArray =
        ByteArrayOutputStream(ENTRY_COMMIT_SIZE).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(ENTRY_COMMIT_MAGIC)
                output.write(entryId)
                output.writeLong(entryBytes)
                output.writeInt(storedCrc32.toInt())
            }
            bytes.toByteArray().also { check(it.size == ENTRY_COMMIT_SIZE) }
        }

    private fun packageHeader(
        sequence: Int,
        remoteAddress: ByteArray,
        sealedAtEpochMs: Long,
        partitionBytes: Long,
        compressedBytes: Long,
        entryCount: Int,
        partitionCrc32: Long,
    ): ByteArray = ByteArrayOutputStream(PACKAGE_HEADER_SIZE).use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(PACKAGE_MAGIC)
            output.writeInt(FORMAT_VERSION)
            output.writeInt(PACKAGE_HEADER_SIZE)
            output.writeInt(sequence)
            output.write(remoteAddress)
            output.writeShort(0)
            output.writeLong(sealedAtEpochMs)
            output.writeLong(partitionBytes)
            output.writeLong(compressedBytes)
            output.writeInt(entryCount)
            output.writeInt(partitionCrc32.toInt())
        }
        bytes.toByteArray().also { check(it.size == PACKAGE_HEADER_SIZE) }
    }

    private fun readPackageHeader(file: File): PackageInfo = DataInputStream(FileInputStream(file)).use { input ->
        require(input.readInt() == PACKAGE_MAGIC)
        require(input.readInt() == FORMAT_VERSION)
        require(input.readInt() == PACKAGE_HEADER_SIZE)
        val sequence = input.readInt()
        val remoteAddress = ByteArray(REMOTE_ADDRESS_BYTES).also(input::readFully)
        input.readUnsignedShort()
        val sealedAt = input.readLong()
        val partitionBytes = input.readLong()
        val compressedBytes = input.readLong()
        val entryCount = input.readInt()
        val partitionCrc = input.readInt().toLong() and 0xffff_ffffL
        require(sequence > 0 && sealedAt > 0 && partitionBytes >= WORKING_HEADER_SIZE)
        require(compressedBytes >= 0 && entryCount > 0)
        PackageInfo(
            file,
            sequence,
            remoteAddress,
            sealedAt,
            partitionBytes,
            compressedBytes,
            entryCount,
            partitionCrc,
        )
    }

    private fun validateFinalizedJsonl(
        source: File,
        expectedSessionId: String?,
        expectedRemoteAddress: String?,
    ): JsonlValidation {
        var previousSequence = 0L
        var recordCount = 0
        var lastType: String? = null
        var sessionId: String? = null
        var remoteAddress: String? = null
        source.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                require(line.isNotBlank()) { "Telemetry log contains a blank record" }
                val value = JSONObject(line)
                val sequence = value.getLong("seq")
                require(sequence > previousSequence) { "Telemetry sequence is not strictly increasing" }
                previousSequence = sequence
                recordCount += 1
                lastType = value.getString("type")
                if (value.has("session_id") && !value.isNull("session_id")) {
                    sessionId = value.getString("session_id")
                }
                if (value.has("device_address") && !value.isNull("device_address")) {
                    remoteAddress = normalizeRemoteAddress(value.getString("device_address"))
                }
            }
        }
        require(recordCount > 0 && lastType == "session_end") { "Telemetry log is not finalized" }
        val actualSessionId = requireNotNull(sessionId) { "Telemetry session identifier is missing" }
        val actualRemoteAddress = requireNotNull(remoteAddress) { "Remote address is missing" }
        if (expectedSessionId != null) require(actualSessionId == expectedSessionId)
        if (expectedRemoteAddress != null) {
            require(actualRemoteAddress == normalizeRemoteAddress(expectedRemoteAddress))
        }
        return JsonlValidation(recordCount, actualSessionId, actualRemoteAddress)
    }

    private fun fingerprint(file: File): FileFingerprint {
        val crc = CRC32()
        FileInputStream(file).use { crc.updateFrom(it) }
        return FileFingerprint(file.length(), crc.value)
    }

    private fun crcOfRange(file: File, offset: Long, length: Long): Long {
        val crc = CRC32()
        FileInputStream(file).use { raw ->
            raw.channel.position(offset)
            BoundedInputStream(BufferedInputStream(raw, BUFFER_SIZE), length).use { crc.updateFrom(it) }
        }
        return crc.value
    }

    private fun compressFile(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { rawOutput ->
                val output = DeflaterOutputStream(
                    BufferedOutputStream(rawOutput, BUFFER_SIZE),
                    Deflater(Deflater.DEFAULT_COMPRESSION),
                    BUFFER_SIZE,
                )
                try {
                    input.copyTo(output, BUFFER_SIZE)
                    output.finish()
                    output.flush()
                    rawOutput.fd.sync()
                } finally {
                    output.close()
                }
            }
        }
    }

    private fun nextPartitionSequence(directory: File): Int =
        (workingFiles(directory) + packageFiles(directory))
            .maxOfOrNull { it.name.partitionSequence() }
            ?.plus(1) ?: 1

    private fun workingFiles(directory: File): List<File> = directory.listFiles()
        ?.filter { it.isFile && WORKING_PATTERN.matches(it.name) }
        ?.sortedBy { it.name.partitionSequence() }
        .orEmpty()

    private fun packageFiles(directory: File): List<File> = directory.listFiles()
        ?.filter { it.isFile && PACKAGE_PATTERN.matches(it.name) }
        ?.sortedBy { it.name.partitionSequence() }
        .orEmpty()

    private fun entryIdBytes(sessionId: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(
                "riders-hub-session:$sessionId"
                    .toByteArray(Charsets.UTF_8),
            )
            .copyOf(ENTRY_ID_BYTES)

    private fun parseRemoteAddress(value: String): ByteArray {
        val normalized = value.trim().uppercase(Locale.ROOT)
        require(REMOTE_ADDRESS_PATTERN.matches(normalized)) { "Invalid remote address" }
        return normalized.split(':').map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun normalizeRemoteAddress(value: String): String =
        parseRemoteAddress(value).toRemoteAddress()

    private fun ExistingEntry.toReference(id: String, records: Int, deleted: Boolean) =
        TelemetryArchiveReference(
            archiveDirectory = requireNotNull(storageFile.parentFile),
            partitionSequence = sequence,
            entryId = id,
            remoteAddress = remoteAddress.toRemoteAddress(),
            storageFile = storageFile,
            partitionSealedAtEpochMs = sealedAtEpochMs,
            recordCount = records,
            uncompressedBytes = entry.payloadBytes,
            sourceDeleted = deleted,
        )

    private fun CRC32.updateFrom(input: InputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            update(buffer, 0, read)
        }
    }

    private fun moveNewFile(source: File, destination: File) {
        require(!destination.exists())
        runCatching {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun moveReplaceableOutput(source: File, destination: File) {
        runCatching {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private data class WorkingPartition(val file: File, val sequence: Int)
    private data class FileFingerprint(val length: Long, val crc32: Long)
    private data class JsonlValidation(val recordCount: Int, val sessionId: String, val remoteAddress: String)

    private data class ArchiveEntry(
        val file: File,
        val entryId: ByteArray,
        val payloadOffset: Long,
        val payloadBytes: Long,
        val recordCount: Int,
        val sourceCrc32: Long,
        val archivedAtEpochMs: Long,
    )

    private data class ExistingEntry(
        val entry: ArchiveEntry,
        val remoteAddress: ByteArray,
        val sequence: Int,
        val storageFile: File,
        val sealedAtEpochMs: Long?,
    )

    private data class WorkingScan(
        val file: File,
        val structurallyValid: Boolean,
        val sequence: Int,
        val remoteAddress: ByteArray,
        val entryCount: Int,
        val fileLength: Long,
        val target: ArchiveEntry?,
    ) {
        companion object {
            fun invalid(
                file: File,
                sequence: Int = 0,
                remoteAddress: ByteArray = ByteArray(REMOTE_ADDRESS_BYTES),
                entryCount: Int = 0,
            ) = WorkingScan(file, false, sequence, remoteAddress, entryCount, file.length(), null)
        }
    }

    private data class PackageInfo(
        val file: File,
        val sequence: Int,
        val remoteAddress: ByteArray,
        val sealedAtEpochMs: Long,
        val partitionBytes: Long,
        val compressedBytes: Long,
        val entryCount: Int,
        val partitionCrc32: Long,
    ) {
        fun samePackageAs(other: PackageInfo): Boolean =
            sequence == other.sequence &&
                remoteAddress.contentEquals(other.remoteAddress) &&
                sealedAtEpochMs == other.sealedAtEpochMs &&
                partitionBytes == other.partitionBytes &&
                compressedBytes == other.compressedBytes &&
                entryCount == other.entryCount &&
                partitionCrc32 == other.partitionCrc32
    }

    private class BoundedInputStream(source: InputStream, private var remaining: Long) :
        FilterInputStream(source) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = super.read()
            if (value >= 0) remaining -= 1
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val read = super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (read > 0) remaining -= read
            return read
        }
    }

    companion object {
        const val DEFAULT_TARGET_PARTITION_BYTES = 10L * 1024L * 1024L
        private const val ARCHIVE_DIRECTORY_NAME = "archive"
        private const val WORKING_MAGIC = 0x52485031
        private const val ENTRY_MAGIC = 0x52484531
        private const val ENTRY_COMMIT_MAGIC = 0x52484331
        private const val PACKAGE_MAGIC = 0x52484131
        private const val FORMAT_VERSION = 1
        private const val WORKING_HEADER_SIZE = 24
        private const val ENTRY_HEADER_SIZE = 48
        private const val ENTRY_COMMIT_SIZE = 32
        private const val PACKAGE_HEADER_SIZE = 56
        private const val ENTRY_ID_BYTES = 16
        private const val REMOTE_ADDRESS_BYTES = 6
        private const val BUFFER_SIZE = 64 * 1024
        private val WORKING_PATTERN = Regex("telemetry-[0-9]{6}\\.rhp")
        private val PACKAGE_PATTERN = Regex("telemetry-[0-9]{6}\\.rha")
        private val REMOTE_ADDRESS_PATTERN = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
        private val ARCHIVE_LOCK = Any()
    }
}

private fun partitionBaseName(sequence: Int): String =
    String.format(Locale.ROOT, "telemetry-%06d", sequence)

private fun workingName(sequence: Int): String = "${partitionBaseName(sequence)}.rhp"
private fun packageName(sequence: Int): String = "${partitionBaseName(sequence)}.rha"

private fun String.partitionSequence(): Int = substringAfter("telemetry-")
    .substringBefore('.')
    .toIntOrNull() ?: 0

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
}

private fun ByteArray.toRemoteAddress(): String = joinToString(":") { byte ->
    String.format(Locale.ROOT, "%02X", byte.toInt() and 0xff)
}

private fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0 || any { it.digitToIntOrNull(16) == null }) return null
    return ByteArray(length / 2) { index ->
        val high = this[index * 2].digitToInt(16)
        val low = this[index * 2 + 1].digitToInt(16)
        ((high shl 4) or low).toByte()
    }
}

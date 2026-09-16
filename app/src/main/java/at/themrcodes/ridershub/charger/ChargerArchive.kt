package at.themrcodes.ridershub.charger

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import at.themrcodes.ridershub.api.ApiArchiveWorker

data class ChargerFileUpload(
    val file: File,
    val fileId: String,
    val recordCount: Int,
    val digestBase64: String,
)

/** Append-only COA v1 evidence. Hardware addresses and raw RPC payloads are never recorded. */
class ChargerArchive private constructor(private val context: Context) {
    private val root = context.getExternalFilesDir("charger") ?: File(context.filesDir, "charger")
    private val active = File(root, "active").apply { mkdirs() }
    private val archive = File(root, "archive").apply { mkdirs() }
    private var current: File? = null
    private var fileId: String? = null
    private var chargerId: String? = null
    private var sequence = 0

    init { recoverInterruptedFiles() }

    @Synchronized
    fun start(reason: String): String {
        if (current != null) return requireNotNull(fileId)
        val id = UUID.randomUUID().toString()
        val localChargerId = chargerIdentifier()
        current = File(active, "$id.coa")
        fileId = id
        chargerId = localChargerId
        sequence = 0
        append("session_start", JSONObject().put("reason", reason))
        return id
    }

    @Synchronized
    fun append(type: String, data: JSONObject = JSONObject()) {
        val target = current ?: return
        val record = JSONObject()
            .put("coa_version", 1)
            .put("record", type)
            .put("sequence", ++sequence)
            .put("observed_at", Instant.now().toString())
            .put("charger_id", chargerId)
            .put("data", data)
        FileOutputStream(target, true).bufferedWriter(Charsets.UTF_8).use {
            it.append(record.toString()).append('\n')
        }
    }

    @Synchronized
    fun sample(observation: ChargerObservation) = append(
        "sample",
        JSONObject()
            .put("output_on", observation.outputOn)
            .put("power_w", observation.powerWatts)
            .apply { observation.temperatureC?.takeIf(Double::isFinite)?.let { put("temperature_c", it) } }
            .put("over_temperature", observation.overTemperature)
            .put("over_power", observation.overPower)
            .put("over_voltage", observation.overVoltage)
            .put("under_voltage", observation.underVoltage),
    )

    @Synchronized
    fun finish(reason: String) {
        val source = current ?: return
        append("session_end", JSONObject().put("reason", reason))
        val destination = File(archive, source.name)
        check(source.renameTo(destination)) { "Could not finalize charger observation archive" }
        current = null; fileId = null; chargerId = null; sequence = 0
        ApiArchiveWorker.enqueue(context)
    }

    fun finalizedUploads(maxBytes: Long): List<ChargerFileUpload> = archive.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "coa" && it.length() in 1..maxBytes }
        .sortedBy(File::getName)
        .mapNotNull { validate(it) }

    private fun validate(file: File): ChargerFileUpload? = runCatching {
        var count = 0
        var expected = 1
        var first: String? = null
        var last: String? = null
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val value = JSONObject(line)
                require(value.getInt("coa_version") == 1)
                require(value.getInt("sequence") == expected++)
                require(UUID.fromString(value.getString("charger_id")).toString() == value.getString("charger_id"))
                val type = value.getString("record")
                if (first == null) first = type
                last = type
                count++
            }
        }
        require(count >= 2 && first == "session_start" && last == "session_end")
        ChargerFileUpload(file, file.nameWithoutExtension, count, sha256(file))
    }.getOrNull()

    private fun chargerIdentifier(): String {
        val prefs = context.getSharedPreferences("charger_archive", Context.MODE_PRIVATE)
        return prefs.getString("charger_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("charger_id", it).apply()
        }
    }

    private fun recoverInterruptedFiles() {
        active.listFiles().orEmpty().filter { it.isFile && it.extension == "coa" }.forEach { file ->
            runCatching {
                val records = file.readLines(Charsets.UTF_8).filter(String::isNotBlank).map(::JSONObject)
                if (records.isEmpty()) return@runCatching
                val last = records.last()
                val end = JSONObject()
                    .put("coa_version", 1)
                    .put("record", "session_end")
                    .put("sequence", last.getInt("sequence") + 1)
                    .put("observed_at", Instant.now().toString())
                    .put("charger_id", last.getString("charger_id"))
                    .put("data", JSONObject().put("reason", "process_recovery"))
                FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use {
                    it.append(end.toString()).append('\n')
                }
                check(file.renameTo(File(archive, file.name)))
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return Base64.getEncoder().encodeToString(digest.digest())
    }

    companion object {
        @Volatile private var instance: ChargerArchive? = null
        fun get(context: Context): ChargerArchive = instance ?: synchronized(this) {
            instance ?: ChargerArchive(context.applicationContext).also { instance = it }
        }
    }
}

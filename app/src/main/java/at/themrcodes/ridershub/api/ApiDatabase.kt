package at.themrcodes.ridershub.api

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import at.themrcodes.ridershub.log.TelemetryPartitionUpload
import at.themrcodes.ridershub.charger.ChargerFileUpload

internal data class PendingMetric(
    val rowId: Long,
    val destinationId: String,
    val payload: String,
    val attempts: Int,
)

internal class ApiDatabase(context: Context) : SQLiteOpenHelper(
    context, "self_hosted_api.db", null, 3,
) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("""
            CREATE TABLE destination (
              destination_id TEXT PRIMARY KEY,
              server_id TEXT,
              capabilities_json TEXT,
              analytics_consent_server_id TEXT,
              metrics_status TEXT NOT NULL DEFAULT 'Not tested',
              realtime_status TEXT NOT NULL DEFAULT 'Not tested',
              analytics_status TEXT NOT NULL DEFAULT 'Not approved',
              charger_analytics_status TEXT NOT NULL DEFAULT 'Not approved',
              gps_status TEXT NOT NULL DEFAULT 'Off',
              last_error TEXT,
              updated_at TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE metric_outbox (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              destination_id TEXT NOT NULL,
              local_event_key TEXT NOT NULL UNIQUE,
              message_id TEXT NOT NULL UNIQUE,
              source_sequence INTEGER NOT NULL,
              kind TEXT NOT NULL,
              payload TEXT NOT NULL,
              state TEXT NOT NULL DEFAULT 'pending',
              attempts INTEGER NOT NULL DEFAULT 0,
              last_error TEXT,
              created_at TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX metric_pending ON metric_outbox(destination_id, state, source_sequence)")
        db.execSQL("""
            CREATE TABLE identity_map (
              kind TEXT NOT NULL,
              local_key TEXT NOT NULL,
              export_id TEXT NOT NULL,
              PRIMARY KEY(kind, local_key),
              UNIQUE(kind, export_id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE archive_delivery (
              destination_id TEXT NOT NULL,
              source_id TEXT NOT NULL,
              partition_sequence INTEGER NOT NULL,
              generation INTEGER NOT NULL,
              logical_digest TEXT NOT NULL,
              compressed_digest TEXT NOT NULL,
              final INTEGER NOT NULL,
              file_path TEXT NOT NULL,
              state TEXT NOT NULL,
              last_error TEXT,
              PRIMARY KEY(destination_id, source_id, partition_sequence)
            )
        """.trimIndent())
        createChargerDelivery(db)
        createDeliveryState(db)
    }

    private fun createDeliveryState(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE analytics_consent (destination_id TEXT NOT NULL, server_id TEXT NOT NULL, PRIMARY KEY(destination_id,server_id))")
        db.execSQL("CREATE TABLE channel_state (scope TEXT NOT NULL, channel TEXT NOT NULL, paused INTEGER NOT NULL DEFAULT 0, retry_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(scope,channel))")
    }

    private fun createChargerDelivery(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE charger_file_delivery (
              destination_id TEXT NOT NULL,
              source_id TEXT NOT NULL,
              file_id TEXT NOT NULL,
              digest TEXT NOT NULL,
              file_path TEXT NOT NULL,
              state TEXT NOT NULL,
              last_error TEXT,
              PRIMARY KEY(destination_id, source_id, file_id)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createDeliveryState(db)
            db.execSQL("INSERT INTO analytics_consent SELECT destination_id,analytics_consent_server_id FROM destination WHERE analytics_consent_server_id IS NOT NULL")
            // Preserve development-version rows under the server authenticated when recorded.
            db.rawQuery("SELECT destination_id,server_id FROM destination WHERE server_id IS NOT NULL", null).use { rows ->
                while (rows.moveToNext()) {
                    val destination = rows.getString(0)
                    val scope = deliveryScopeId(destination, rows.getString(1))
                    db.rawQuery("SELECT id,local_event_key FROM metric_outbox WHERE destination_id=?", arrayOf(destination)).use { events ->
                        while (events.moveToNext()) {
                            val key = events.getString(1).removePrefix("$destination:")
                            db.execSQL("UPDATE metric_outbox SET destination_id=?,local_event_key=? WHERE id=?",
                                arrayOf<Any>(scope, "$scope:$key", events.getLong(0)))
                        }
                    }
                    db.execSQL("UPDATE archive_delivery SET destination_id=? WHERE destination_id=?", arrayOf(scope, destination))
                }
            }
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE destination ADD COLUMN charger_analytics_status TEXT NOT NULL DEFAULT 'Not approved'")
            createChargerDelivery(db)
        }
    }

    fun sourceId(): String = writableDatabase.inTransactionResult {
        queryValue("source_id") ?: UUID.randomUUID().toString().also { putValue("source_id", it) }
    }

    fun exportId(kind: String, localKey: String): String {
        if (kind == "board" && localKey.startsWith("board_")) {
            return UUID.nameUUIDFromBytes(
                ("riders-hub-api-board:v1:" + localKey.lowercase(java.util.Locale.ROOT)).toByteArray(Charsets.UTF_8),
            ).toString()
        }
        return writableDatabase.inTransactionResult {
            rawQuery(
                "SELECT export_id FROM identity_map WHERE kind=? AND local_key=?",
                arrayOf(kind, localKey),
            ).use { if (it.moveToFirst()) return@inTransactionResult it.getString(0) }
            UUID.randomUUID().toString().also { generated ->
                insertOrThrow("identity_map", null, ContentValues().apply {
                    put("kind", kind); put("local_key", localKey); put("export_id", generated)
                })
            }
        }
    }

    fun saveDestination(destinationId: String, serverId: String?, capabilities: JSONObject?) {
        writableDatabase.insertWithOnConflict("destination", null, ContentValues().apply {
            put("destination_id", destinationId)
            if (serverId != null) put("server_id", serverId)
            if (capabilities != null) put("capabilities_json", capabilities.toString())
            put("updated_at", Instant.now().toString())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (serverId != null || capabilities != null) {
            writableDatabase.update("destination", ContentValues().apply {
                if (serverId != null) put("server_id", serverId)
                if (capabilities != null) put("capabilities_json", capabilities.toString())
                put("updated_at", Instant.now().toString())
            }, "destination_id=?", arrayOf(destinationId))
        }
    }

    fun cachedCapabilities(destinationId: String): JSONObject? = readableDatabase.rawQuery(
        "SELECT capabilities_json FROM destination WHERE destination_id=?",
        arrayOf(destinationId),
    ).use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) runCatching { JSONObject(cursor.getString(0)) }.getOrNull()
        else null
    }

    fun serverId(destinationId: String): String? = readableDatabase.rawQuery(
        "SELECT server_id FROM destination WHERE destination_id=?", arrayOf(destinationId),
    ).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    fun analyticsApproved(destinationId: String, serverId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM analytics_consent WHERE destination_id=? AND server_id=?",
        arrayOf(destinationId, serverId),
    ).use { it.moveToFirst() }

    fun setAnalyticsApproved(destinationId: String, serverId: String, approved: Boolean) {
        saveDestination(destinationId, serverId, null)
        if (approved) {
            writableDatabase.execSQL("INSERT OR IGNORE INTO analytics_consent VALUES (?,?)", arrayOf(destinationId, serverId))
        } else {
            writableDatabase.delete("analytics_consent", "destination_id=? AND server_id=?", arrayOf(destinationId, serverId))
        }
        writableDatabase.update("destination", ContentValues().apply {
            if (approved) put("analytics_consent_server_id", serverId) else putNull("analytics_consent_server_id")
            put("analytics_status", if (approved) "Ready" else "Not approved")
            put("charger_analytics_status", if (approved) "Ready" else "Not approved")
        }, "destination_id=?", arrayOf(destinationId))
    }

    /** Allocates the source sequence and inserts the exact retry payload in one transaction. */
    fun enqueueLifecycle(
        destinationId: String,
        localEventKey: String,
        build: (Long, String) -> JSONObject,
    ): Long = writableDatabase.inTransactionResult {
            val eventKey = "$destinationId:$localEventKey"
            rawQuery(
                "SELECT id FROM metric_outbox WHERE local_event_key=?",
                arrayOf(eventKey),
            ).use {
                if (it.moveToFirst()) return@inTransactionResult it.getLong(0)
            }
            val sequence = (queryValue("source_sequence")?.toLongOrNull() ?: 0L) + 1
            val messageId = UUID.randomUUID().toString()
            val payload = build(sequence, messageId).toString()
            val row = insertOrThrow("metric_outbox", null, ContentValues().apply {
                put("destination_id", destinationId)
                put("local_event_key", eventKey)
                put("message_id", messageId)
                put("source_sequence", sequence)
                put("kind", JSONObject(payload).getString("kind"))
                put("payload", payload)
                put("created_at", Instant.now().toString())
            })
            putValue("source_sequence", sequence.toString())
            row
        }

    fun nextSequence(): Long = writableDatabase.inTransactionResult {
        val next = (queryValue("source_sequence")?.toLongOrNull() ?: 0L) + 1
        putValue("source_sequence", next.toString())
        next
    }

    fun pendingMetrics(destinationId: String, limit: Int = 20): List<PendingMetric> =
        readableDatabase.rawQuery(
            "SELECT id,destination_id,payload,attempts FROM metric_outbox " +
                "WHERE destination_id=? AND state='pending' ORDER BY source_sequence LIMIT ?",
            arrayOf(destinationId, limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(PendingMetric(
                    cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3),
                ))
            }
        }

    fun acknowledgeMetric(rowId: Long) {
        // Keep the transition journal row after delivery so process-death recovery cannot
        // manufacture a second lifecycle event after the server acknowledged the first.
        writableDatabase.update("metric_outbox", ContentValues().apply {
            put("state", "acknowledged")
            putNull("last_error")
        }, "id=?", arrayOf(rowId.toString()))
    }

    fun failMetric(rowId: Long, state: String, error: String) {
        require(state in setOf("pending", "blocked", "unauthorized"))
        writableDatabase.update("metric_outbox", ContentValues().apply {
            put("state", state)
            put("last_error", error)
        }, "id=?", arrayOf(rowId.toString()))
        writableDatabase.execSQL("UPDATE metric_outbox SET attempts=attempts+1 WHERE id=?", arrayOf(rowId))
    }

    fun resumeUnauthorized(destinationId: String) {
        writableDatabase.execSQL(
            "UPDATE metric_outbox SET state='pending',last_error=NULL WHERE destination_id=? AND state='unauthorized'",
            arrayOf(destinationId),
        )
        writableDatabase.delete("channel_state", "scope=?", arrayOf(destinationId))
    }

    fun pauseChannel(scope: String, channel: String) {
        writableDatabase.execSQL("INSERT INTO channel_state(scope,channel,paused) VALUES (?,?,1) ON CONFLICT(scope,channel) DO UPDATE SET paused=1", arrayOf(scope, channel))
    }

    fun deferChannel(scope: String, channel: String, retryAt: Long) {
        writableDatabase.execSQL("INSERT INTO channel_state(scope,channel,retry_at) VALUES (?,?,?) ON CONFLICT(scope,channel) DO UPDATE SET retry_at=MAX(retry_at,excluded.retry_at)", arrayOf<Any>(scope, channel, retryAt))
    }

    fun channelReady(scope: String, channel: String, now: Long = System.currentTimeMillis()): Boolean =
        readableDatabase.rawQuery("SELECT paused,retry_at FROM channel_state WHERE scope=? AND channel=?", arrayOf(scope, channel)).use {
            !it.moveToFirst() || (it.getInt(0) == 0 && it.getLong(1) <= now)
        }

    fun retryDelay(scope: String, channel: String, now: Long = System.currentTimeMillis()): Long =
        readableDatabase.rawQuery("SELECT retry_at FROM channel_state WHERE scope=? AND channel=?", arrayOf(scope, channel)).use {
            if (it.moveToFirst()) (it.getLong(0) - now).coerceAtLeast(0) else 0
        }

    fun setStatus(destinationId: String, column: String, status: String, error: String? = null) {
        require(column in setOf("metrics_status", "realtime_status", "analytics_status", "charger_analytics_status", "gps_status"))
        saveDestination(destinationId, null, null)
        writableDatabase.update("destination", ContentValues().apply {
            put(column, status)
            if (error == null) putNull("last_error") else put("last_error", error)
        }, "destination_id=?", arrayOf(destinationId))
        val errorKey = "status_error:$destinationId:$column"
        if (error == null) writableDatabase.delete("metadata", "key=?", arrayOf(errorKey))
        else writableDatabase.putValue(errorKey, error)
    }

    fun statuses(destinationId: String): List<String?> = readableDatabase.rawQuery(
        "SELECT metrics_status,realtime_status,analytics_status,charger_analytics_status,gps_status,last_error FROM destination WHERE destination_id=?",
        arrayOf(destinationId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) listOf("Not tested", "Not tested", "Not approved", "Not approved", "Off", null)
        else (0..5).map { if (cursor.isNull(it)) null else cursor.getString(it) }.toMutableList().apply {
            serverId(destinationId)?.let { server ->
                val scope = deliveryScopeId(destinationId, server)
                for ((index, table) in listOf(0 to "metric_outbox", 2 to "archive_delivery", 3 to "charger_file_delivery")) {
                    readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE destination_id=? AND state='blocked'", arrayOf(scope)).use {
                        if (it.moveToFirst() && it.getInt(0) > 0) this[index] = "Blocked: ${it.getInt(0)} item(s)"
                    }
                }
            }
            this[5] = readableDatabase.rawQuery("SELECT value FROM metadata WHERE key LIKE ? ORDER BY key", arrayOf("status_error:$destinationId:%")).use {
                buildList { while (it.moveToNext()) add(it.getString(0)) }.joinToString("; ").ifEmpty { null }
            }
        }
    }

    fun clearArchiveAcknowledgements(destinationId: String) {
        writableDatabase.delete("archive_delivery", "destination_id=?", arrayOf(destinationId))
        writableDatabase.delete("charger_file_delivery", "destination_id=?", arrayOf(destinationId))
    }

    fun chargerFileNeedsUpload(destinationId: String, sourceId: String, upload: ChargerFileUpload): Boolean =
        readableDatabase.rawQuery(
            "SELECT digest,state FROM charger_file_delivery WHERE destination_id=? AND source_id=? AND file_id=?",
            arrayOf(destinationId, sourceId, upload.fileId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) true
            else if (cursor.getString(1) == "blocked") false
            else cursor.getString(1) != "acknowledged" || cursor.getString(0) != upload.digestBase64
        }

    fun acknowledgeChargerFile(destinationId: String, sourceId: String, upload: ChargerFileUpload) =
        recordChargerFile(destinationId, sourceId, upload, "acknowledged", null)

    fun blockChargerFile(destinationId: String, sourceId: String, upload: ChargerFileUpload, error: String) =
        recordChargerFile(destinationId, sourceId, upload, "blocked", error)

    private fun recordChargerFile(
        destinationId: String,
        sourceId: String,
        upload: ChargerFileUpload,
        state: String,
        error: String?,
    ) {
        writableDatabase.insertWithOnConflict("charger_file_delivery", null, ContentValues().apply {
            put("destination_id", destinationId); put("source_id", sourceId); put("file_id", upload.fileId)
            put("digest", upload.digestBase64); put("file_path", upload.file.absolutePath); put("state", state)
            if (error == null) putNull("last_error") else put("last_error", error)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun archiveNeedsUpload(
        destinationId: String,
        sourceId: String,
        upload: TelemetryPartitionUpload,
    ): Boolean = readableDatabase.rawQuery(
        "SELECT generation,logical_digest,final,state FROM archive_delivery " +
            "WHERE destination_id=? AND source_id=? AND partition_sequence=?",
        arrayOf(destinationId, sourceId, upload.partitionSequence.toString()),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use true
        // Permanent conflicts require a deliberate resync; automatic triggers cannot un-block them.
        if (cursor.getString(3) == "blocked") return@use false
        if (cursor.getString(3) != "acknowledged") return@use true
        val generation = cursor.getInt(0)
        val digest = cursor.getString(1)
        val final = cursor.getInt(2) != 0
        when {
            final -> upload.final && (upload.generation != generation || upload.uncompressedDigestBase64 != digest)
            upload.generation > generation -> true
            upload.generation < generation -> false
            upload.uncompressedDigestBase64 != digest -> true
            upload.final -> true
            else -> false
        }
    }

    fun acknowledgeArchive(
        destinationId: String,
        sourceId: String,
        upload: TelemetryPartitionUpload,
    ) = recordArchive(destinationId, sourceId, upload, "acknowledged", null)

    fun recordArchiveFailure(
        destinationId: String,
        sourceId: String,
        upload: TelemetryPartitionUpload,
        error: String,
    ) = recordArchive(destinationId, sourceId, upload, "blocked", error)

    private fun recordArchive(
        destinationId: String,
        sourceId: String,
        upload: TelemetryPartitionUpload,
        state: String,
        error: String?,
    ) {
        writableDatabase.insertWithOnConflict("archive_delivery", null, ContentValues().apply {
            put("destination_id", destinationId)
            put("source_id", sourceId)
            put("partition_sequence", upload.partitionSequence)
            put("generation", upload.generation)
            put("logical_digest", upload.uncompressedDigestBase64)
            put("compressed_digest", upload.compressedDigestBase64)
            put("final", if (upload.final) 1 else 0)
            put("file_path", upload.file.absolutePath)
            put("state", state)
            if (error == null) putNull("last_error") else put("last_error", error)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun SQLiteDatabase.queryValue(key: String): String? = rawQuery(
        "SELECT value FROM metadata WHERE key=?", arrayOf(key),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun SQLiteDatabase.putValue(key: String, value: String) {
        insertWithOnConflict("metadata", null, ContentValues().apply {
            put("key", key); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private inline fun <T> SQLiteDatabase.inTransactionResult(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        return try { block().also { setTransactionSuccessful() } } finally { endTransaction() }
    }

    companion object {
        @Volatile private var instance: ApiDatabase? = null
        fun get(context: Context): ApiDatabase = instance ?: synchronized(this) {
            instance ?: ApiDatabase(context.applicationContext).also { instance = it }
        }
    }
}

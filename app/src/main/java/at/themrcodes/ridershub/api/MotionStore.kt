package at.themrcodes.ridershub.api

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class MotionRecord(val id: String, val payload: String)

/** Private recording journal and consent, independent of ride/charger archives. */
internal class MotionStore(context: Context) : SQLiteOpenHelper(context, "motion_recordings.db", null, 1) {
    private val prefs = context.getSharedPreferences("motion_recording", Context.MODE_PRIVATE)
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE batch (id TEXT PRIMARY KEY, scope TEXT NOT NULL, payload TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending', recorded_order INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX motion_pending ON batch(scope,state,recorded_order)")
        db.execSQL("CREATE TABLE quota (id INTEGER PRIMARY KEY CHECK(id=1), bytes INTEGER NOT NULL)")
        db.execSQL("INSERT INTO quota VALUES (1,0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun approved(scope: String) = prefs.getBoolean("approved:$scope", false)
    fun approve(scope: String, value: Boolean) { prefs.edit().putBoolean("approved:$scope", value).commit() }
    fun status(scope: String) = prefs.getString("status:$scope", "Off") ?: "Off"
    fun status(scope: String, value: String) { prefs.edit().putString("status:$scope", value).apply() }

    fun record(scope: String, id: String, payload: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val bytes = payload.toByteArray(Charsets.UTF_8).size
            val total = db.rawQuery("SELECT bytes FROM quota WHERE id=1", null).use { it.moveToFirst(); it.getLong(0) }
            check(total + bytes <= MAX_RECORDING_BYTES) { "Motion recording storage is full" }
            db.insertOrThrow("batch", null, ContentValues().apply {
                put("id", id); put("scope", scope); put("payload", payload)
                put("recorded_order", System.currentTimeMillis())
            })
            db.execSQL("UPDATE quota SET bytes=bytes+? WHERE id=1", arrayOf(bytes))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun pending(scope: String): List<MotionRecord> = readableDatabase.rawQuery(
        "SELECT id,payload FROM batch WHERE scope=? AND state='pending' ORDER BY recorded_order,rowid LIMIT 20", arrayOf(scope),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(MotionRecord(cursor.getString(0), cursor.getString(1))) } }
    fun mark(id: String, state: String) {
        require(state in setOf("acknowledged", "blocked"))
        writableDatabase.execSQL("UPDATE batch SET state=? WHERE id=?", arrayOf(state, id))
    }
    companion object {
        private const val MAX_RECORDING_BYTES = 512L * 1024 * 1024
        @Volatile private var instance: MotionStore? = null
        fun get(context: Context): MotionStore = instance ?: synchronized(this) {
            instance ?: MotionStore(context.applicationContext).also { instance = it }
        }
    }
}

package at.themrcodes.ridershub.storage

import android.content.Context
import android.annotation.SuppressLint
import at.themrcodes.ridershub.GeneralSettingsStore
import at.themrcodes.ridershub.isPlausibleBoardTelemetry
import at.themrcodes.ridershub.protocol.TelemetryFrame
import at.themrcodes.ridershub.session.RideSummary
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Local-only reminder state. Never added to telemetry, exports, API payloads or device backups. */
internal class StorageModeStore(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("storage_mode", Context.MODE_PRIVATE)
    private val settings = GeneralSettingsStore(this.context)
    private val reading = StorageReadingWindow()
    private var travel = StorageTravelWindow()
    private var lastRideWrite = 0L
    private var state = decodeStorageState(preferences.getString("state", null))

    @Synchronized fun snapshot(): StorageState = state

    @Synchronized fun selectBoard(boardId: String, inactivitySince: Long = System.currentTimeMillis()) {
        if (state.boardId == boardId) return
        reading.reset()
        travel = StorageTravelWindow()
        update(StorageState(boardId = boardId, inactivitySince = inactivitySince))
    }

    @Synchronized fun answer(enable: Boolean, now: Long = System.currentTimeMillis()) {
        if (!state.offerDue(now)) return
        reading.reset()
        update(if (enable) StoragePolicy.enable(state, now) else StoragePolicy.decline(state), now)
    }

    @Synchronized fun onTelemetry(ride: RideSummary, frame: TelemetryFrame, at: Long, elapsed: Long) {
        val board = ride.localBoardId ?: return
        selectBoard(board, at)
        val valid = isPlausibleBoardTelemetry(frame.boardBatteryPercent, frame.packVoltageV, frame.crcValid)
        val distance = travel.observe(ride.id, frame.speedKmh, valid, elapsed)
        if (valid && distance > 0.1) {
            if (state.enabled || state.declined || at - lastRideWrite >= 60_000) {
                lastRideWrite = at
                reading.reset()
                update(StoragePolicy.ride(state, distance, at))
            }
            return
        }
        if (state.enabled && reading.accept(frame.boardBatteryPercent, frame.speedKmh, valid, elapsed)) {
            update(StoragePolicy.observe(state, StorageObservation(at, frame.boardBatteryPercent), settings.snapshot().lowBatteryWarningPercent))
        }
    }

    @Synchronized fun evaluate(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        var next = state
        if (storageCanNotify(next, now, zone)) {
            if (StorageNotifications.post(context, requireNotNull(next.reminder(now)))) {
                next = next.copy(lastNotificationDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString())
            }
        }
        if (next.enabled && next.nextEvaluationAt?.let { now >= it } == true) {
            next = next.copy(nextEvaluationAt = now + STORAGE_WEEK_MS)
        }
        update(next, now)
        StorageReminderReceiver.schedule(context, state, now + 1)
    }

    private fun update(next: StorageState, now: Long = System.currentTimeMillis()) {
        if (next == state) return
        val clearNotification = state.reminder(now) != next.reminder(now)
        state = next
        preferences.edit().putString("state", encodeStorageState(next)).apply()
        if (clearNotification) StorageNotifications.cancel(context)
        StorageReminderReceiver.schedule(context, state, now)
    }

    companion object {
        // Only the application context is retained; no Activity or View is stored.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: StorageModeStore? = null
        fun get(context: Context): StorageModeStore = instance ?: synchronized(this) {
            instance ?: StorageModeStore(context).also { instance = it }
        }
    }
}

internal fun encodeStorageState(state: StorageState): String = JSONObject().apply {
    put("version", 1)
    put("board", state.boardId)
    put("inactive_since", state.inactivitySince)
    put("declined", state.declined)
    put("requested_at", state.requestedAt)
    put("observations", JSONArray().apply { state.observations.forEach { put(JSONObject().put("at", it.at).put("percent", it.percent)) } })
    put("loss_per_day", state.lossPerDay)
    put("charge_due_at", state.chargeDueAt)
    put("zero_at", state.estimatedZeroAt)
    put("cycle_started_at", state.cycleStartedAt)
    put("next_evaluation_at", state.nextEvaluationAt)
    put("last_notification_date", state.lastNotificationDate)
}.toString()

internal fun decodeStorageState(encoded: String?): StorageState = runCatching {
    if (encoded == null) return@runCatching StorageState()
    val json = JSONObject(encoded)
    require(json.getInt("version") == 1)
    fun long(key: String): Long? = if (json.isNull(key)) null else json.getLong(key)
    val observations = json.getJSONArray("observations").let { array ->
        List(array.length()) { index -> array.getJSONObject(index).let { StorageObservation(it.getLong("at"), it.getInt("percent")) } }
    }
    require(observations.size <= 24 && observations.all { it.percent in 0..100 })
    require(observations.zipWithNext().all { (a, b) -> b.at > a.at })
    StorageState(
        boardId = json.optString("board").takeIf { it.isNotEmpty() },
        inactivitySince = long("inactive_since"),
        declined = json.optBoolean("declined"),
        requestedAt = long("requested_at"),
        observations = observations,
        lossPerDay = if (json.isNull("loss_per_day")) null else json.getDouble("loss_per_day").also { require(it.isFinite() && it > 0) },
        chargeDueAt = long("charge_due_at"),
        estimatedZeroAt = long("zero_at"),
        cycleStartedAt = long("cycle_started_at"),
        nextEvaluationAt = long("next_evaluation_at"),
        lastNotificationDate = json.optString("last_notification_date").takeIf { it.isNotEmpty() },
    )
}.getOrDefault(StorageState())

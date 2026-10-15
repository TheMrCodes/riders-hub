package at.themrcodes.ridershub.session

import at.themrcodes.ridershub.log.TelemetryArchive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Recover presentation history from verified sessions; analytics replay remains separate. */
internal object ArchiveHistoryRecovery {
    fun recover(directory: File, scratch: File): List<RideSummary> {
        val rides = mutableListOf<RideSummary>()
        var session: Session? = null
        TelemetryArchive().forEachFinalizedRecordWithReference(directory, scratch) { reference, row ->
            when (row.getString("type")) {
                "session_start" -> {
                    check(session == null) { "Unfinished archived session" }
                    session = Session(row, reference)
                }
                "telemetry_frame" -> requireNotNull(session).observe(row)
                "session_end" -> {
                    rides += requireNotNull(session).finish(row)
                    session = null
                }
            }
        }
        check(session == null) { "Unfinished archived session" }
        require(rides.map { it.id }.distinct().size == rides.size) { "Duplicate archived session" }
        return rides.sortedByDescending { Instant.parse(it.startedAt) }
    }

    fun merge(recovered: List<RideSummary>, existing: List<RideSummary>): List<RideSummary> {
        val references = recovered.associateBy { it.id }
        // Retain existing presentation totals; update their archive pointers after a restore.
        val retained = existing.map { ride -> references[ride.id]?.let {
            ride.copy(logFile = it.logFile, localBoardId = ride.localBoardId ?: it.localBoardId)
        } ?: ride }
        return (retained + recovered.filter { it.isTrack }).distinctBy { it.id }
            .sortedByDescending { Instant.parse(it.startedAt) }.take(1_000)
    }

    private class Session(val start: JSONObject, val reference: String) {
        private var boardFrames = 0
        private var lastFrameAt: String? = null
        private var batteryMin: Int? = null
        private var voltageMin: Double? = null
        private var voltageMax: Double? = null
        private var restingStart: Double? = null
        private var restingEnd: Double? = null
        private var odometerStart: Double? = null
        private var odometerEnd: Double? = null
        private val modes = linkedSetOf<String>()

        fun observe(row: JSONObject) {
            if (!row.getBoolean("crc_valid")) return
            val voltage = row.getDouble("pack_voltage_v").takeIf { it in 10.0..70.0 }
            val percent = row.getInt("board_battery_percent").takeIf { it in 0..100 && voltage != null }
            if (percent != null) {
                boardFrames++
                lastFrameAt = row.getString("wall_time")
                batteryMin = batteryMin?.let { minOf(it, percent) } ?: percent
            }
            voltage?.let {
                voltageMin = voltageMin?.let { prior -> minOf(prior, it) } ?: it
                voltageMax = voltageMax?.let { prior -> maxOf(prior, it) } ?: it
                if (row.getDouble("speed_kmh") <= 0.5) {
                    restingStart = restingStart ?: it
                    restingEnd = it
                }
            }
            odometerStart = odometerStart ?: row.getDouble("odometer_km")
            odometerEnd = row.getDouble("odometer_km")
            row.optString("mode").takeIf(String::isNotEmpty)?.let(modes::add)
        }

        fun finish(end: JSONObject): RideSummary {
            require(end.getString("session_id") == start.getString("session_id")) { "Archived session identity mismatch" }
            val startedAt = Instant.parse(start.getString("wall_time"))
            val endedAt = Instant.parse(end.getString("wall_time"))
            require(!endedAt.isBefore(startedAt)) { "Archived session ends before it starts" }
            require(listOf("distance_km", "moving_seconds", "max_speed_kmh").all {
                end.getDouble(it).let { value -> value.isFinite() && value >= 0 }
            }) { "Invalid archived trip totals" }
            val json = JSONObject(end.toString())
                .put("id", start.getString("session_id"))
                .put("started_at", startedAt.toString()).put("ended_at", endedAt.toString())
                .put("last_frame_at", lastFrameAt ?: JSONObject.NULL)
                .put("board_battery_min", batteryMin ?: JSONObject.NULL)
                .put("pack_voltage_min", voltageMin ?: JSONObject.NULL)
                .put("pack_voltage_max", voltageMax ?: JSONObject.NULL)
                .put("resting_voltage_start", restingStart ?: JSONObject.NULL)
                .put("resting_voltage_end", restingEnd ?: JSONObject.NULL)
                .put("odometer_start_km", odometerStart ?: JSONObject.NULL)
                .put("odometer_end_km", odometerEnd ?: JSONObject.NULL)
                .put("board_frame_count", boardFrames)
                .put("modes", JSONArray(modes.toList()))
                .put("log_file", reference).put("active", false)
                .put("local_board_id", pseudonymousBoardId(start.getString("device_address")))
            val ride = summaryFromJson(json)
            require(listOf(ride.distanceKm, ride.movingSeconds, ride.maxSpeedKmh).all { it.isFinite() && it >= 0 }) {
                "Invalid archived trip totals"
            }
            return ride
        }
    }
}

data class ArchiveReplayState(
    val running: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
)

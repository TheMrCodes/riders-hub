package at.themrcodes.ridershub.session

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class StoredVoltageCorrelationBin(
    val localBoardId: String,
    val chargeCycleId: String?,
    val rideId: String,
    val rideStartedAt: String,
    val rideEndedAt: String,
    val bin: VoltageCorrelationBin,
)

/** Local-only durable voltage statistics. Nothing in this database is exported to Home Assistant. */
class VoltageHistoryStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE voltage_correlation_bins (
                local_board_id TEXT NOT NULL,
                charge_cycle_id TEXT,
                ride_id TEXT NOT NULL,
                ride_started_at TEXT NOT NULL,
                ride_ended_at TEXT NOT NULL,
                battery_percent INTEGER NOT NULL,
                speed_bucket_start_kmh INTEGER NOT NULL,
                operating_state TEXT NOT NULL,
                first_observed_at TEXT NOT NULL,
                last_observed_at TEXT NOT NULL,
                sample_count INTEGER NOT NULL,
                pack_voltage_sum_v REAL NOT NULL,
                pack_voltage_squared_sum_v2 REAL NOT NULL,
                pack_voltage_min_v REAL NOT NULL,
                pack_voltage_max_v REAL NOT NULL,
                speed_sum_kmh REAL NOT NULL,
                speed_squared_sum_kmh2 REAL NOT NULL,
                pack_voltage_speed_product_sum REAL NOT NULL,
                load_raw_sum INTEGER NOT NULL,
                load_raw_squared_sum REAL NOT NULL,
                pack_voltage_load_raw_product_sum REAL NOT NULL,
                speed_load_raw_product_sum REAL NOT NULL,
                load_raw_min INTEGER NOT NULL,
                load_raw_max INTEGER NOT NULL,
                odometer_min_km REAL NOT NULL,
                odometer_max_km REAL NOT NULL,
                ride_distance_min_km REAL NOT NULL,
                ride_distance_max_km REAL NOT NULL,
                PRIMARY KEY (
                    ride_id,
                    battery_percent,
                    speed_bucket_start_kmh,
                    operating_state
                )
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX voltage_bins_board_time " +
                "ON voltage_correlation_bins(local_board_id, ride_started_at)",
        )
        database.execSQL(
            "CREATE INDEX voltage_bins_cycle ON voltage_correlation_bins(charge_cycle_id)",
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun recordRide(
        localBoardId: String,
        chargeCycleId: String?,
        ride: RideSummary,
        bins: Collection<VoltageCorrelationBin>,
    ) {
        if (bins.isEmpty()) return
        val endedAt = ride.endedAt ?: ride.lastFrameAt ?: return
        writableDatabase.inTransaction {
            bins.forEach { bin ->
                insertWithOnConflict(
                    TABLE_NAME,
                    null,
                    bin.toContentValues(localBoardId, chargeCycleId, ride, endedAt),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    /** Supplies future estimators; callers should process the ordered result off the UI thread. */
    fun readAll(localBoardId: String? = null): List<StoredVoltageCorrelationBin> {
        val selection = localBoardId?.let { "local_board_id = ?" }
        val arguments = localBoardId?.let { arrayOf(it) }
        return readableDatabase.query(
            TABLE_NAME,
            COLUMNS,
            selection,
            arguments,
            null,
            null,
            "ride_started_at ASC, battery_percent DESC, speed_bucket_start_kmh ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        StoredVoltageCorrelationBin(
                            localBoardId = cursor.getString(cursor.getColumnIndexOrThrow("local_board_id")),
                            chargeCycleId = cursor.getColumnIndexOrThrow("charge_cycle_id").let { index ->
                                if (cursor.isNull(index)) null else cursor.getString(index)
                            },
                            rideId = cursor.getString(cursor.getColumnIndexOrThrow("ride_id")),
                            rideStartedAt = cursor.getString(cursor.getColumnIndexOrThrow("ride_started_at")),
                            rideEndedAt = cursor.getString(cursor.getColumnIndexOrThrow("ride_ended_at")),
                            bin = VoltageCorrelationBin(
                                batteryPercent = cursor.int("battery_percent"),
                                speedBucketStartKmh = cursor.int("speed_bucket_start_kmh"),
                                operatingState = enumValues<VoltageOperatingState>().firstOrNull {
                                    it.name == cursor.string("operating_state")
                                } ?: VoltageOperatingState.MOVING,
                                firstObservedAt = cursor.string("first_observed_at"),
                                lastObservedAt = cursor.string("last_observed_at"),
                                sampleCount = cursor.long("sample_count"),
                                packVoltageSumV = cursor.double("pack_voltage_sum_v"),
                                packVoltageSquaredSumV2 = cursor.double("pack_voltage_squared_sum_v2"),
                                packVoltageMinV = cursor.double("pack_voltage_min_v"),
                                packVoltageMaxV = cursor.double("pack_voltage_max_v"),
                                speedSumKmh = cursor.double("speed_sum_kmh"),
                                speedSquaredSumKmh2 = cursor.double("speed_squared_sum_kmh2"),
                                packVoltageSpeedProductSum =
                                    cursor.double("pack_voltage_speed_product_sum"),
                                loadRawSum = cursor.long("load_raw_sum"),
                                loadRawSquaredSum = cursor.double("load_raw_squared_sum"),
                                packVoltageLoadRawProductSum =
                                    cursor.double("pack_voltage_load_raw_product_sum"),
                                speedLoadRawProductSum = cursor.double("speed_load_raw_product_sum"),
                                loadRawMin = cursor.int("load_raw_min"),
                                loadRawMax = cursor.int("load_raw_max"),
                                odometerMinKm = cursor.double("odometer_min_km"),
                                odometerMaxKm = cursor.double("odometer_max_km"),
                                rideDistanceMinKm = cursor.double("ride_distance_min_km"),
                                rideDistanceMaxKm = cursor.double("ride_distance_max_km"),
                            ),
                        ),
                    )
                }
            }
        }
    }

    companion object {
        private const val DATABASE_NAME = "voltage_history.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "voltage_correlation_bins"
        private val COLUMNS = arrayOf(
            "local_board_id",
            "charge_cycle_id",
            "ride_id",
            "ride_started_at",
            "ride_ended_at",
            "battery_percent",
            "speed_bucket_start_kmh",
            "operating_state",
            "first_observed_at",
            "last_observed_at",
            "sample_count",
            "pack_voltage_sum_v",
            "pack_voltage_squared_sum_v2",
            "pack_voltage_min_v",
            "pack_voltage_max_v",
            "speed_sum_kmh",
            "speed_squared_sum_kmh2",
            "pack_voltage_speed_product_sum",
            "load_raw_sum",
            "load_raw_squared_sum",
            "pack_voltage_load_raw_product_sum",
            "speed_load_raw_product_sum",
            "load_raw_min",
            "load_raw_max",
            "odometer_min_km",
            "odometer_max_km",
            "ride_distance_min_km",
            "ride_distance_max_km",
        )
    }
}

private fun VoltageCorrelationBin.toContentValues(
    localBoardId: String,
    chargeCycleId: String?,
    ride: RideSummary,
    endedAt: String,
): ContentValues = ContentValues().apply {
    put("local_board_id", localBoardId)
    put("charge_cycle_id", chargeCycleId)
    put("ride_id", ride.id)
    put("ride_started_at", ride.startedAt)
    put("ride_ended_at", endedAt)
    put("battery_percent", batteryPercent)
    put("speed_bucket_start_kmh", speedBucketStartKmh)
    put("operating_state", operatingState.name)
    put("first_observed_at", firstObservedAt)
    put("last_observed_at", lastObservedAt)
    put("sample_count", sampleCount)
    put("pack_voltage_sum_v", packVoltageSumV)
    put("pack_voltage_squared_sum_v2", packVoltageSquaredSumV2)
    put("pack_voltage_min_v", packVoltageMinV)
    put("pack_voltage_max_v", packVoltageMaxV)
    put("speed_sum_kmh", speedSumKmh)
    put("speed_squared_sum_kmh2", speedSquaredSumKmh2)
    put("pack_voltage_speed_product_sum", packVoltageSpeedProductSum)
    put("load_raw_sum", loadRawSum)
    put("load_raw_squared_sum", loadRawSquaredSum)
    put("pack_voltage_load_raw_product_sum", packVoltageLoadRawProductSum)
    put("speed_load_raw_product_sum", speedLoadRawProductSum)
    put("load_raw_min", loadRawMin)
    put("load_raw_max", loadRawMax)
    put("odometer_min_km", odometerMinKm)
    put("odometer_max_km", odometerMaxKm)
    put("ride_distance_min_km", rideDistanceMinKm)
    put("ride_distance_max_km", rideDistanceMaxKm)
}

private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try {
        block()
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}

private fun android.database.Cursor.string(column: String): String =
    getString(getColumnIndexOrThrow(column))

private fun android.database.Cursor.int(column: String): Int =
    getInt(getColumnIndexOrThrow(column))

private fun android.database.Cursor.long(column: String): Long =
    getLong(getColumnIndexOrThrow(column))

private fun android.database.Cursor.double(column: String): Double =
    getDouble(getColumnIndexOrThrow(column))

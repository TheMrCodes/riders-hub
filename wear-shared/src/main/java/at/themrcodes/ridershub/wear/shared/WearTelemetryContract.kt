package at.themrcodes.ridershub.wear.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

enum class WearConnectionStatus {
    STANDBY,
    CONNECTING,
    LIVE,
    RECONNECTING,
}

/**
 * Privacy-minimized state shared with a paired Wear OS watch.
 *
 * Device identifiers, names, odometer values, timestamps from ride history, and raw telemetry
 * intentionally aren't part of this contract. Estimated remaining range is derived on the phone
 * from the user's local ride model and is sent only as the current aggregate estimate.
 */
data class WearTelemetryState(
    val connection: WearConnectionStatus,
    val updatedAtEpochMs: Long,
    val speedKmh: Double?,
    val boardBatteryPercent: Int?,
    val tripKm: Double?,
    val estimatedRangeKm: Double?,
    val mode: String?,
) {
    init {
        require(updatedAtEpochMs >= 0)
        require(speedKmh == null || speedKmh.isFinite() && speedKmh in 0.0..MAX_SPEED_KMH)
        require(boardBatteryPercent == null || boardBatteryPercent in 0..100)
        require(tripKm == null || tripKm.isFinite() && tripKm in 0.0..MAX_TRIP_KM)
        require(
            estimatedRangeKm == null ||
                estimatedRangeKm.isFinite() && estimatedRangeKm in 0.0..MAX_RANGE_KM,
        )
        require(mode == null || mode.length <= MAX_MODE_LENGTH)
    }

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(SCHEMA_VERSION)
            output.writeUTF(connection.name)
            output.writeLong(updatedAtEpochMs)
            output.writeOptionalDouble(speedKmh)
            output.writeOptionalInt(boardBatteryPercent)
            output.writeOptionalDouble(tripKm)
            output.writeOptionalDouble(estimatedRangeKm)
            output.writeOptionalString(mode)
        }
        return bytes.toByteArray()
    }

    companion object {
        const val DATA_PATH = "/riders-hub/telemetry"
        private const val MAGIC = 0x52485542
        private const val SCHEMA_VERSION = 2
        private const val LEGACY_SCHEMA_VERSION = 1
        private const val MAX_SPEED_KMH = 200.0
        private const val MAX_TRIP_KM = 1_000_000.0
        private const val MAX_RANGE_KM = 1_000_000.0
        private const val MAX_MODE_LENGTH = 32

        fun decode(bytes: ByteArray): WearTelemetryState = DataInputStream(
            ByteArrayInputStream(bytes),
        ).use { input ->
            require(input.readInt() == MAGIC) { "Unknown Wear telemetry payload" }
            val version = input.readInt()
            require(version == LEGACY_SCHEMA_VERSION || version == SCHEMA_VERSION) {
                "Unsupported Wear telemetry schema $version"
            }
            val connection = WearConnectionStatus.valueOf(input.readUTF())
            val updatedAtEpochMs = input.readLong()
            val speedKmh = input.readOptionalDouble()
            val boardBatteryPercent = input.readOptionalInt()
            val tripKm = input.readOptionalDouble()
            val estimatedRangeKm = if (version >= SCHEMA_VERSION) input.readOptionalDouble() else null
            val state = WearTelemetryState(
                connection = connection,
                updatedAtEpochMs = updatedAtEpochMs,
                speedKmh = speedKmh,
                boardBatteryPercent = boardBatteryPercent,
                tripKm = tripKm,
                estimatedRangeKm = estimatedRangeKm,
                mode = input.readOptionalString(),
            )
            require(input.available() == 0) { "Wear telemetry payload has trailing data" }
            state
        }
    }
}

private fun DataOutputStream.writeOptionalDouble(value: Double?) {
    writeBoolean(value != null)
    if (value != null) writeDouble(value)
}

private fun DataOutputStream.writeOptionalInt(value: Int?) {
    writeBoolean(value != null)
    if (value != null) writeInt(value)
}

private fun DataOutputStream.writeOptionalString(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeUTF(value)
}

private fun DataInputStream.readOptionalDouble(): Double? =
    if (readBoolean()) readDouble() else null

private fun DataInputStream.readOptionalInt(): Int? =
    if (readBoolean()) readInt() else null

private fun DataInputStream.readOptionalString(): String? =
    if (readBoolean()) readUTF() else null

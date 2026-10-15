package at.themrcodes.ridershub.wear.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

enum class WearDashboardMode {
    AMBIENT, LIVE, RACE;

    fun next(): WearDashboardMode = entries[(ordinal + 1) % entries.size]
    val keepsScreenAwake: Boolean get() = this != AMBIENT

    companion object {
        fun restore(saved: String?, legacyKeepAwake: Boolean): WearDashboardMode =
            entries.firstOrNull { it.name == saved } ?: if (legacyKeepAwake) LIVE else AMBIENT
    }
}

data class WearDisplayModeState(val mode: WearDashboardMode) {
    fun encode(): ByteArray = byteArrayOf(1, mode.ordinal.toByte())

    companion object {
        const val DATA_PATH = "/riders-hub/display-mode"
        fun decode(bytes: ByteArray): WearDisplayModeState {
            require(bytes.size == 2 && bytes[0] == 1.toByte())
            return WearDisplayModeState(requireNotNull(WearDashboardMode.entries.getOrNull(bytes[1].toInt())))
        }
    }
}

/** Signed, provisional power only. No raw frames, board identifiers or energy credit. */
data class WearRaceSample(
    val updatedAtEpochMs: Long,
    val speedKmh: Double?,
    val estimatedPowerWatts: Double?,
    val lowBatteryWarningPercent: Int,
) {
    init {
        require(updatedAtEpochMs >= 0)
        require(speedKmh == null || speedKmh.isFinite() && speedKmh in 0.0..200.0)
        // Accommodate the entire signed protocol range, independently of dial clipping.
        require(estimatedPowerWatts == null || estimatedPowerWatts.isFinite() &&
            estimatedPowerWatts in -50_000.0..50_000.0)
        require(lowBatteryWarningPercent in 5..50 && lowBatteryWarningPercent % 5 == 0)
    }

    // Phone/watch clocks may differ. The watch also enforces a monotonic receipt timeout.
    fun isFresh(nowEpochMs: Long): Boolean = (nowEpochMs - updatedAtEpochMs).coerceAtLeast(0) <= FRESHNESS_MS

    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeByte(1)
            out.writeLong(updatedAtEpochMs)
            out.writeBoolean(speedKmh != null)
            speedKmh?.let(out::writeDouble)
            out.writeBoolean(estimatedPowerWatts != null)
            estimatedPowerWatts?.let(out::writeDouble)
            out.writeInt(lowBatteryWarningPercent)
        }
    }.toByteArray()

    companion object {
        const val DATA_PATH = "/riders-hub/race-sample"
        const val MESSAGE_PATH = "/riders-hub/race-live"
        const val FRESHNESS_MS = 3_000L
        private const val MAGIC = 0x52414345

        fun decode(bytes: ByteArray): WearRaceSample = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readUnsignedByte() == 1)
            val sample = WearRaceSample(
                input.readLong(),
                if (input.readBoolean()) input.readDouble() else null,
                if (input.readBoolean()) input.readDouble() else null,
                input.readInt(),
            )
            require(input.available() == 0)
            sample
        }
    }
}

/** Monotonic, no catch-up queues: always send the newest available frame. */
class WearRaceCadence {
    private var lastDurable: Long? = null
    private var lastFast: Long? = null
    private var lastFastRecovery = false

    fun durableDue(elapsedMs: Long): Boolean = due(lastDurable, elapsedMs, 2_000).also {
        if (it) lastDurable = elapsedMs
    }

    // Call only when transport has a free slot. Do not consume a transition that
    // cannot be sent, and never queue an old braking sample behind newer frames.
    fun fastDue(mode: WearDashboardMode, elapsedMs: Long, estimatedPowerWatts: Double? = null): Boolean {
        if (mode != WearDashboardMode.RACE) return false
        val recovery = estimatedPowerWatts != null && estimatedPowerWatts <= -.5
        val send = due(lastFast, elapsedMs, 250) || recovery != lastFastRecovery
        if (send) {
            lastFast = elapsedMs
            lastFastRecovery = recovery
        }
        return send
    }

    private fun due(last: Long?, now: Long, interval: Long): Boolean =
        last == null || now < last || now - last >= interval
}

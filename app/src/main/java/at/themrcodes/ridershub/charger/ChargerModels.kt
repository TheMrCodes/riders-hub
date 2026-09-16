package at.themrcodes.ridershub.charger

enum class ChargerPhase {
    UNPAIRED, ABSENT, CONNECTING, READY, AWAITING_CONFIRMATION, STARTING,
    WAITING_FOR_LOAD, CHARGING, COMPLETING, STOPPING, COMPLETE, ERROR,
}

data class ChargerSnapshot(
    val paired: Boolean,
    val associationId: Int?,
    val associatedDeviceName: String?,
    val present: Boolean,
    val gattConnected: Boolean,
    val rpcVerified: Boolean,
    val phase: ChargerPhase,
    val status: String,
    val outputOn: Boolean?,
    val powerWatts: Double?,
    val peakWatts: Double?,
    val activeSeconds: Long,
    val completionSeconds: Long,
    val appearanceToken: String?,
    val error: String?,
) {
    /** A live GATT link is stronger reachability evidence than a delayed CDM scan callback. */
    val nearby: Boolean get() = present || gattConnected

    val canPrompt: Boolean get() = rpcVerified && phase in setOf(
        ChargerPhase.READY, ChargerPhase.AWAITING_CONFIRMATION, ChargerPhase.COMPLETE,
    )
    val canStop: Boolean get() = rpcVerified && outputOn == true
}

enum class ChargerAction { NONE, ACTIVE_CONFIRMED, NO_LOAD_OFF, COMPLETE_OFF, FAULT_OFF }

data class ChargerObservation(
    val atMs: Long,
    val outputOn: Boolean,
    val powerWatts: Double,
    val temperatureC: Double? = null,
    val overTemperature: Boolean = false,
    val overPower: Boolean = false,
    val overVoltage: Boolean = false,
    val underVoltage: Boolean = false,
)

/** Pure timing policy. Only fresh observations add time; gaps reset completion evidence. */
class ChargerCycleTracker(
    private val activeThresholdW: Double = 10.0,
    private val activeConfirmMs: Long = 30_000,
    private val noLoadTimeoutMs: Long = 5 * 60_000,
    private val completionConfirmMs: Long = 20 * 60_000,
    private val completionAbsoluteW: Double = 5.0,
    private val freshGapMs: Long = 20_000,
) {
    var activeConfirmed = false; private set
    var peakWatts = 0.0; private set
    var activeEvidenceMs = 0L; private set
    var completionEvidenceMs = 0L; private set
    var relayOnEvidenceMs = 0L; private set
    private var last: ChargerObservation? = null
    private var relayOnSinceMs: Long? = null

    fun observe(value: ChargerObservation): ChargerAction {
        require(value.powerWatts.isFinite() && value.powerWatts >= 0.0)
        if (value.outputOn && (value.overTemperature || value.overPower || value.overVoltage || value.underVoltage)) {
            last = value
            return ChargerAction.FAULT_OFF
        }
        val previous = last
        last = value
        if (!value.outputOn) {
            activeEvidenceMs = 0
            completionEvidenceMs = 0
            relayOnEvidenceMs = 0
            relayOnSinceMs = null
            return ChargerAction.NONE
        }
        if (previous?.outputOn != true) relayOnSinceMs = value.atMs
        peakWatts = maxOf(peakWatts, value.powerWatts)
        val elapsed = previous?.takeIf { it.outputOn }?.let { value.atMs - it.atMs }
            ?.takeIf { it in 0..freshGapMs } ?: 0L
        if (elapsed == 0L && previous != null) {
            completionEvidenceMs = 0
            activeEvidenceMs = 0
        }
        relayOnEvidenceMs += elapsed
        activeEvidenceMs = if (value.powerWatts >= activeThresholdW) activeEvidenceMs + elapsed else 0L
        if (!activeConfirmed && activeEvidenceMs >= activeConfirmMs) {
            activeConfirmed = true
            return ChargerAction.ACTIVE_CONFIRMED
        }
        if (!activeConfirmed && value.atMs - requireNotNull(relayOnSinceMs) >= noLoadTimeoutMs) {
            return ChargerAction.NO_LOAD_OFF
        }
        val relativeThreshold = peakWatts * 0.10
        val completePower = minOf(completionAbsoluteW, relativeThreshold)
        completionEvidenceMs = if (activeConfirmed && value.powerWatts <= completePower) {
            completionEvidenceMs + elapsed
        } else 0L
        return if (completionEvidenceMs >= completionConfirmMs) ChargerAction.COMPLETE_OFF else ChargerAction.NONE
    }
}

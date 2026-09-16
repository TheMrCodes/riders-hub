package at.themrcodes.ridershub.session

import org.json.JSONObject
import kotlin.math.abs

/** Only the board's estimate/reference survive disconnects; elapsed-time evidence never does. */
internal data class BatteryEstimateState(val percent: Double, val referencePercent: Int) {
    init {
        require(percent.isFinite() && percent in 0.0..100.0)
        require(referencePercent in 0..100)
    }

    fun toJson(): JSONObject = JSONObject().put("version", 1)
        .put("percent", percent).put("reference_percent", referencePercent)

    companion object {
        fun fromJson(value: JSONObject): BatteryEstimateState? = runCatching {
            if (value.optInt("version") != 1) return null
            BatteryEstimateState(value.getDouble("percent"), value.getInt("reference_percent"))
        }.getOrNull()
    }
}

/** Debounce observations independently of model consumption and persisted state. */
internal class BatteryObservationTracker {
    private var candidate: Int? = null
    private var candidateSeconds = 0.0
    private var settledVoltage: Double? = null
    private var settledSeconds = 0.0
    private var reacquiring = true

    fun observe(
        state: BatteryEstimateState?,
        reportedPercent: Int,
        elapsedSeconds: Double,
        distanceKm: Double,
        batteryPercentPerKm: Double?,
        voltageV: Double,
        loadRaw: Int,
    ): BatteryEstimateState = observeWithDiagnostics(state, reportedPercent, elapsedSeconds,
        distanceKm, batteryPercentPerKm, voltageV, loadRaw).state

    fun observeWithDiagnostics(
        state: BatteryEstimateState?, reportedPercent: Int, elapsedSeconds: Double,
        distanceKm: Double, batteryPercentPerKm: Double?, voltageV: Double, loadRaw: Int,
    ): BatteryObservationResult {
        require(reportedPercent in 0..100)
        val current = state ?: BatteryEstimateState(reportedPercent.toDouble(), reportedPercent)
        val continuous = elapsedSeconds.isFinite() && elapsedSeconds > 0.0 && elapsedSeconds <= 2.0 &&
            distanceKm.isFinite() && distanceKm >= 0.0 && voltageV.isFinite() && voltageV in 10.0..70.0
        if (!continuous) {
            candidate = null
            candidateSeconds = 0.0
            settledVoltage = null
            settledSeconds = 0.0
            reacquiring = true
            return BatteryObservationResult(current, BatteryObservationDiagnostics(
                status = if (state == null) "initialized" else "held_discontinuity",
                previousPercent = state?.percent, previousReferencePercent = state?.referencePercent,
                referencePercent = current.referencePercent, reportedPercent = reportedPercent, candidatePercent = null,
                candidateSeconds = 0.0, settledSeconds = 0.0, settledVoltageV = null,
                confirmed = false, settled = false, reacquiring = false, reacquiringAfter = true,
                consumptionPercent = 0.0, correctionPercent = 0.0,
            ))
        }
        if (candidate != reportedPercent) {
            candidate = reportedPercent
            candidateSeconds = 0.0
        } else candidateSeconds += elapsedSeconds
        // Raw input is a protocol candidate, not amperes. This heuristic only controls correction
        // speed; neither voltage nor input is converted into an absolute charge measurement.
        if (abs(loadRaw.toLong()) > 50L) {
            settledVoltage = null
            settledSeconds = 0.0
        } else if (settledVoltage == null || abs(voltageV - requireNotNull(settledVoltage)) > 0.2) {
            settledVoltage = voltageV
            settledSeconds = 0.0
        } else settledSeconds += elapsedSeconds
        val confirmed = candidateSeconds >= 3.0
        val reference = if (confirmed) reportedPercent else current.referencePercent
        val settled = confirmed && settledSeconds >= 3.0
        val reacquiringUsed = confirmed && reacquiring
        val step = BatteryPercentEstimator.step(current.percent, reference, elapsedSeconds, distanceKm,
            batteryPercentPerKm, settled, reacquiringUsed)
        val result = BatteryEstimateState(step.percent, reference)
        if (confirmed && abs(result.percent - reference) <= 2.6) reacquiring = false
        return BatteryObservationResult(result, BatteryObservationDiagnostics(
            status = if (state == null) "initialized" else "updated",
            previousPercent = state?.percent, previousReferencePercent = state?.referencePercent,
            referencePercent = reference, reportedPercent = reportedPercent, candidatePercent = candidate,
            candidateSeconds = candidateSeconds, settledSeconds = settledSeconds,
            settledVoltageV = settledVoltage, confirmed = confirmed, settled = settled,
            reacquiring = reacquiringUsed, reacquiringAfter = reacquiring,
            consumptionPercent = step.consumptionPercent, correctionPercent = step.observationCorrectionPercent,
        ))
    }
}

/** Independent per-board durable state; malformed entries do not discard other boards. */
internal class BatteryEstimateRegistry(encoded: String = "{}") {
    private val states = mutableMapOf<String, BatteryEstimateState>()
    init {
        val json = runCatching { JSONObject(encoded) }.getOrDefault(JSONObject())
        json.keys().forEach { id ->
            json.optJSONObject(id)?.let(BatteryEstimateState::fromJson)?.let { states[id] = it }
        }
    }

    fun state(boardId: String): BatteryEstimateState? = states[boardId]
    fun record(boardId: String, state: BatteryEstimateState) { states[boardId] = state }
    fun toJson(): JSONObject = JSONObject().apply { states.forEach { (id, state) -> put(id, state.toJson()) } }
}

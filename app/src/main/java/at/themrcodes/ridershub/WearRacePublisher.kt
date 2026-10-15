package at.themrcodes.ridershub

import android.content.Context
import android.os.SystemClock
import at.themrcodes.ridershub.protocol.TelemetryFrame
import at.themrcodes.ridershub.session.BatteryFrameDiagnostics
import at.themrcodes.ridershub.session.ProvisionalPowerEstimator
import at.themrcodes.ridershub.wear.shared.WearDashboardMode
import at.themrcodes.ridershub.wear.shared.WearDisplayModeState
import at.themrcodes.ridershub.wear.shared.WearRaceCadence
import at.themrcodes.ridershub.wear.shared.WearRaceSample
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable

/** Each watch owns its mode; a second Ambient watch must not enable fast streaming. */
internal class WearDisplayModeStore(context: Context) {
    private val preferences = context.getSharedPreferences("wear_display_modes", Context.MODE_PRIVATE)
    fun set(node: String, mode: WearDashboardMode?) = synchronized(lock) {
        preferences.edit().apply {
            if (mode == null) remove(node) else putString(node, mode.name)
        }.apply()
        revision++
    }
    fun mode(node: String): WearDashboardMode = WearDashboardMode.restore(preferences.getString(node, null), false)
    fun revision(): Long = synchronized(lock) { revision }
    fun bootstrap(values: Map<String, WearDashboardMode>, expectedRevision: Long) = synchronized(lock) {
        if (revision != expectedRevision) return@synchronized
        preferences.edit().clear().apply { values.forEach { (node, mode) -> putString(node, mode.name) } }.apply()
        revision++
    }
    private companion object {
        val lock = Any()
        var revision = 0L
    }
}

internal class WearRacePublisher(context: Context) {
    private val app = context.applicationContext
    private val dataClient by lazy { Wearable.getDataClient(app) }
    private val messageClient by lazy { Wearable.getMessageClient(app) }
    private val modes = WearDisplayModeStore(app)
    private val settings = GeneralSettingsStore(app)
    private val cadence = WearRaceCadence()
    @Volatile private var nodes = emptyList<String>()
    @Volatile private var discovering = false
    private var lastDiscoveryMs: Long? = null
    private val inFlight = mutableSetOf<String>()
    private var modesRestored = false

    @Synchronized
    fun publish(frame: TelemetryFrame, diagnostics: BatteryFrameDiagnostics, atEpochMs: Long) {
        val elapsed = SystemClock.elapsedRealtime()
        refreshNodes(elapsed)
        val durable = cadence.durableDue(elapsed)
        // Evaluate on every frame, even when transport is throttled.
        val valid = isPlausibleBoardTelemetry(frame.boardBatteryPercent, frame.packVoltageV, frame.crcValid)
        val power = ProvisionalPowerEstimator.estimate(
            frame.packVoltageV, diagnostics.loadEffectiveRaw, frame.crcValid, frame.speedKmh).takeIf { valid }
        val targets = nodes.filter { modes.mode(it) == WearDashboardMode.RACE && it !in inFlight }
        val fast = targets.isNotEmpty() && cadence.fastDue(WearDashboardMode.RACE, elapsed, power)
        if (!durable && !fast) return
        val sample = WearRaceSample(
            atEpochMs,
            frame.speedKmh.takeIf { valid && it.isFinite() && it in 0.0..200.0 },
            power,
            settings.snapshot().lowBatteryWarningPercent,
        )
        val bytes = sample.encode()
        if (durable) runCatching {
            dataClient.putDataItem(PutDataRequest.create(WearRaceSample.DATA_PATH).setData(bytes).setUrgent())
                .addOnFailureListener { /* Retry with a fresh sample at the next durable interval. */ }
        }
        if (fast) targets.forEach { node ->
            if (inFlight.add(node)) {
                runCatching {
                    messageClient.sendMessage(node, WearRaceSample.MESSAGE_PATH, bytes)
                        .addOnCompleteListener { synchronized(this) { inFlight.remove(node) } }
                }.onFailure { inFlight.remove(node) }
            }
        }
    }

    private fun refreshNodes(elapsed: Long) {
        if (discovering || lastDiscoveryMs?.let { elapsed - it < 10_000 } == true) return
        discovering = true
        lastDiscoveryMs = elapsed
        if (!modesRestored) {
            modesRestored = true
            val revision = modes.revision()
            runCatching {
                dataClient.dataItems.addOnSuccessListener { items ->
                    try {
                        val values = items.asSequence().filter { it.uri.path == WearDisplayModeState.DATA_PATH }
                            .mapNotNull { item ->
                                val node = item.uri.host ?: return@mapNotNull null
                                val mode = runCatching { WearDisplayModeState.decode(item.data ?: return@mapNotNull null).mode }
                                    .getOrNull() ?: return@mapNotNull null
                                node to mode
                            }.toMap()
                        modes.bootstrap(values, revision)
                    } finally { items.release() }
                }.addOnFailureListener { synchronized(this) { modesRestored = false } }
            }.onFailure { modesRestored = false }
        }
        runCatching {
            Wearable.getNodeClient(app).connectedNodes
                .addOnSuccessListener { nodes = it.map { node -> node.id } }
                .addOnFailureListener { nodes = emptyList() }
                .addOnCompleteListener { discovering = false }
        }.onFailure { discovering = false }
    }
}

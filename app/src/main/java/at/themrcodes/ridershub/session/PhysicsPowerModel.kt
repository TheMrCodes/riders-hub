package at.themrcodes.ridershub.session

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Physics-informed electrical power model, learned per board from aligned frames.
 *
 * ```text
 * w(t)   = v (r + k v^2 + a + g s)                 mechanical power per unit mass
 * P(t)   = m max(w, 0) - eta m max(-w, 0) + P0     electrical power, raw load x voltage units
 * ```
 *
 * The load channel reports only drive current, so the frame fit is the hinge
 * `load x V = max(c_r v + c_a v^3 + m v a, 0) + P0` over all moving frames.
 *
 * `r` (rolling), `k` (aerodynamic), the idle ratio `P0 / m` and the round-trip recovery `eta`
 * are scale-free. The unverified raw-load scale and the mass cancel, so battery percentage is
 * one depletion-window scale on per-mass work. Over a window the identity
 * `integral(max(w, 0)) = integral(w) + integral(max(-w, 0))` gives
 *
 * ```text
 * work / m = cruise(r, k, idle; speed buckets) + delta kinetic + g delta height + (1 - eta) braking
 * ```
 *
 * so stop-and-go and hills enter only through braked work, which recuperation partly returns.
 *
 * The signed load channel reports drive current but not charge current, so `eta` is derived
 * from pack voltage: braking voltage above the tracked open-circuit voltage implies charging.
 */
internal object PhysicsPowerConstants {
    const val GRAVITY = 9.81
    /** Centred speed smoothing; archive validation selected three seconds at about 10 Hz. */
    const val SMOOTHING_HALF_FRAMES = 15
    /** The load channel trails speed-derived acceleration by about 0.4 s. */
    const val LOAD_LAG_FRAMES = 4
    const val MAX_FRAME_GAP_MS = 500L
    const val MIN_FIT_SPEED_MPS = 1.0
    const val MIN_DRIVE_SAMPLES = 3_000L
    const val MIN_BRAKING_SAMPLES = 200L
    const val MIN_REGEN_ACCEL_MPS2 = -0.3
    const val MIN_REGEN_SPEED_MPS = 3.0
    const val MIN_RESISTANCE_STEP_RAW = 20.0
    const val OCV_TIME_CONSTANT_S = 30.0
    const val RESISTANCE_DETREND_TIME_CONSTANT_S = 60.0
    const val GRADE_TIME_CONSTANT_S = 5.0
    const val GRADE_DECAY_TIME_CONSTANT_S = 30.0
    const val PRESSURE_TIME_CONSTANT_S = 3.0
    const val MAX_PRESSURE_AGE_MS = 3_000L
    const val MAX_GRADE = 0.2
    const val HEIGHT_HYSTERESIS_M = 2.0
    const val MAX_RECOVERY_FRACTION = 0.9
    /**
     * Bootstrap shape only for classifying samples before the first refit (Crr about 0.025,
     * 0.5 rho CdA / m about 0.002 per metre). It never enters a consumption rate.
     */
    const val BOOTSTRAP_ROLLING_MPS2 = 0.25
    const val BOOTSTRAP_AERO_PER_M = 0.002
}

/** Per-mass motion work accumulated into one depletion window. */
data class WindowMotion(
    val netKineticJPerKg: Double = 0.0,
    val netHeightM: Double = 0.0,
    val brakingStopGoJPerKg: Double = 0.0,
    val brakingDescentJPerKg: Double = 0.0,
    val climbM: Double = 0.0,
    val descentM: Double = 0.0,
    val alignedSeconds: Double = 0.0,
    val barometricSeconds: Double = 0.0,
) {
    operator fun plus(other: WindowMotion) = WindowMotion(
        netKineticJPerKg + other.netKineticJPerKg,
        netHeightM + other.netHeightM,
        brakingStopGoJPerKg + other.brakingStopGoJPerKg,
        brakingDescentJPerKg + other.brakingDescentJPerKg,
        climbM + other.climbM,
        descentM + other.descentM,
        alignedSeconds + other.alignedSeconds,
        barometricSeconds + other.barometricSeconds,
    )

    val isEmpty: Boolean get() = alignedSeconds <= 0.0

    fun scaled(weight: Double) = WindowMotion(
        netKineticJPerKg * weight, netHeightM * weight, brakingStopGoJPerKg * weight,
        brakingDescentJPerKg * weight, climbM * weight, descentM * weight,
        alignedSeconds * weight, barometricSeconds * weight,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("net_kinetic_j_per_kg", netKineticJPerKg)
        .put("net_height_m", netHeightM)
        .put("braking_stop_go_j_per_kg", brakingStopGoJPerKg)
        .put("braking_descent_j_per_kg", brakingDescentJPerKg)
        .put("climb_m", climbM)
        .put("descent_m", descentM)
        .put("aligned_seconds", alignedSeconds)
        .put("barometric_seconds", barometricSeconds)

    companion object {
        val ZERO = WindowMotion()

        fun fromJson(value: JSONObject?): WindowMotion {
            value ?: return ZERO
            fun finite(key: String, nonNegative: Boolean = true) =
                value.optDouble(key, 0.0).takeIf { it.isFinite() && (!nonNegative || it >= 0.0) } ?: 0.0
            return WindowMotion(
                finite("net_kinetic_j_per_kg", nonNegative = false),
                finite("net_height_m", nonNegative = false),
                finite("braking_stop_go_j_per_kg"),
                finite("braking_descent_j_per_kg"),
                finite("climb_m"),
                finite("descent_m"),
                finite("aligned_seconds"),
                finite("barometric_seconds"),
            )
        }
    }
}

/** One centred sample: smoothed speed, its derivative, and the lagged load/voltage. */
internal data class AlignedFrame(
    val dtS: Double,
    val speedMps: Double,
    val previousSpeedMps: Double,
    val accelMps2: Double,
    /** Voltage at the speed sample, paired with the lagged load for resistance/OCV tracking. */
    val voltageV: Double,
    /** Voltage and effective raw load at the lagged index, the electrical power sample. */
    val loadVoltageV: Double,
    val loadRaw: Double,
    val pressureHPa: Double?,
)

/** Fixed-delay centred smoother; emits a sample once its future context is available. */
internal class PhysicsFrameAligner {
    private class Raw(val atMs: Long, val speedMps: Double, val voltageV: Double, val loadRaw: Double,
        val pressureHPa: Double?)

    private val buffer = ArrayDeque<Raw>()
    /** Set when continuity broke; the consumer drops state derived from earlier samples. */
    var discontinuity = false

    fun reset() { buffer.clear(); discontinuity = true }

    fun observe(atMs: Long, speedKmh: Double, voltageV: Double, effectiveLoadRaw: Double,
        pressureHPa: Double? = null): AlignedFrame? {
        if (!speedKmh.isFinite() || speedKmh < 0.0 || !voltageV.isFinite() || !effectiveLoadRaw.isFinite()) {
            reset(); return null
        }
        buffer.lastOrNull()?.let { last ->
            val gap = atMs - last.atMs
            if (gap <= 0L || gap > PhysicsPowerConstants.MAX_FRAME_GAP_MS) reset()
        }
        buffer.addLast(Raw(atMs, speedKmh / 3.6, voltageV, effectiveLoadRaw, pressureHPa))
        val half = PhysicsPowerConstants.SMOOTHING_HALF_FRAMES
        val size = 2 * half + 3
        if (buffer.size < size) return null
        while (buffer.size > size) buffer.removeFirst()
        fun smoothed(center: Int): Double {
            var sum = 0.0
            for (i in center - half..center + half) sum += buffer[i].speedMps
            return sum / (2 * half + 1)
        }
        val c = half + 1
        val previous = smoothed(c - 1)
        val current = smoothed(c)
        val next = smoothed(c + 1)
        val span = (buffer[c + 1].atMs - buffer[c - 1].atMs) / 1000.0
        val lagged = buffer[c + PhysicsPowerConstants.LOAD_LAG_FRAMES]
        val frame = AlignedFrame(
            dtS = (buffer[c].atMs - buffer[c - 1].atMs) / 1000.0,
            speedMps = current,
            previousSpeedMps = previous,
            accelMps2 = (next - previous) / span,
            voltageV = buffer[c].voltageV,
            loadVoltageV = lagged.voltageV,
            loadRaw = lagged.loadRaw,
            pressureHPa = buffer[c].pressureHPa,
        )
        buffer.removeFirst()
        return frame
    }
}

/** Scale-free fitted shape; only ratios leave the learner. */
data class PhysicsShape(
    val rollingMps2: Double,
    val aeroPerM: Double,
    val idleWPerKg: Double,
    val recoveryFraction: Double?,
    val driveSamples: Long,
    val brakingSamples: Long,
    val rSquared: Double?,
) {
    /** Cruise work per kilometre and unit mass at a steady speed. */
    fun cruiseJPerKgPerKm(speedMps: Double): Double =
        1000.0 * (rollingMps2 + aeroPerM * speedMps * speedMps + idleWPerKg / speedMps.coerceAtLeast(0.1))

    val equivalentRollingCoefficient: Double get() = rollingMps2 / PhysicsPowerConstants.GRAVITY
}

/** Durable sufficient statistics; refit once per completed trip, like the bucket shape. */
internal class BoardPhysicsModel {
    // Drive normal equations for P = c_r v + c_a v^3 + m v a + P0.
    private val xtx = Array(4) { DoubleArray(4) }
    private val xty = DoubleArray(4)
    private var yy = 0.0
    private var ySum = 0.0
    private var driveSamples = 0L
    private var interceptSamples = 0L
    // Pack resistance from detrended voltage and load: (V - mean V) = -R (I - mean I).
    private var resistanceCross = 0.0
    private var resistanceSquares = 0.0
    // Voltage-implied recovery while braking.
    private var recoveredRaw = 0.0
    private var brakingRaw = 0.0
    private var brakingSamples = 0L
    // Fitted raw coefficients.
    var rollingRaw = 0.0; private set
    var aeroRaw = 0.0; private set
    var massRaw = 0.0; private set
    var idleRaw = 0.0; private set
    private var rSquared: Double? = null
    var refits = 0L; private set
    private var lastTripId: String? = null

    val fitted: Boolean get() = massRaw > 0.0 && driveSamples >= PhysicsPowerConstants.MIN_DRIVE_SAMPLES

    val resistanceVoltsPerRaw: Double?
        get() = if (resistanceSquares > 0.0) (-resistanceCross / resistanceSquares).takeIf { it > 0.0 && it.isFinite() } else null

    val recoveryFraction: Double?
        get() = if (brakingSamples >= PhysicsPowerConstants.MIN_BRAKING_SAMPLES && brakingRaw > 0.0) {
            (recoveredRaw / brakingRaw).coerceIn(0.0, PhysicsPowerConstants.MAX_RECOVERY_FRACTION)
        } else null

    fun shape(): PhysicsShape? = if (!fitted) null else PhysicsShape(
        rollingMps2 = rollingRaw / massRaw,
        aeroPerM = aeroRaw / massRaw,
        idleWPerKg = (idleRaw / massRaw).coerceAtLeast(0.0),
        recoveryFraction = recoveryFraction,
        driveSamples = driveSamples,
        brakingSamples = brakingSamples,
        rSquared = rSquared,
    )

    /** Per-mass shape for classification, falling back to the bootstrap before a refit. */
    internal fun rollingMps2() = if (fitted) rollingRaw / massRaw else PhysicsPowerConstants.BOOTSTRAP_ROLLING_MPS2
    internal fun aeroPerM() = if (fitted) aeroRaw / massRaw else PhysicsPowerConstants.BOOTSTRAP_AERO_PER_M

    fun addDriveSample(v: Double, a: Double, power: Double) {
        val x = doubleArrayOf(v, v * v * v, v * a, 1.0)
        for (i in 0 until 4) {
            for (j in 0 until 4) xtx[i][j] += x[i] * x[j]
            xty[i] += x[i] * power
        }
        yy += power * power; ySum += power; driveSamples++
    }

    /** A moving frame predicted to brake: the rectified load observes only P0. */
    fun addInterceptSample(power: Double) {
        xtx[3][3] += 1.0; xty[3] += power
        yy += power * power; ySum += power; interceptSamples++
    }

    fun addResistanceStep(dV: Double, dI: Double) {
        resistanceCross += dV * dI; resistanceSquares += dI * dI
    }

    fun addBraking(recovered: Double, braking: Double) {
        recoveredRaw += recovered; brakingRaw += braking; brakingSamples++
    }

    fun completeTrip(tripId: String) {
        if (lastTripId == tripId) return
        lastTripId = tripId
        refit()
    }

    /** Non-negative least squares on c_r and c_a by active-set enumeration; m must be positive. */
    private fun refit() {
        if (driveSamples < PhysicsPowerConstants.MIN_DRIVE_SAMPLES) return
        var best: Pair<Double, DoubleArray>? = null
        for (active in listOf(listOf(0, 1, 2, 3), listOf(0, 2, 3), listOf(1, 2, 3), listOf(2, 3))) {
            val beta = solve(active) ?: continue
            if (beta[2] <= 0.0 || beta[0] < 0.0 || beta[1] < 0.0) continue
            var sse = yy
            for (i in 0 until 4) {
                sse -= 2.0 * beta[i] * xty[i]
                for (j in 0 until 4) sse += beta[i] * xtx[i][j] * beta[j]
            }
            if (best == null || sse < best.first) best = sse to beta
        }
        val (sse, beta) = best ?: return
        rollingRaw = beta[0]; aeroRaw = beta[1]; massRaw = beta[2]; idleRaw = beta[3]
        val samples = driveSamples + interceptSamples
        val total = yy - ySum * ySum / samples
        rSquared = if (total > 0.0) (1.0 - sse / total).takeIf { it.isFinite() } else null
        refits++
    }

    private fun solve(active: List<Int>): DoubleArray? {
        val n = active.size
        val a = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) xtx[active[i]][active[j]] else xty[active[i]] } }
        for (col in 0 until n) {
            val pivot = (col until n).maxBy { abs(a[it][col]) }
            if (abs(a[pivot][col]) < 1e-12 * max(1.0, abs(a[col][col]))) return null
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            for (row in 0 until n) if (row != col) {
                val f = a[row][col] / a[col][col]
                for (k in col..n) a[row][k] -= f * a[col][k]
            }
        }
        val out = DoubleArray(4)
        active.forEachIndexed { i, index -> out[index] = a[i][n] / a[i][i] }
        return out.takeIf { values -> values.all { it.isFinite() } }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put("xtx", org.json.JSONArray().apply { xtx.forEach { row -> row.forEach { put(it) } } })
        .put("xty", org.json.JSONArray().apply { xty.forEach { put(it) } })
        .put("yy", yy).put("y_sum", ySum).put("drive_samples", driveSamples)
        .put("intercept_samples", interceptSamples)
        .put("resistance_cross", resistanceCross).put("resistance_squares", resistanceSquares)
        .put("recovered_raw", recoveredRaw).put("braking_raw", brakingRaw)
        .put("braking_samples", brakingSamples)
        .put("rolling_raw", rollingRaw).put("aero_raw", aeroRaw)
        .put("mass_raw", massRaw).put("idle_raw", idleRaw)
        .put("r_squared", rSquared ?: JSONObject.NULL)
        .put("refits", refits)
        .put("last_trip_id", lastTripId ?: JSONObject.NULL)

    companion object {
        fun fromJson(value: JSONObject?): BoardPhysicsModel = BoardPhysicsModel().apply {
            value ?: return@apply
            runCatching {
                require(value.getInt("schema_version") == 1)
                val m = value.getJSONArray("xtx"); val t = value.getJSONArray("xty")
                require(m.length() == 16 && t.length() == 4)
                for (i in 0 until 4) {
                    for (j in 0 until 4) xtx[i][j] = m.getDouble(i * 4 + j)
                    xty[i] = t.getDouble(i)
                }
                yy = value.getDouble("yy"); ySum = value.getDouble("y_sum")
                driveSamples = value.getLong("drive_samples")
                interceptSamples = value.getLong("intercept_samples")
                resistanceCross = value.getDouble("resistance_cross")
                resistanceSquares = value.getDouble("resistance_squares")
                recoveredRaw = value.getDouble("recovered_raw"); brakingRaw = value.getDouble("braking_raw")
                brakingSamples = value.getLong("braking_samples")
                rollingRaw = value.getDouble("rolling_raw"); aeroRaw = value.getDouble("aero_raw")
                massRaw = value.getDouble("mass_raw"); idleRaw = value.getDouble("idle_raw")
                rSquared = if (value.isNull("r_squared")) null else value.getDouble("r_squared")
                refits = value.getLong("refits")
                lastTripId = if (value.isNull("last_trip_id")) null else value.getString("last_trip_id")
                val all = xtx.flatMap { it.asIterable() } + xty.asIterable() +
                    listOf(yy, ySum, resistanceCross, resistanceSquares, recoveredRaw, brakingRaw,
                        rollingRaw, aeroRaw, massRaw, idleRaw)
                require(all.all { it.isFinite() } && driveSamples >= 0 && interceptSamples >= 0 && brakingSamples >= 0)
            }.onFailure { return BoardPhysicsModel() }
        }
    }
}

/**
 * Ride-scoped transient state: alignment, open-circuit voltage, grade and height tracking.
 * Every learning update goes to the durable [BoardPhysicsModel]; window increments are returned.
 */
internal class PhysicsFrameProcessor {
    private val aligner = PhysicsFrameAligner()
    private var previous: AlignedFrame? = null
    private var ocvV: Double? = null
    private var slowVoltage: Double? = null
    private var slowLoad: Double? = null
    private var grade = 0.0
    private var heightM = 0.0
    private var heightReferenceM = 0.0
    private var pressureAltitudeM: Double? = null

    fun reset() {
        aligner.reset(); previous = null; ocvV = null; slowVoltage = null; slowLoad = null; grade = 0.0
        heightM = 0.0; heightReferenceM = 0.0; pressureAltitudeM = null
    }

    fun observe(model: BoardPhysicsModel, atMs: Long, speedKmh: Double, voltageV: Double,
        effectiveLoadRaw: Double, pressureHPa: Double? = null): WindowMotion {
        val frame = aligner.observe(atMs, speedKmh, voltageV, effectiveLoadRaw, pressureHPa)
        if (aligner.discontinuity) {
            aligner.discontinuity = false
            previous = null; ocvV = null; pressureAltitudeM = null; slowVoltage = null; slowLoad = null
        }
        if (frame == null) return WindowMotion.ZERO
        val last = previous
        previous = frame
        val dt = frame.dtS
        if (!(dt > 0.0)) return WindowMotion.ZERO
        val g = PhysicsPowerConstants.GRAVITY
        val v = frame.speedMps
        val a = frame.accelMps2
        val r = model.rollingMps2()
        val k = model.aeroPerM()
        val flatPerMass = v * (r + k * v * v + a)
        val power = frame.loadVoltageV * frame.loadRaw

        // 1. Hinge learning (bootstrap sign before the first refit). The load channel is rectified,
        // so predicted braking frames carry only the idle intercept; dropping them would bias the fit.
        if (v > PhysicsPowerConstants.MIN_FIT_SPEED_MPS) {
            if (flatPerMass > 0.0) model.addDriveSample(v, a, power) else model.addInterceptSample(power)
        }

        // 2. Pack resistance and open-circuit voltage from drive/coast frames.
        val resistance = model.resistanceVoltsPerRaw
        val braking = v > PhysicsPowerConstants.MIN_REGEN_SPEED_MPS &&
            a < PhysicsPowerConstants.MIN_REGEN_ACCEL_MPS2 && flatPerMass < 0.0
        if (!braking) {
            // Detrend against slow averages: removes the open-circuit drift without the
            // attenuation that frame-to-frame differences of a noisy load would introduce.
            val alpha = (dt / PhysicsPowerConstants.RESISTANCE_DETREND_TIME_CONSTANT_S).coerceIn(0.0, 1.0)
            val meanV = slowVoltage?.let { it + alpha * (frame.voltageV - it) } ?: frame.voltageV
            val meanI = slowLoad?.let { it + alpha * (frame.loadRaw - it) } ?: frame.loadRaw
            slowVoltage = meanV; slowLoad = meanI
            val dI = frame.loadRaw - meanI
            if (last != null && abs(dI) >= PhysicsPowerConstants.MIN_RESISTANCE_STEP_RAW) {
                model.addResistanceStep(frame.voltageV - meanV, dI)
            }
        }
        if (resistance != null) {
            if (!braking) {
                val observed = frame.voltageV + resistance * frame.loadRaw
                val alpha = (dt / PhysicsPowerConstants.OCV_TIME_CONSTANT_S).coerceIn(0.0, 1.0)
                ocvV = ocvV?.let { it + alpha * (observed - it) } ?: observed
            } else if (model.fitted) {
                ocvV?.let { ocv ->
                    val impliedRaw = (ocv - frame.voltageV) / resistance
                    val recovered = max(0.0, -impliedRaw) * frame.voltageV * dt
                    val brakingPower = -model.massRaw * flatPerMass * dt
                    if (brakingPower > 0.0) model.addBraking(recovered, brakingPower)
                }
            }
        }

        // 3. Grade: barometer when present, otherwise the aligned drive residual.
        var barometricSeconds = 0.0
        val dh = if (frame.pressureHPa != null && frame.pressureHPa.isFinite() && frame.pressureHPa > 0.0) {
            val altitude = 44_330.77 * (1.0 - (frame.pressureHPa / 1013.25).pow(0.190263))
            val alpha = (dt / PhysicsPowerConstants.PRESSURE_TIME_CONSTANT_S).coerceIn(0.0, 1.0)
            val before = pressureAltitudeM
            val smoothed = before?.let { it + alpha * (altitude - it) } ?: altitude
            pressureAltitudeM = smoothed
            barometricSeconds = dt
            if (before == null) 0.0 else smoothed - before
        } else {
            pressureAltitudeM = null
            if (model.fitted && v > PhysicsPowerConstants.MIN_FIT_SPEED_MPS && flatPerMass > 0.0 && frame.loadRaw > 0.0) {
                val residual = ((power - model.idleRaw) / (model.massRaw * v) - r - k * v * v - a) / g
                val target = residual.coerceIn(-PhysicsPowerConstants.MAX_GRADE, PhysicsPowerConstants.MAX_GRADE)
                grade += (dt / PhysicsPowerConstants.GRADE_TIME_CONSTANT_S).coerceIn(0.0, 1.0) * (target - grade)
            } else {
                grade -= (dt / PhysicsPowerConstants.GRADE_DECAY_TIME_CONSTANT_S).coerceIn(0.0, 1.0) * grade
            }
            if (v > PhysicsPowerConstants.MIN_FIT_SPEED_MPS) grade * v * dt else 0.0
        }

        // 4. Braked work per unit mass, split between deceleration and descent.
        val total = v * (r + k * v * v + a) + g * dh / dt
        val braked = max(0.0, -total) * dt
        val stopShare = max(0.0, -v * a)
        val descentShare = max(0.0, -g * dh / dt)
        val stopFraction = if (stopShare + descentShare > 0.0) stopShare / (stopShare + descentShare) else 1.0

        heightM += dh
        var climb = 0.0
        var descent = 0.0
        val delta = heightM - heightReferenceM
        if (delta >= PhysicsPowerConstants.HEIGHT_HYSTERESIS_M) { climb = delta; heightReferenceM = heightM }
        else if (-delta >= PhysicsPowerConstants.HEIGHT_HYSTERESIS_M) { descent = -delta; heightReferenceM = heightM }

        return WindowMotion(
            netKineticJPerKg = 0.5 * (v * v - frame.previousSpeedMps * frame.previousSpeedMps),
            netHeightM = dh,
            brakingStopGoJPerKg = braked * stopFraction,
            brakingDescentJPerKg = braked * (1.0 - stopFraction),
            climbM = climb,
            descentM = descent,
            alignedSeconds = dt,
            barometricSeconds = barometricSeconds,
        )
    }
}

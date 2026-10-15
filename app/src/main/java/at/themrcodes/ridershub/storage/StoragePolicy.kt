package at.themrcodes.ridershub.storage

import java.time.Instant
import java.time.ZoneId

internal const val STORAGE_DAY_MS = 86_400_000L
internal const val STORAGE_WEEK_MS = 7 * STORAGE_DAY_MS
internal const val STORAGE_INACTIVITY_MS = 14 * STORAGE_DAY_MS
internal const val STORAGE_CHARGE_RISE = 10

internal data class StorageObservation(val at: Long, val percent: Int)

internal data class StorageState(
    val boardId: String? = null,
    val inactivitySince: Long? = null,
    val declined: Boolean = false,
    val requestedAt: Long? = null,
    val observations: List<StorageObservation> = emptyList(),
    val lossPerDay: Double? = null,
    val chargeDueAt: Long? = null,
    val estimatedZeroAt: Long? = null,
    val cycleStartedAt: Long? = null,
    val nextEvaluationAt: Long? = null,
    val lastNotificationDate: String? = null,
) {
    val enabled: Boolean get() = requestedAt != null
    val latest: StorageObservation? get() = observations.lastOrNull()
    val nextSampleAt: Long? get() = if (enabled && lossPerDay == null) latest?.at?.plus(STORAGE_WEEK_MS) else null
    fun offerDue(now: Long): Boolean = !enabled && !declined &&
        inactivitySince?.let { now >= it + STORAGE_INACTIVITY_MS } == true

    fun reminder(now: Long): StorageReminder? = when {
        offerDue(now) -> StorageReminder.OFFER
        !enabled -> null
        latest == null -> StorageReminder.BASELINE
        chargeDueAt?.let { now >= it } == true -> StorageReminder.CHARGE
        nextSampleAt?.let { now >= it } == true -> StorageReminder.SAMPLE
        else -> null
    }

    fun nextActionAt(): Long? = when {
        !enabled -> inactivitySince?.takeUnless { declined }?.plus(STORAGE_INACTIVITY_MS)
        latest == null -> requestedAt
        else -> listOfNotNull(chargeDueAt, nextSampleAt).minOrNull()
    }
}

internal enum class StorageReminder { OFFER, BASELINE, SAMPLE, CHARGE }

internal object StoragePolicy {
    fun enable(state: StorageState, now: Long): StorageState = if (state.boardId == null || state.enabled) state else
        state.copy(declined = false, requestedAt = now, lastNotificationDate = null)

    fun decline(state: StorageState): StorageState = state.copy(declined = true, lastNotificationDate = null)

    fun ride(state: StorageState, distanceKm: Double, now: Long): StorageState =
        if (!distanceKm.isFinite() || distanceKm <= 0.1) state else
            StorageState(boardId = state.boardId, inactivitySince = now)

    /** Called only with a fresh, stationary, sustained live reading. No manual charge acknowledgement. */
    fun observe(state: StorageState, observation: StorageObservation, lowPercent: Int): StorageState {
        if (!state.enabled || observation.percent !in 0..100 || observation.at < requireNotNull(state.requestedAt)) return state
        val previous = state.latest
        if (previous != null && observation.at <= previous.at) return state
        val charged = previous != null && observation.percent - previous.percent >= STORAGE_CHARGE_RISE
        val sampleDue = previous == null || observation.at >= previous.at + STORAGE_WEEK_MS
        if (!charged && !sampleDue) return if (observation.percent <= lowPercent) {
            state.copy(chargeDueAt = minOf(state.chargeDueAt ?: observation.at, observation.at))
        } else state
        val points = when {
            charged || previous == null -> listOf(observation)
            else -> (state.observations + observation).takeLast(24)
        }
        val fittedRate = dischargeRate(points)
        val rate = fittedRate ?: state.lossPerDay
        val zeroAt = rate?.let { projectZero(observation, it) }
        val predictedDue = zeroAt?.let { observation.at + (it - observation.at) / 2 }
        val due = if (observation.percent <= lowPercent) observation.at else predictedDue
        return state.copy(
            observations = points,
            lossPerDay = rate,
            estimatedZeroAt = zeroAt,
            // Checking the battery must not keep moving a previously scheduled reminder into the future.
            chargeDueAt = if (charged || previous == null) due else listOfNotNull(state.chargeDueAt, due).minOrNull(),
            cycleStartedAt = if (charged || previous == null) observation.at else state.cycleStartedAt,
            nextEvaluationAt = observation.at + STORAGE_WEEK_MS,
        )
    }

    /** Percent is coarse telemetry: require three weekly-spaced points and at least one five-point fall. */
    fun dischargeRate(points: List<StorageObservation>): Double? {
        if (points.size < 3) return null
        if (points.zipWithNext().any { (a, b) -> b.at - a.at < STORAGE_WEEK_MS || b.percent > a.percent }) return null
        if (points.first().percent - points.last().percent < 5) return null
        val origin = points.first().at
        val days = points.map { (it.at - origin).toDouble() / STORAGE_DAY_MS }
        val meanDay = days.average()
        val meanPercent = points.map { it.percent }.average()
        val denominator = days.sumOf { (it - meanDay) * (it - meanDay) }
        if (denominator <= 0) return null
        val rate = -points.indices.sumOf { (days[it] - meanDay) * (points[it].percent - meanPercent) } / denominator
        return rate.takeIf { it.isFinite() && it > 0 }
    }

    private fun projectZero(observation: StorageObservation, rate: Double): Long? {
        val duration = observation.percent / rate * STORAGE_DAY_MS
        if (!duration.isFinite() || duration < 0 || duration >= Long.MAX_VALUE - observation.at.toDouble()) return null
        return observation.at + duration.toLong()
    }
}

/** Calendar-local 10:00, recomputed after boot and clock/timezone changes; no 24-hour DST drift. */
internal fun storageAlarmAt(state: StorageState, now: Long, zone: ZoneId): Long? {
    val event = listOfNotNull(state.nextActionAt(), state.nextEvaluationAt.takeIf { state.enabled }).minOrNull() ?: return null
    val earliest = maxOf(now, event)
    val local = Instant.ofEpochMilli(earliest).atZone(zone)
    var date = local.toLocalDate()
    var alarm = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
    if (alarm < earliest || state.lastNotificationDate == date.toString()) {
        date = date.plusDays(1)
        alarm = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
    }
    return alarm
}

internal fun storageCanNotify(state: StorageState, now: Long, zone: ZoneId): Boolean {
    val local = Instant.ofEpochMilli(now).atZone(zone)
    return state.reminder(now) != null && local.hour >= 10 &&
        state.lastNotificationDate != local.toLocalDate().toString()
}

/** Stable stationary readings prevent single-frame jumps and ride rebound from acknowledging a charge. */
internal class StorageReadingWindow {
    private var percent: Int? = null
    private var firstElapsed: Long? = null
    private var lastElapsed: Long? = null

    fun reset() { percent = null; firstElapsed = null; lastElapsed = null }

    fun accept(battery: Int, speedKmh: Double, valid: Boolean, elapsed: Long): Boolean {
        if (!valid || battery !in 0..100 || !speedKmh.isFinite() || speedKmh !in 0.0..0.5) {
            reset()
            return false
        }
        if (percent != battery || lastElapsed == null || elapsed - requireNotNull(lastElapsed) !in 1..2_000) {
            percent = battery
            firstElapsed = elapsed
        }
        lastElapsed = elapsed
        return elapsed - requireNotNull(firstElapsed) >= 30_000
    }
}

/** Integrate only live, contiguous motion. A retained remote trip counter is not a new ride. */
internal class StorageTravelWindow {
    var distanceKm = 0.0
        private set
    private var rideId: String? = null
    private var previousElapsed: Long? = null
    private var previousSpeed: Double? = null

    fun observe(id: String, speed: Double, valid: Boolean, elapsed: Long): Double {
        if (id != rideId) {
            rideId = id
            distanceKm = 0.0
            previousElapsed = null
        }
        val gap = previousElapsed?.let { elapsed - it }
        if (valid && speed.isFinite() && speed in 0.0..100.0 && previousSpeed != null && gap != null && gap in 1..2_000) {
            distanceKm += (speed + requireNotNull(previousSpeed)) / 2.0 * gap / 3_600_000.0
        }
        previousElapsed = if (valid) elapsed else null
        previousSpeed = speed.takeIf { valid && it.isFinite() && it in 0.0..100.0 }
        return distanceKm
    }
}

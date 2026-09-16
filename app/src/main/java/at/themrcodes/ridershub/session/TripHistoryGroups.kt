package at.themrcodes.ridershub.session

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

internal enum class TripHistoryGranularity { DAY, WEEK, MONTH, YEAR }

internal data class TripHistoryGroup(
    val id: String,
    val label: String,
    val granularity: TripHistoryGranularity?,
    val trips: List<RideSummary>,
)

internal data class TripHistorySections(
    val recentTrips: List<RideSummary>,
    val olderGroups: List<TripHistoryGroup>,
)

/** Calendar grouping is presentation-only: it never changes ride or calibration records. */
internal object TripHistoryGroups {
    fun build(
        trips: List<RideSummary>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.US,
    ): TripHistorySections {
        val unique = trips.distinctBy { it.id }
        val dates = unique.associate { trip -> trip.id to runCatching {
            Instant.parse(trip.startedAt).atZone(zone).toLocalDate()
        }.getOrNull() }
        val sorted = unique.sortedByDescending { it.startedAt }
        // Today plus the previous six local dates, including DST changes.
        val cutoff = today.minusDays(6)
        val recent = sorted.filter { dates[it.id]?.let { day -> !day.isBefore(cutoff) } == true }
        val old = sorted.filter { dates[it.id]?.isBefore(cutoff) == true }
        fun date(trip: RideSummary) = requireNotNull(dates[trip.id])
        fun start(day: LocalDate, granularity: TripHistoryGranularity): LocalDate = when (granularity) {
            TripHistoryGranularity.DAY -> day
            TripHistoryGranularity.WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            TripHistoryGranularity.MONTH -> day.withDayOfMonth(1)
            TripHistoryGranularity.YEAR -> day.withDayOfYear(1)
        }
        fun label(group: List<RideSummary>, granularity: TripHistoryGranularity): String {
            val first = group.minOf { date(it) }; val last = group.maxOf { date(it) }
            fun formatted(day: LocalDate, pattern: String) = day.format(DateTimeFormatter.ofPattern(pattern, locale))
            return when (granularity) {
                TripHistoryGranularity.DAY -> formatted(first, "d MMM yyyy")
                TripHistoryGranularity.WEEK -> "Week · ${formatted(first, "d MMM")}–${formatted(last, "d MMM yyyy")}"
                TripHistoryGranularity.MONTH -> formatted(first, "MMMM yyyy")
                TripHistoryGranularity.YEAR -> if (first.year == last.year) first.year.toString()
                    else "${first.year}–${last.year}"
            }
        }
        fun section(group: List<RideSummary>, granularity: TripHistoryGranularity) = TripHistoryGroup(
            // Include the actual span to distinguish weeks clipped by a month/year boundary.
            "history-${granularity}-${group.minOf { date(it) }}-${group.maxOf { date(it) }}",
            label(group, granularity), granularity, group.sortedByDescending { it.startedAt },
        )
        fun refine(group: List<RideSummary>, level: TripHistoryGranularity): List<TripHistoryGroup> {
            val finer = when (level) {
                TripHistoryGranularity.YEAR -> TripHistoryGranularity.MONTH
                TripHistoryGranularity.MONTH -> TripHistoryGranularity.WEEK
                TripHistoryGranularity.WEEK -> TripHistoryGranularity.DAY
                TripHistoryGranularity.DAY -> return listOf(section(group, level))
            }
            val children = group.groupBy { start(date(it), finer) }.toSortedMap(reverseOrder()).values
            return if (children.all { it.size >= 5 }) children.flatMap { refine(it, finer) }
                else listOf(section(group, level))
        }
        // Sparse adjacent years are combined so all older groups can reach five where possible.
        val batches = mutableListOf<List<RideSummary>>()
        var pending = emptyList<RideSummary>()
        old.groupBy { date(it).year }.toSortedMap(reverseOrder()).values.forEach { year ->
            pending = pending + year
            if (pending.size >= 5) { batches.add(pending); pending = emptyList() }
        }
        if (pending.isNotEmpty()) {
            if (batches.isEmpty()) batches.add(pending)
            else batches[batches.lastIndex] = batches.last() + pending
        }
        val groups = batches.flatMap { batch ->
            if (batch.map { date(it).year }.distinct().size == 1) refine(batch, TripHistoryGranularity.YEAR)
            else listOf(section(batch, TripHistoryGranularity.YEAR))
        }.toMutableList()
        val undated = sorted.filter { dates[it.id] == null }
        if (undated.isNotEmpty()) groups.add(TripHistoryGroup("history-undated", "Undated trips", null, undated))
        return TripHistorySections(recent, groups)
    }
}

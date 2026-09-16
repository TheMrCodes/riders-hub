package at.themrcodes.ridershub.session

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TripHistoryGroupsTest {
    private val today = LocalDate.of(2030, 6, 20)
    private fun trips(day: String, count: Int) = (1..count).map { n ->
        summaryFromJson(JSONObject().put("id", "$day-$n").put("started_at", "${day}T12:00:00Z")
            .put("distance_km", 2.0).put("log_file", "synthetic.jsonl").put("active", false))
    }
    private fun group(rides: List<RideSummary>) = TripHistoryGroups.build(rides, today, ZoneId.of("UTC"), Locale.US)

    @Test fun sevenLocalCalendarDaysStayVisibleAndAllOlderTripsAppearExactlyOnce() {
        val rides = trips("2030-06-20", 2) + trips("2030-06-14", 3) + trips("2030-06-13", 5)
        val result = group(rides + rides.first())
        assertEquals(5, result.recentTrips.size)
        assertEquals(5, result.olderGroups.single().trips.size)
        assertEquals(rides.map { it.id }.toSet(), (result.recentTrips + result.olderGroups.flatMap { it.trips }).map { it.id }.toSet())
        assertEquals(TripHistoryGranularity.DAY, result.olderGroups.single().granularity)
    }

    @Test fun usesTheFinestCalendarGroupingThatKeepsFiveTripsTogether() {
        val day = group(trips("2030-05-01", 5)).olderGroups.single()
        val week = group(trips("2030-05-06", 3) + trips("2030-05-07", 2)).olderGroups.single()
        val month = group(trips("2030-05-01", 3) + trips("2030-05-20", 2)).olderGroups.single()
        val year = group(trips("2030-01-01", 3) + trips("2030-05-20", 2)).olderGroups.single()
        assertEquals(listOf(TripHistoryGranularity.DAY, TripHistoryGranularity.WEEK,
            TripHistoryGranularity.MONTH, TripHistoryGranularity.YEAR), listOf(day, week, month, year).map { it.granularity })
    }

    @Test fun denseDaysAndSparseMonthsCanUseDifferentGranularityWithoutLosingTrips() {
        val rides = trips("2030-05-01", 5) + trips("2030-05-20", 5) +
            trips("2030-04-01", 3) + trips("2030-04-20", 2)
        val groups = group(rides).olderGroups
        assertEquals(listOf(TripHistoryGranularity.DAY, TripHistoryGranularity.DAY, TripHistoryGranularity.MONTH), groups.map { it.granularity })
        assertTrue(groups.all { it.trips.size >= 5 })
        assertEquals(rides.size, groups.sumOf { it.trips.size })
        assertEquals(groups.size, groups.map { it.id }.distinct().size)
    }

    @Test fun sparseYearsCombineAndAnIncompleteHistoryIsStillAccessible() {
        val groups = group(trips("2029-01-01", 4) + trips("2028-01-01", 3) + trips("2027-01-01", 1)).olderGroups
        assertEquals(8, groups.single().trips.size)
        assertEquals("2027–2029", groups.single().label)
        assertEquals(2, group(trips("2030-05-01", 2)).olderGroups.single().trips.size)
    }

    @Test fun localDateBoundaryAndMalformedTimestampsDoNotHideRides() {
        val source = trips("2030-06-13", 1).single()
        val atBoundary = source.copy(id = "boundary", startedAt = "2030-06-13T23:30:00Z")
        val invalid = source.copy(id = "unknown", startedAt = "invalid")
        val result = TripHistoryGroups.build(listOf(atBoundary, invalid), today, ZoneId.of("Europe/Vienna"), Locale.US)
        assertEquals(listOf(atBoundary), result.recentTrips)
        assertEquals(listOf(invalid), result.olderGroups.single().trips)
        assertEquals("Undated trips", result.olderGroups.single().label)
    }

    @Test fun weekAtYearBoundaryAndMoreThanThirtyTripsStayUnique() {
        val rides = trips("2029-12-31", 20) + trips("2030-01-01", 20)
        val result = group(rides)
        assertEquals(40, result.olderGroups.sumOf { it.trips.size })
        assertEquals(40, result.olderGroups.flatMap { it.trips }.map { it.id }.distinct().size)
        assertEquals(2, result.olderGroups.map { it.id }.distinct().size)
    }
}

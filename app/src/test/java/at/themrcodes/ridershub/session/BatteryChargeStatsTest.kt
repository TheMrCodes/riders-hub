package at.themrcodes.ridershub.session

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class BatteryChargeStatsTest {
    private val start = Instant.parse("2030-01-01T00:00:00Z").toEpochMilli()

    @Test fun distinguishesOneFrameMaximumFromSustainedMaximum() {
        var stats = BatteryChargeStats().observe(100, start, 100.0)
        for (second in 1..31) stats = stats.observe(80, start + second * 1_000, 100.0)
        assertEquals(100, stats.highestReportedPercent)
        assertEquals(80, stats.highestSettledPercent)
        assertEquals(100, stats.monthlyMaxima["2030-01"])
        assertEquals(100.0, stats.highestObservedOdometerKm!!, 0.0)
    }

    @Test fun gapsAndInvalidTelemetryDoNotCountTowardsSettling() {
        val first = BatteryChargeStats().observe(80, start, 100.0)
        val gap = first.observe(80, start + 60_000, 100.0)
        assertNull(gap.highestSettledPercent)
        assertEquals(0.0, gap.candidateSeconds, 0.0)
        val interrupted = first.observe(80, start + 1_000, 100.0).interrupted()
            .observe(80, start + 2_000, 100.0)
        assertEquals(0.0, interrupted.candidateSeconds, 0.0)
    }

    @Test fun monthlyMaximaAndBoardIdentitySurvivePersistence() {
        val registry = BatteryChargeStatsRegistry()
        registry.observe("board-a", 80, start, 100.0)
        registry.observe("board-a", 75, Instant.parse("2030-02-01T00:00:00Z").toEpochMilli(), 200.0)
        registry.observe("board-b", 95, start, 50.0)
        val restored = BatteryChargeStatsRegistry(registry.toJson().toString())
        val a = requireNotNull(restored.state("board-a"))
        assertEquals(80, a.highestReportedPercent)
        assertEquals(mapOf("2030-01" to 80, "2030-02" to 75), a.monthlyMaxima)
        assertEquals(95, restored.state("board-b")!!.highestReportedPercent)
        assertEquals(a, BatteryChargeStats.fromJson(JSONObject(a.toJson().toString())))
        assertNull(restored.state("unknown"))
    }

    @Test fun legacyMaximumSurvivesLowerLiveReadingsWithoutInventingSettlingEvidence() {
        val registry = BatteryChargeStatsRegistry()
        registry.seedMaximum("board-a", 80)
        val stats = registry.observe("board-a", 50, start, 100.0)
        assertEquals(80, stats.highestReportedPercent)
        assertNull(stats.highestSettledPercent)
        assertNull(stats.highestObservedAt)
        assertEquals(50, stats.monthlyMaxima["2030-01"])
    }

    @Test fun malformedBoardDoesNotEraseOtherBoards() {
        val valid = BatteryChargeStats().observe(80, start, 100.0)
        val json = JSONObject().put("valid", valid.toJson())
            .put("bad", JSONObject().put("highest_reported_percent", 101))
        val registry = BatteryChargeStatsRegistry(json.toString())
        assertEquals(valid, registry.state("valid"))
        assertNull(registry.state("bad"))
    }
}

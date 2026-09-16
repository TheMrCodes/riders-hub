package at.themrcodes.ridershub.log

import at.themrcodes.ridershub.protocol.TelemetryFrame
import at.themrcodes.ridershub.session.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TelemetryFrameRecordTest {
    @Test fun eachFrameContainsItsOwnPostUpdateEstimateAndOriginalCaptureClock() {
        val tracker = BatteryObservationTracker()
        var state = BatteryEstimateState(59.0, 60)
        val records = mutableListOf<JSONObject>()
        val clock = Instant.parse("2030-01-01T00:00:00Z")
        repeat(50) { index ->
            val raw = if (index < 10) 60 else 50
            val result = tracker.observeWithDiagnostics(state, raw, .1, .001, 4.0, 44.0, 7)
            val battery = BatteryFrameDiagnostics(result.diagnostics.status, result.state.percent,
                result.diagnostics, .1, .001, 15, 4.0, 4.0, BatteryLearningModel(), BatteryLearningModel())
            val record = sessionLogEnvelope(index + 1L, "telemetry_frame",
                telemetryFrameFields(frame(raw), index + 1L, battery), clock.plusMillis(index * 100L), index * 100_000_000L)
            assertEquals(raw, record.getInt("board_battery_percent"))
            assertEquals(result.state.percent, record.getDouble("estimated_battery_percent"), 0.0)
            assertEquals(state.percent, record.getJSONObject("battery_estimation").getDouble("previous_percent"), 0.0)
            assertEquals(raw - 2.5, record.getJSONObject("battery_estimation")
                .getDouble("observation_lower_percent"), 0.0)
            assertEquals(raw + 2.5, record.getJSONObject("battery_estimation")
                .getDouble("observation_upper_percent"), 0.0)
            assertEquals(state.percent - result.diagnostics.consumptionPercent + result.diagnostics.correctionPercent,
                result.state.percent, 1e-12)
            records.add(record)
            state = result.state
        }
        // Later tracker updates must not mutate queued records or shift results by one frame.
        assertEquals(60, records.first().getJSONObject("battery_estimation").getInt("reference_percent"))
        assertEquals(50, records.last().getJSONObject("battery_estimation").getInt("reference_percent"))
        assertEquals(4_900_000_000L, records.last().getLong("elapsed_realtime_ns"))
        assertEquals(clock.plusMillis(4900).toString(), records.last().getString("wall_time"))
        assertEquals(50, records.last().getInt("frame_number"))
        assertEquals(50, records.last().getInt("seq"))
    }

    @Test fun invalidOrUnprocessedFramesCannotMasqueradeAsCurrentEstimates() {
        for (status in listOf("invalid_crc", "invalid_board_telemetry", "no_active_ride", "processing_error")) {
            val fields = telemetryFrameFields(frame(60).copy(crcValid = false), 1, BatteryFrameDiagnostics(status))
            assertTrue(fields.isNull("estimated_battery_percent"))
            val diagnostics = fields.getJSONObject("battery_estimation")
            assertTrue(diagnostics.isNull("estimated_percent"))
            assertEquals(status, diagnostics.getString("status"))
            assertFalse(diagnostics.has("previous_percent"))
            assertFalse(fields.getBoolean("crc_valid"))
        }
    }

    @Test fun missingRatesAndHeldGapsAreExplicitAndJsonSafe() {
        val tracker = BatteryObservationTracker()
        val result = tracker.observeWithDiagnostics(BatteryEstimateState(58.0, 60), 80, 0.0, 0.0, null, 44.0, 7)
        val json = BatteryFrameDiagnostics(result.diagnostics.status, result.state.percent,
            result.diagnostics, 0.0, 0.0, basePercentPerKm = Double.NaN).toJson()
        val restored = JSONObject(json.toString())
        assertEquals("held_discontinuity", restored.getString("status"))
        assertEquals(58.0, restored.getDouble("estimated_percent"), 0.0)
        assertFalse(restored.getBoolean("observation_confirmed"))
        assertFalse(restored.getBoolean("reacquiring_used"))
        assertTrue(restored.getBoolean("reacquiring_after"))
        assertTrue(restored.isNull("base_percent_per_km"))
        assertTrue(restored.isNull("applied_percent_per_km"))
    }

    @Test fun inferredGapTransitionIsArchivedSeparatelyFromMeasuredFrameDistance() {
        val json = BatteryFrameDiagnostics(
            status = "propagated_inferred_gap",
            estimatedPercent = 54.2,
            intervalDistanceKm = 0.0,
            estimatedGapSpeedBuckets = mapOf(20 to 0.6, 30 to 0.4),
            inferredGapDistanceKm = 1.0,
            inferredGapBasePercentPerKm = 3.5,
            inferredGapAppliedPercentPerKm = 3.8,
            inferredGapConsumptionPercent = 3.8,
        ).toJson()
        assertEquals(0.0, json.getDouble("interval_distance_km"), 0.0)
        assertEquals(1.0, json.getDouble("inferred_gap_distance_km"), 0.0)
        assertEquals(3.5, json.getDouble("inferred_gap_base_percent_per_km"), 0.0)
        assertEquals(3.8, json.getDouble("inferred_gap_applied_percent_per_km"), 0.0)
        assertEquals(3.8, json.getDouble("inferred_gap_consumption_percent"), 0.0)
        assertEquals(1.0, json.getJSONObject("estimated_gap_speed_bucket_distances_km")
            .let { it.getDouble("20") + it.getDouble("30") }, 1e-12)
    }

    @Test fun envelopeProtectsCaptureClocksAndSequenceFromPayloadFields() {
        val time = Instant.parse("2030-01-01T00:00:00Z")
        val json = sessionLogEnvelope(3, "telemetry_frame",
            JSONObject().put("seq", 900).put("elapsed_realtime_ns", 900).put("wall_time", "later"), time, 42)
        assertEquals(3, json.getInt("seq"))
        assertEquals(42, json.getLong("elapsed_realtime_ns"))
        assertEquals(time.toString(), json.getString("wall_time"))
    }

    private fun frame(percent: Int) = TelemetryFrame(
        ByteArray(25), 2, "Sport", percent, 15.0 to 15.0, 15.0, 44.0, 7,
        0.0, 0.0, 0, 0, true,
    )
}

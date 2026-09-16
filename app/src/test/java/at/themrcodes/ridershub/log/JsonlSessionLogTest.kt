package at.themrcodes.ridershub.log

import android.app.Application
import at.themrcodes.ridershub.session.RideSegment
import at.themrcodes.ridershub.session.RideSummary
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class JsonlSessionLogTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun concurrentBleAndGpsShareOneSequenceAndFinalizationRejectsLateWrites() {
        val file = File(folder.root, "synthetic.jsonl")
        val log = JsonlSessionLog(segment(file, 1))
        val producers = Executors.newFixedThreadPool(2)
        producers.submit { repeat(200) { log.record("telemetry_frame") } }
        producers.submit { repeat(200) { JsonlSessionLog.recordRide(file, "location_fix", JSONObject()) } }
        producers.shutdown(); assertTrue(producers.awaitTermination(5, TimeUnit.SECONDS))
        log.closeSegment("disconnect"); assertTrue(log.awaitClosed())
        JsonlSessionLog.recordRide(file, "location_fix", JSONObject())
        val second = JsonlSessionLog(segment(file, 2))
        second.record("telemetry_frame"); second.closeSegment("disconnect"); assertTrue(second.awaitClosed())
        val last = JsonlSessionLog.finishSession(file, 0, "finished", summary(file))
        val before = file.readBytes()
        JsonlSessionLog.recordRide(file, "location_fix", JSONObject())
        assertEquals(last, JsonlSessionLog.finishSession(file, 0, "retry", summary(file)))
        assertArrayEquals(before, file.readBytes())
        val rows = file.readLines().map(::JSONObject)
        assertEquals((1L..last).toList(), rows.map { it.getLong("seq") })
        assertEquals(1, rows.count { it.getString("type") == "session_end" })
        assertEquals(201, rows.count { it.getString("type") == "location_fix" })
        assertEquals("session_end", rows.last().getString("type"))
    }

    @Test fun truncatedTailIsRemovedAndUnicodeRecordSequenceSurvivesRecovery() {
        val file = folder.newFile("synthetic.jsonl")
        file.writeText("{\"seq\":41,\"type\":\"diagnostic\",\"message\":\"Grüße\"}\n{\"seq\":42,")
        JsonlSessionLog.resumeRide(file)
        JsonlSessionLog.recordRide(file, "location_fix", JSONObject())
        JsonlSessionLog.finishSession(file, 0, "recovered", summary(file))
        val rows = file.readLines().map(::JSONObject)
        assertEquals(listOf(41L, 42L, 43L), rows.map { it.getLong("seq") })
        assertEquals("Grüße", rows.first().getString("message"))
    }

    @Test fun mutablePayloadIsFrozenBeforeEnqueue() {
        val file = File(folder.root, "synthetic.jsonl")
        JsonlSessionLog.resumeRide(file)
        val fields = JSONObject().put("nested", JSONObject().put("value", "original"))
        JsonlSessionLog.recordRide(file, "diagnostic", fields)
        fields.getJSONObject("nested").put("value", "changed")
        JsonlSessionLog.finishSession(file, 0, "finished", summary(file))
        assertEquals("original", JSONObject(file.readLines().first()).getJSONObject("nested").getString("value"))
    }

    private fun segment(file: File, number: Int) = RideSegment("synthetic", "02:00:00:00:00:01",
        "Synthetic board", file.path, 0, number, number == 1)
    private fun summary(file: File) = RideSummary(
        id = "synthetic", startedAt = "2030-01-01T00:00:00Z", endedAt = "2030-01-01T00:02:00Z",
        lastFrameAt = "2030-01-01T00:00:00Z", distanceKm = 0.0, movingSeconds = 0.0, maxSpeedKmh = 0.0,
        boardBatteryStart = null, boardBatteryEnd = null, boardBatteryMin = null,
        packVoltageStart = null, packVoltageEnd = null, packVoltageMin = null, packVoltageMax = null,
        restingVoltageStart = null, restingVoltageEnd = null, odometerStartKm = null, odometerEndKm = null,
        frameCount = 0, boardFrameCount = 0, crcErrorCount = 0, segmentCount = 2,
        modes = emptySet(), logFile = file.path, active = false)
}

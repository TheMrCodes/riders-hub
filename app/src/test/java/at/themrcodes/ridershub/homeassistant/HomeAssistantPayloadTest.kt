package at.themrcodes.ridershub.homeassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantPayloadTest {
    @Test
    fun sensorUpdateDataRemainsAJsonArray() {
        val batch = homeAssistantSensorBatch(syntheticUpdate())
        val payload = JSONObject()
            .put("type", "update_sensor_states")
            .put("data", batch.values)

        assertTrue(payload.get("data") is JSONArray)
        assertTrue(payload.toString().contains("\"data\":["))
        assertEquals(5, batch.values.length())
    }

    @Test
    fun emptyHomeAssistantResultIsNotAcceptedAsDelivered() {
        val batch = homeAssistantSensorBatch(syntheticUpdate())

        assertThrows(HomeAssistantProtocolException::class.java) {
            validateHomeAssistantSensorUpdate(JSONObject(), batch.expectedIds)
        }
    }

    @Test
    fun successfulResultsReportDisabledEntities() {
        val batch = homeAssistantSensorBatch(syntheticUpdate())
        val response = JSONObject()
        batch.expectedIds.forEachIndexed { index, id ->
            response.put(
                id,
                JSONObject()
                    .put("success", true)
                    .apply { if (index == 0) put("is_disabled", true) },
            )
        }

        assertEquals(1, validateHomeAssistantSensorUpdate(response, batch.expectedIds).disabledCount)
    }

    private fun syntheticUpdate() = HomeAssistantUpdate(
        boardBatteryPercent = 75,
        estimatedRangeKm = 12.3456,
        currentTripKm = 1.2345,
        inUse = true,
        updatedAt = "2030-01-01T00:00:00Z",
    )
}

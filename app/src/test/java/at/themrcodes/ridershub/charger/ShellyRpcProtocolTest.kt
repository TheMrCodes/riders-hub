package at.themrcodes.ridershub.charger

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShellyRpcProtocolTest {
    @Test fun `request contains only allowlisted RPC envelope data`() {
        val value = JSONObject(String(ShellyRpcProtocol.request(7, "Switch.GetStatus", JSONObject().put("id", 0))))
        assertEquals(7, value.getInt("id")); assertEquals("riders-hub", value.getString("src"))
        assertEquals("Switch.GetStatus", value.getString("method")); assertEquals(0, value.getJSONObject("params").getInt("id"))
        assertFalse(value.has("dst"))
    }

    @Test fun `length is big endian`() {
        val prefix = ShellyRpcProtocol.lengthPrefix(513)
        assertEquals(513, ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN).int)
        assertEquals(513, ShellyRpcProtocol.responseLength(prefix))
    }

    @Test fun `matched response returns result`() {
        val result = ShellyRpcProtocol.parseResponse("""{"id":4,"result":{"output":true}}""".toByteArray(), 4)
        assertTrue(result.getBoolean("output"))
    }
}

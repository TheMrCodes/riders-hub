package at.themrcodes.ridershub.charger

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object ShellyRpcProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("5f6d4f53-5f52-5043-5f53-56435f49445f")
    val DATA_UUID: UUID = UUID.fromString("5f6d4f53-5f52-5043-5f64-6174615f5f5f")
    val TX_CONTROL_UUID: UUID = UUID.fromString("5f6d4f53-5f52-5043-5f74-785f63746c5f")
    val RX_CONTROL_UUID: UUID = UUID.fromString("5f6d4f53-5f52-5043-5f72-785f63746c5f")

    fun request(id: Long, method: String, params: JSONObject = JSONObject()): ByteArray = JSONObject()
        .put("id", id).put("src", "riders-hub").put("method", method).put("params", params)
        .toString().toByteArray(Charsets.UTF_8)

    fun lengthPrefix(payloadSize: Int): ByteArray {
        require(payloadSize in 1..MAX_RPC_BYTES)
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payloadSize).array()
    }

    fun responseLength(bytes: ByteArray): Int {
        require(bytes.size == 4) { "Shelly RPC length must be four bytes" }
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int.also {
            require(it in 1..MAX_RPC_BYTES) { "Shelly RPC response is outside the safe size limit" }
        }
    }

    fun parseResponse(bytes: ByteArray, expectedId: Long): JSONObject {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.optLong("id", Long.MIN_VALUE) == expectedId) { "Mismatched Shelly RPC response" }
        json.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "Shelly RPC error")) }
        require(json.has("result")) { "Shelly RPC response has no result" }
        return json.getJSONObject("result")
    }

    const val MAX_RPC_BYTES = 64 * 1024
}

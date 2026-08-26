package at.themrcodes.ridershub.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackfireProtocolTest {
    @Test
    fun childLimiterCommandsMatchDocumentedProtocol() {
        assertArrayEquals(
            hexBytes("ac a0 01 08 21"),
            BackfireProtocol.childLimiterCommand(enabled = true),
        )
        assertArrayEquals(
            hexBytes("ac a0 00 c9 e1"),
            BackfireProtocol.childLimiterCommand(enabled = false),
        )
    }

    @Test
    fun childLimiterConfirmationUsesTelemetryStatus() {
        assertTrue(BackfireProtocol.childLimiterCommandConfirmed(true, 0x81))
        assertTrue(BackfireProtocol.childLimiterCommandConfirmed(false, 2))
        assertFalse(BackfireProtocol.childLimiterCommandConfirmed(true, 2))
        assertFalse(BackfireProtocol.childLimiterCommandConfirmed(false, 0x81))
    }

    @Test
    fun crcKnownVectors() {
        assertEquals(0x4B37, BackfireProtocol.crc16Modbus("123456789".encodeToByteArray()))
        assertEquals(0xE1C9, BackfireProtocol.crc16Modbus(hexBytes("ac a0 00")))
        assertEquals(0x2108, BackfireProtocol.crc16Modbus(hexBytes("ac a0 01")))
    }

    @Test
    fun reassemblesAndDecodesTwentyPlusFive() {
        val frame = makeFrame(mode = 2)
        val decoder = BackfireFrameDecoder()

        assertTrue(decoder.feed(frame.copyOfRange(0, 20)).isEmpty())
        val decoded = decoder.feed(frame.copyOfRange(20, frame.size)).single()

        assertEquals("Sport", decoded.mode)
        assertEquals(85, decoded.boardBatteryPercent)
        assertEquals(20.684, decoded.speedKmh, 0.0001)
        assertEquals(48.352, decoded.packVoltageV, 0.0001)
        assertEquals(-17, decoded.loadRaw)
        assertEquals(0.3, decoded.tripKm, 0.0001)
        assertEquals(12.5, decoded.odometerKm, 0.0001)
        assertTrue(decoded.crcValid)
    }

    @Test
    fun reportsLimiterAndInvalidCrc() {
        val frame = makeFrame(mode = 0x81)
        frame[10] = (frame[10].toInt() xor 1).toByte()

        val decoded = BackfireFrameDecoder().feed(frame).single()

        assertEquals("Child speed limit", decoded.mode)
        assertFalse(decoded.crcValid)
    }

    @Test
    fun preservesRawDataWhileResynchronizing() {
        val discarded = mutableListOf<ByteArray>()
        val decoder = BackfireFrameDecoder { _, bytes -> discarded += bytes }

        val decoded = decoder.feed("noise".encodeToByteArray() + makeFrame()).single()

        assertEquals("noise", discarded.single().decodeToString())
        assertTrue(decoded.crcValid)
    }

    private fun makeFrame(mode: Int = 2): ByteArray {
        val payload = ByteArray(23)
        payload[0] = 0xAC.toByte()
        payload[1] = 0x06
        payload[2] = 25
        payload[3] = 1
        payload[4] = mode.toByte()
        payload[5] = 85.toByte()
        payload.putBe(6, 2, 20_500)
        payload.putBe(8, 2, 20_684)
        payload.putBe(10, 2, 48_352)
        payload.putBe(12, 2, 0xFFEF)
        payload.putBe(16, 2, 3)
        payload.putBe(18, 3, 125)
        val crc = BackfireProtocol.crc16Modbus(payload)
        return payload + byteArrayOf((crc and 0xFF).toByte(), (crc ushr 8).toByte())
    }

    private fun ByteArray.putBe(start: Int, length: Int, value: Int) {
        repeat(length) { offset ->
            this[start + length - 1 - offset] = (value ushr (offset * 8)).toByte()
        }
    }

    private fun hexBytes(value: String): ByteArray =
        value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}

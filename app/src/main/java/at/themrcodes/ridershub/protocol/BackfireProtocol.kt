package at.themrcodes.ridershub.protocol

import java.util.UUID

object BackfireProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("0000f1f0-0000-1000-8000-00805f9b34fb")
    val WRITE_UUID: UUID = UUID.fromString("0000f1f1-0000-1000-8000-00805f9b34fb")
    val NOTIFY_UUID: UUID = UUID.fromString("0000f1f2-0000-1000-8000-00805f9b34fb")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val FRAME_HEADER = 0xAC
    const val MIN_FRAME_LENGTH = 8
    const val MAX_FRAME_LENGTH = 64

    fun crc16Modbus(data: ByteArray, endExclusive: Int = data.size): Int {
        var crc = 0xFFFF
        for (index in 0 until endExclusive) {
            crc = crc xor data[index].u8()
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    fun modeName(code: Int): String = when (code) {
        0 -> "Off"
        1 -> "Eco"
        2 -> "Sport"
        3 -> "Turbo"
        0x81 -> "Child speed limit"
        else -> "Unknown ($code)"
    }

    fun childLimiterCommand(enabled: Boolean): ByteArray {
        val payload = byteArrayOf(
            FRAME_HEADER.toByte(),
            0xA0.toByte(),
            if (enabled) 0x01 else 0x00,
        )
        val crc = crc16Modbus(payload)
        return payload + byteArrayOf((crc and 0xFF).toByte(), (crc ushr 8).toByte())
    }

    fun isChildLimiterActive(modeCode: Int): Boolean = modeCode == 0x81

    fun childLimiterCommandConfirmed(requestedEnabled: Boolean, modeCode: Int): Boolean =
        isChildLimiterActive(modeCode) == requestedEnabled
}

data class TelemetryFrame(
    val raw: ByteArray,
    val modeCode: Int,
    val mode: String,
    val boardBatteryPercent: Int,
    val speedCandidatesKmh: Pair<Double, Double>,
    val speedKmh: Double,
    val packVoltageV: Double,
    val loadRaw: Int,
    val tripKm: Double,
    val odometerKm: Double,
    val expectedCrc: Int,
    val actualCrc: Int,
    val crcValid: Boolean,
)

class BackfireFrameDecoder(
    private val onDiscard: (reason: String, bytes: ByteArray) -> Unit = { _, _ -> },
) {
    private var buffer = ByteArray(0)

    fun feed(chunk: ByteArray): List<TelemetryFrame> {
        if (chunk.isNotEmpty()) {
            buffer += chunk
        }
        val frames = mutableListOf<TelemetryFrame>()

        while (buffer.isNotEmpty()) {
            val headerAt = buffer.indexOfFirst { it.u8() == BackfireProtocol.FRAME_HEADER }
            if (headerAt < 0) {
                onDiscard("no_frame_header", buffer)
                buffer = ByteArray(0)
                break
            }
            if (headerAt > 0) {
                onDiscard("bytes_before_frame_header", buffer.copyOfRange(0, headerAt))
                buffer = buffer.copyOfRange(headerAt, buffer.size)
            }
            if (buffer.size < 3) break

            val declaredLength = buffer[2].u8()
            if (declaredLength !in BackfireProtocol.MIN_FRAME_LENGTH..BackfireProtocol.MAX_FRAME_LENGTH) {
                onDiscard("invalid_declared_length_$declaredLength", buffer.copyOfRange(0, 1))
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }
            if (buffer.size < declaredLength) break

            val rawFrame = buffer.copyOfRange(0, declaredLength)
            buffer = buffer.copyOfRange(declaredLength, buffer.size)
            if (rawFrame.size < 23) {
                onDiscard("frame_too_short_${rawFrame.size}", rawFrame)
                continue
            }
            frames += decode(rawFrame)
        }

        return frames
    }

    private fun decode(frame: ByteArray): TelemetryFrame {
        val speedA = frame.uintBe(6, 2) / 1000.0
        val speedB = frame.uintBe(8, 2) / 1000.0
        val expectedCrc = frame[frame.lastIndex - 1].u8() or (frame.last().u8() shl 8)
        val actualCrc = BackfireProtocol.crc16Modbus(frame, frame.size - 2)
        val modeCode = frame[4].u8()

        return TelemetryFrame(
            raw = frame,
            modeCode = modeCode,
            mode = BackfireProtocol.modeName(modeCode),
            boardBatteryPercent = frame[5].u8(),
            speedCandidatesKmh = speedA to speedB,
            speedKmh = maxOf(speedA, speedB),
            packVoltageV = frame.uintBe(10, 2) / 1000.0,
            loadRaw = frame.signedBe(12, 2),
            tripKm = frame.uintBe(16, 2) / 10.0,
            odometerKm = frame.uintBe(18, 3) / 10.0,
            expectedCrc = expectedCrc,
            actualCrc = actualCrc,
            crcValid = expectedCrc == actualCrc,
        )
    }
}

fun Byte.u8(): Int = toInt() and 0xFF

fun ByteArray.uintBe(start: Int, length: Int): Int {
    var value = 0
    for (index in start until start + length) {
        value = (value shl 8) or this[index].u8()
    }
    return value
}

fun ByteArray.signedBe(start: Int, length: Int): Int {
    val unsigned = uintBe(start, length)
    val signBit = 1 shl (length * 8 - 1)
    return if (unsigned and signBit != 0) unsigned - (1 shl (length * 8)) else unsigned
}

fun ByteArray.hex(): String = joinToString(separator = " ") { "%02x".format(it.u8()) }

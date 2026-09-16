package at.themrcodes.ridershub.log

import at.themrcodes.ridershub.protocol.TelemetryFrame
import at.themrcodes.ridershub.protocol.hex
import at.themrcodes.ridershub.session.BatteryFrameDiagnostics
import org.json.JSONArray
import org.json.JSONObject

/** One decoded BLE frame and the estimator result produced while processing exactly that frame. */
internal fun telemetryFrameFields(
    frame: TelemetryFrame, number: Long, battery: BatteryFrameDiagnostics,
): JSONObject = JSONObject()
    .put("frame_number", number)
    .put("raw_hex", frame.raw.hex())
    .put("frame_length", frame.raw.size)
    .put("mode_code", frame.modeCode)
    .put("mode", frame.mode)
    .put("board_battery_percent", frame.boardBatteryPercent)
    .put("speed_candidates_kmh", JSONArray().put(frame.speedCandidatesKmh.first).put(frame.speedCandidatesKmh.second))
    .put("speed_kmh", frame.speedKmh)
    .put("pack_voltage_v", frame.packVoltageV)
    .put("load_raw_signed_be", frame.loadRaw)
    .put("trip_km", frame.tripKm)
    .put("odometer_km", frame.odometerKm)
    .put("unknown", JSONObject()
        .put("byte_1", frame.raw.copyOfRange(1, 2).hex())
        .put("byte_3", frame.raw.copyOfRange(3, 4).hex())
        .put("bytes_12_15", frame.raw.copyOfRange(12, 16).hex())
        .put("bytes_21_22", frame.raw.copyOfRange(21, 23).hex()))
    .put("crc_expected", frame.expectedCrc)
    .put("crc_actual", frame.actualCrc)
    .put("crc_valid", frame.crcValid)
    .put("estimated_battery_percent", battery.estimatedPercent ?: JSONObject.NULL)
    .put("battery_estimation", battery.toJson())

package at.themrcodes.ridershub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import at.themrcodes.ridershub.protocol.BackfireProtocol
import at.themrcodes.ridershub.session.RideStore
import org.json.JSONObject
import java.io.File

/** ADB/system-only, emulator-only byte injection. Absent from release builds. */
class EmulatorTelemetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.HARDWARE !in setOf("ranchu", "goldfish")) return
        val app = context.applicationContext
        when (intent.getStringExtra("command")) {
            "start" -> {
                check(session == null) { "Stop the previous synthetic session first" }
                startSession(app, intent.getIntExtra("board", 1))
                status(app, "started")
            }
            "frames" -> {
                check(remaining == 0) { "A synthetic frame batch is already running" }
                val active = checkNotNull(session) { "Start a synthetic session first" }
                val count = intent.getIntExtra("count", 1).coerceIn(1, 600)
                val percent = intent.getIntExtra("battery_percent", 60).coerceIn(0, 100)
                val speed = intent.getFloatExtra("speed_kmh", 18f).coerceIn(0f, 50f)
                val voltage = intent.getFloatExtra("voltage_v", 44f).coerceIn(0f, 60f)
                val input = intent.getIntExtra("load_raw", 7).coerceIn(-1000, 2000)
                val invalidCrc = intent.getBooleanExtra("invalid_crc", false)
                remaining = count
                status(app, "running")
                val send = object : Runnable {
                    override fun run() {
                        val bytes = syntheticFrame(percent, speed, voltage, input, invalidCrc)
                        // Exercise decoder reassembly using the normal 20+5 byte split.
                        active.ingestNotification(BackfireProtocol.NOTIFY_UUID.toString(), bytes.copyOfRange(0, 20))
                        active.ingestNotification(BackfireProtocol.NOTIFY_UUID.toString(), bytes.copyOfRange(20, 25))
                        remaining--
                        emitted++
                        if (remaining > 0) handler.postDelayed(this, 100L)
                        else status(app, "batch_complete")
                    }
                }
                handler.post(send)
            }
            "replay" -> {
                check(session == null && remaining == 0) { "Stop the previous replay first" }
                val file = File(app.filesDir, "emulator-replay.jsonl")
                require(file.length() in 1..16_000_000) { "Invalid replay file size" }
                // Validate the entire input before changing app state.
                val frames = file.useLines { lines ->
                    lines.map { line ->
                        val row = JSONObject(line)
                        val delay = row.getLong("delay_ms")
                        val hex = row.getString("hex")
                        require(delay in 0..60_000 && hex.length in 46..128 && hex.length % 2 == 0)
                        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        require(bytes[0] == 0xAC.toByte() && (bytes[2].toInt() and 255) == bytes.size)
                        delay to bytes
                    }.take(100_001).toList()
                }
                require(frames.isNotEmpty() && frames.size <= 100_000)
                startSession(app, intent.getIntExtra("board", 3))
                remaining = frames.size
                emitted = 0
                status(app, "replaying")
                val send = object : Runnable {
                    override fun run() {
                        val bytes = frames[emitted].second
                        val active = checkNotNull(session)
                        bytes.asList().chunked(20).forEach {
                            active.ingestNotification(BackfireProtocol.NOTIFY_UUID.toString(), it.toByteArray())
                        }
                        emitted++
                        remaining--
                        if (remaining > 0) {
                            if (emitted % 10 == 0) status(app, "replaying")
                            handler.postDelayed(this, frames[emitted].first)
                        } else {
                            session?.stop("archive_replay_complete")
                            session = null
                            AppStateStore(app).setServiceActive(false)
                            RideStore.get(app).finalizeExpired(
                                System.currentTimeMillis() + RideStore.SESSION_GRACE_MS + 1,
                            )
                            status(app, "replay_complete")
                        }
                    }
                }
                handler.postDelayed(send, frames.first().first)
            }
            "stop", "finalize" -> {
                handler.removeCallbacksAndMessages(null)
                remaining = 0
                session?.stop("synthetic_emulator_test")
                session = null
                AppStateStore(app).setServiceActive(false)
                if (intent.getStringExtra("command") == "finalize") {
                    RideStore.get(app).finalizeExpired(System.currentTimeMillis() + RideStore.SESSION_GRACE_MS + 1)
                }
                status(app, "stopped")
            }
        }
    }

    companion object {
        private val handler = Handler(Looper.getMainLooper())
        private var session: BleTelemetrySession? = null
        private var remaining = 0
        private var emitted = 0

        private fun startSession(app: Context, board: Int) {
            require(board in 1..239)
            val address = "02:00:00:00:00:%02X".format(java.util.Locale.US, board + 16)
            val state = AppStateStore(app)
            state.setAssociation(address, "Emulator replay remote", null)
            state.setServiceActive(true)
            state.setPresence(true, "Emulator telemetry replay")
            session = BleTelemetrySession(app, address, "Emulator replay remote", state)
            state.setConnection("Listening — emulator replay")
        }

        private fun status(context: Context, status: String) {
            File(context.filesDir, "emulator-telemetry-status.json").writeText(JSONObject()
                .put("status", status).put("emitted", emitted).put("remaining", remaining).toString())
        }

        private fun syntheticFrame(percent: Int, speed: Float, voltage: Float, input: Int, invalidCrc: Boolean): ByteArray {
            val payload = ByteArray(23)
            payload[0] = 0xAC.toByte(); payload[1] = 6; payload[2] = 25; payload[3] = 1
            payload[4] = 2; payload[5] = percent.toByte()
            fun put16(at: Int, value: Int) {
                payload[at] = (value ushr 8).toByte(); payload[at + 1] = value.toByte()
            }
            put16(6, (speed * 1000).toInt()); put16(8, (speed * 1000).toInt())
            put16(10, (voltage * 1000).toInt()); put16(12, input)
            val crc = BackfireProtocol.crc16Modbus(payload) xor if (invalidCrc) 1 else 0
            return payload + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
        }
    }
}

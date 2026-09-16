@file:android.annotation.SuppressLint("MissingPermission")
@file:Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")

package at.themrcodes.ridershub.charger

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.UUID
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Minimal allowlisted Shelly Gen2 BLE RPC client. The service and [start] enforce CONNECT. */
@Suppress("DEPRECATION")
class ShellyChargerSession(
    private val context: Context,
    private val address: String,
    private val store: ChargerStore,
    private val freshAppearance: Boolean,
    private val onGattActiveChanged: (Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val archive = ChargerArchive.get(context)
    private var tracker = ChargerCycleTracker()
    private val rpcIds = AtomicLong(0)
    private val queue = ArrayDeque<RpcCall>()
    private var current: RpcCall? = null
    private var gatt: BluetoothGatt? = null
    private var service: BluetoothGattService? = null
    private var responseLength = 0
    private var response = ByteArrayOutputStream()
    private var responseControlAttempts = 0
    private var dataReadQueueRetries = 0
    private var stopped = false
    private var verifiedDevice = false
    private var prompted = false
    private var stopReason: String? = null
    private var episodeOpen = false
    private var gapOpen = false

    private val poll = object : Runnable {
        override fun run() {
            if (!stopped && verifiedDevice) {
                requestStatus()
                handler.postDelayed(this, POLL_MS)
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (stopped) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    archive.append("gatt", JSONObject().put("event", "connected"))
                    onGattActiveChanged(true)
                    store.setGattConnected(true)
                    if (gapOpen) {
                        archive.append("gap_end")
                        gapOpen = false
                    }
                    store.update(ChargerPhase.CONNECTING, "Discovering Shelly BLE RPC")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    archive.append("gatt", JSONObject().put("event", "disconnected").put("status", status))
                    onGattActiveChanged(false)
                    store.setGattConnected(false)
                    if (episodeOpen && !gapOpen) {
                        archive.append("gap_start")
                        gapOpen = true
                    }
                    handler.removeCallbacks(poll)
                    current = null; queue.clear(); verifiedDevice = false; service = null
                    store.update(ChargerPhase.CONNECTING, "Charger RPC disconnected; retrying")
                    handler.postDelayed({ if (!stopped) gatt.connect() }, RECONNECT_MS)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) return@post fail("Shelly service discovery failed ($status)")
                service = gatt.getService(ShellyRpcProtocol.SERVICE_UUID)
                    ?: return@post fail("Shelly BLE RPC service is unavailable; enable Bluetooth RPC on the plug")
                if (characteristic(ShellyRpcProtocol.RX_CONTROL_UUID) == null) {
                    return@post fail("Shelly RX control is unavailable")
                }
                archive.append("gatt", JSONObject().put("event", "rpc_service_ready"))
                beginVerification()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) return@post failCurrent("Shelly RPC write failed ($status)")
                when (characteristic.uuid) {
                    ShellyRpcProtocol.TX_CONTROL_UUID -> writeRequestBody()
                    ShellyRpcProtocol.DATA_UUID -> readResponseControl()
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handler.post { acceptRead(characteristic.uuid, value, status) }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post { acceptRead(characteristic.uuid, characteristic.value ?: byteArrayOf(), status) }
        }

    }

    fun start() {
        check(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        archive.start(if (freshAppearance) "fresh_appearance" else "service_rebound")
        episodeOpen = true
        if (freshAppearance) {
            archive.append("presence", JSONObject().put("present", true).put("fresh", true))
        } else {
            archive.append("gatt", JSONObject().put("event", "connection_probe"))
        }
        store.setGattConnected(false)
        store.update(ChargerPhase.CONNECTING, "Connecting to charger")
        val device = context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.getRemoteDevice(address)
        gatt = device.connectGatt(context, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    fun stop(reason: String) {
        if (stopped) return
        stopped = true
        handler.removeCallbacksAndMessages(null)
        runCatching { gatt?.disconnect() }; runCatching { gatt?.close() }
        gatt = null
        onGattActiveChanged(false)
        archive.append("presence", JSONObject().put("present", false).put("reason", reason))
        finishEpisode(reason)
        store.setAbsent()
    }

    fun userDecision(start: Boolean, appearanceToken: String): Result<Unit> = runCatching {
        check(appearanceToken == store.snapshot().appearanceToken) { "This charger prompt is no longer current" }
        check(verifiedDevice) { "The Shelly plug has not been verified" }
        if (!episodeOpen) {
            archive.start(if (start) "user_restart" else "user_stop")
            episodeOpen = true
            if (start) tracker = ChargerCycleTracker()
        }
        archive.append("user_decision", JSONObject().put("decision", if (start) "start" else "stop"))
        if (start) {
            store.update(ChargerPhase.STARTING, "Turning charger on with a 12-hour fail-safe")
            setRelay(true, "user_confirmed_start")
        } else {
            store.update(ChargerPhase.STOPPING, "Turning charger off")
            setRelay(false, "user_confirmed_stop")
        }
    }

    private fun beginVerification() {
        rpc("Shelly.GetDeviceInfo", JSONObject()) { result ->
            val generation = result.optInt("gen", -1)
            val app = result.optString("app")
            if (generation != 2 || !app.equals("PlusPlugS", ignoreCase = true)) {
                fail("The associated device is not a Shelly Plus Plug S Gen2")
                return@rpc
            }
            verifiedDevice = true
            store.setRpcVerified(true)
            archive.append("rpc_state", JSONObject().put("verified_model", "Shelly Plus Plug S Gen2"))
            requestStatus()
            handler.removeCallbacks(poll); handler.postDelayed(poll, POLL_MS)
        }
    }

    private fun requestStatus() = rpc("Switch.GetStatus", JSONObject().put("id", 0)) { parseStatus(it) }

    private fun parseStatus(result: JSONObject) {
        val output = result.opt("output") as? Boolean ?: return fail("Shelly status omitted relay output")
        val power = (result.opt("apower") as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return fail("Shelly status omitted active power")
        val errors = result.optJSONArray("errors") ?: JSONArray()
        fun has(fragment: String) = (0 until errors.length()).any { errors.optString(it).contains(fragment, true) }
        val temperature = result.optJSONObject("temperature")?.opt("tC") as? Number
        val observation = ChargerObservation(
            atMs = android.os.SystemClock.elapsedRealtime(), outputOn = output, powerWatts = power,
            temperatureC = temperature?.toDouble(), overTemperature = has("temp"), overPower = has("power"),
            overVoltage = has("overvoltage"), underVoltage = has("undervoltage"),
        )
        if (output && !episodeOpen) {
            archive.start("external_output_on")
            episodeOpen = true
            tracker = ChargerCycleTracker()
            archive.append("rpc_state", JSONObject().put("external_output_on", true))
        }
        archive.sample(observation)
        val action = tracker.observe(observation)
        val phase = when {
            stopReason != null -> if (output) ChargerPhase.STOPPING else ChargerPhase.COMPLETE
            !output -> ChargerPhase.AWAITING_CONFIRMATION
            tracker.activeConfirmed -> ChargerPhase.CHARGING
            else -> ChargerPhase.WAITING_FOR_LOAD
        }
        store.update(
            phase, statusFor(phase, power), output, power, tracker.peakWatts,
            tracker.activeEvidenceMs / 1000, tracker.completionEvidenceMs / 1000,
        )
        if (freshAppearance && !prompted && phase == ChargerPhase.AWAITING_CONFIRMATION) {
            val token = store.snapshot().appearanceToken ?: return
            prompted = true
            archive.append("prompt", JSONObject().put("channel", "notification"))
            ChargerNotifier(context).prompt(token)
        }
        when (action) {
            ChargerAction.NO_LOAD_OFF -> setRelay(false, "no_active_load_within_five_minutes")
            ChargerAction.COMPLETE_OFF -> setRelay(false, "charge_complete")
            ChargerAction.FAULT_OFF -> setRelay(false, "protection_error")
            else -> Unit
        }
        stopReason?.let { reason ->
            if (!output) {
                archive.append("rpc_state", JSONObject().put("relay_off_verified", true).put("reason", reason))
                if (reason == "charge_complete") ChargerNotifier(context).complete()
                else if (reason == "protection_error") ChargerNotifier(context).fault("A Shelly protection condition was observed; output is off.")
                finishEpisode(reason)
                stopReason = null
            }
        } ?: if (!output && tracker.activeConfirmed) finishEpisode("external_output_off") else Unit
    }

    private fun statusFor(phase: ChargerPhase, power: Double): String = when (phase) {
        ChargerPhase.AWAITING_CONFIRMATION -> "Charger ready — confirmation required"
        ChargerPhase.WAITING_FOR_LOAD -> "Output on; waiting for at least 10 W"
        ChargerPhase.CHARGING -> String.format(java.util.Locale.US, "Charging at %.1f W", power)
        ChargerPhase.COMPLETE -> "Output off and verified"
        else -> phase.name.lowercase(Locale.ROOT).replace('_', ' ')
    }

    private fun setRelay(on: Boolean, reason: String) {
        stopReason = if (on) null else reason
        if (!on) store.update(ChargerPhase.STOPPING, "Turning charger off: ${reason.replace('_', ' ')}")
        archive.append("relay_command", JSONObject().put("on", on).put("reason", reason).put("toggle_after_s", if (on) 43_200 else 0))
        val params = JSONObject().put("id", 0).put("on", on)
        if (on) params.put("toggle_after", 43_200)
        rpc("Switch.Set", params) {
            archive.append("rpc_state", JSONObject().put("command_accepted", true).put("requested_on", on))
            requestStatus()
        }
    }

    private fun rpc(method: String, params: JSONObject, completed: (JSONObject) -> Unit) {
        check(method in ALLOWED_METHODS)
        queue.add(RpcCall(rpcIds.incrementAndGet(), method, params, completed))
        if (current == null) beginNextRpc()
    }

    private fun beginNextRpc() {
        val call = queue.poll() ?: return
        current = call
        responseLength = 0; response = ByteArrayOutputStream()
        responseControlAttempts = 0; dataReadQueueRetries = 0
        val payload = ShellyRpcProtocol.request(call.id, call.method, call.params)
        call.payload = payload
        archive.append("gatt", JSONObject().put("event", "rpc_started").put("method", call.method))
        handler.postDelayed({
            if (current?.id == call.id) failCurrent("Shelly RPC timed out")
        }, RPC_TIMEOUT_MS)
        val tx = characteristic(ShellyRpcProtocol.TX_CONTROL_UUID) ?: return failCurrent("Shelly TX control missing")
        write(tx, ShellyRpcProtocol.lengthPrefix(payload.size))
    }

    private fun writeRequestBody() {
        val call = current ?: return
        val data = characteristic(ShellyRpcProtocol.DATA_UUID) ?: return failCurrent("Shelly RPC data characteristic missing")
        write(data, call.payload)
    }

    private fun write(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val code = gatt?.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (code != BluetoothGatt.GATT_SUCCESS) failCurrent("Could not queue Shelly RPC write")
    }

    private fun readResponseControl() {
        val callId = current?.id ?: return
        if (++responseControlAttempts > MAX_RESPONSE_CONTROL_ATTEMPTS) {
            return failCurrent("Shelly RPC response length was not ready")
        }
        val rx = characteristic(ShellyRpcProtocol.RX_CONTROL_UUID) ?: return failCurrent("Shelly RX control missing")
        if (gatt?.readCharacteristic(rx) != true) {
            handler.postDelayed(
                { if (current?.id == callId && responseLength == 0) readResponseControl() },
                GATT_QUEUE_RETRY_MS,
            )
        }
    }

    private fun acceptRead(uuid: UUID, value: ByteArray, status: Int) {
        if (current == null) return
        if (status != BluetoothGatt.GATT_SUCCESS) return failCurrent("Shelly RPC read failed ($status)")
        when (uuid) {
            ShellyRpcProtocol.RX_CONTROL_UUID -> acceptResponseLength(value)
            ShellyRpcProtocol.DATA_UUID -> {
                dataReadQueueRetries = 0
                response.write(value)
                if (response.size() < responseLength) readResponseData() else completeRpc()
            }
        }
    }

    private fun acceptResponseLength(value: ByteArray) {
        if (current == null || responseLength != 0) return
        responseLength = runCatching { ShellyRpcProtocol.responseLength(value) }.getOrElse {
            val callId = current?.id ?: return
            handler.postDelayed(
                { if (current?.id == callId && responseLength == 0) readResponseControl() },
                RESPONSE_NOT_READY_RETRY_MS,
            )
            return
        }
        archive.append("gatt", JSONObject().put("event", "response_ready").put("bytes", responseLength))
        readResponseData()
    }

    private fun readResponseData() {
        val callId = current?.id ?: return
        val data = characteristic(ShellyRpcProtocol.DATA_UUID) ?: return failCurrent("Shelly RPC data missing")
        if (gatt?.readCharacteristic(data) != true) {
            if (++dataReadQueueRetries > MAX_GATT_QUEUE_RETRIES) {
                return failCurrent("Could not queue Shelly RPC response read")
            }
            handler.postDelayed(
                { if (current?.id == callId) readResponseData() },
                GATT_QUEUE_RETRY_MS,
            )
        }
    }

    private fun completeRpc() {
        val call = current ?: return
        val bytes = response.toByteArray()
        if (bytes.size != responseLength) return failCurrent("Shelly RPC response was truncated")
        val result = runCatching { ShellyRpcProtocol.parseResponse(bytes, call.id) }.getOrElse {
            failCurrent(it.message ?: "Invalid Shelly RPC response"); return
        }
        archive.append("gatt", JSONObject().put("event", "rpc_completed").put("method", call.method))
        current = null
        call.completed(result)
        if (current == null) beginNextRpc()
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic? = service?.getCharacteristic(uuid)

    private fun failCurrent(message: String) { current = null; queue.clear(); fail(message) }
    private fun fail(message: String) {
        store.setRpcVerified(false)
        store.update(ChargerPhase.ERROR, message, error = message)
        archive.append("rpc_state", JSONObject().put("error", message))
    }

    private fun finishEpisode(reason: String) {
        if (!episodeOpen) return
        runCatching { archive.finish(reason) }
        episodeOpen = false
    }

    private data class RpcCall(
        val id: Long,
        val method: String,
        val params: JSONObject,
        val completed: (JSONObject) -> Unit,
        var payload: ByteArray = byteArrayOf(),
    )

    companion object {
        private val ALLOWED_METHODS = setOf("Shelly.GetDeviceInfo", "Switch.GetStatus", "Switch.Set")
        private const val POLL_MS = 10_000L
        private const val RECONNECT_MS = 2_000L
        private const val RPC_TIMEOUT_MS = 8_000L
        private const val GATT_QUEUE_RETRY_MS = 100L
        private const val RESPONSE_NOT_READY_RETRY_MS = 150L
        private const val MAX_GATT_QUEUE_RETRIES = 10
        private const val MAX_RESPONSE_CONTROL_ATTEMPTS = 20
    }
}

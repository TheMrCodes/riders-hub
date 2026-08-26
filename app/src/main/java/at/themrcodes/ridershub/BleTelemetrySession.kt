@file:android.annotation.SuppressLint("MissingPermission")

package at.themrcodes.ridershub

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import at.themrcodes.ridershub.log.JsonlSessionLog
import at.themrcodes.ridershub.protocol.BackfireFrameDecoder
import at.themrcodes.ridershub.protocol.BackfireProtocol
import at.themrcodes.ridershub.protocol.TelemetryFrame
import at.themrcodes.ridershub.protocol.hex
import at.themrcodes.ridershub.session.RideStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

class BleTelemetrySession(
    private val context: Context,
    deviceAddress: String,
    private val deviceName: String?,
    private val state: AppStateStore,
    private val onGattActiveChanged: (Boolean) -> Unit = {},
) {
    private val deviceAddress = canonicalBluetoothAddress(deviceAddress)
    private val handler = Handler(Looper.getMainLooper())
    private val notificationCount = AtomicLong(0)
    private val notificationBytes = AtomicLong(0)
    private val frameCount = AtomicLong(0)
    private val crcErrorCount = AtomicLong(0)
    private val rideStore = RideStore.get(context)
    private val segment = rideStore.openSegment(deviceAddress, deviceName)
    private val log = JsonlSessionLog(segment)
    private val batteryWarnings = BatteryWarningNotifier(context)
    private val commandLock = Any()
    private val decoder = BackfireFrameDecoder { reason, bytes ->
        log.record(
            "decoder_discard",
            JSONObject()
                .put("reason", reason)
                .put("bytes_length", bytes.size)
                .put("value_hex", bytes.hex()),
        )
    }
    private var active = true
    private var gatt: BluetoothGatt? = null
    private var retryAttempt = 0
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var telemetryReady = false
    private var pendingLimiterEnabled: Boolean? = null
    private var reportedGattActive = false
    private val gattProgress = GattProgressTracker()
    private var gattDeadline = gattProgress.enter(GattStage.IDLE)
    private var watchdogGatt: BluetoothGatt? = null

    private val reconnectRunnable = Runnable { connect() }
    private val gattWatchdogRunnable = Runnable {
        val deadline = gattDeadline
        val stalledGatt = watchdogGatt
        if (!active || !gattProgress.isCurrent(deadline) || deadline.stage.timeoutMs == null) {
            return@Runnable
        }
        log.record(
            "gatt_stage_timeout",
            JSONObject()
                .put("stage", deadline.stage.logName)
                .put("timeout_ms", deadline.stage.timeoutMs),
        )
        scheduleReconnect("${deadline.stage.logName}_timeout", stalledGatt)
    }
    private val commandTimeoutRunnable = Runnable {
        val pending = synchronized(commandLock) {
            pendingLimiterEnabled.also { pendingLimiterEnabled = null }
        }
        if (pending != null) {
            state.setLimiterCommandStatus(
                "Not confirmed by telemetry; the limiter may be unchanged",
            )
            log.record(
                "application_command_not_confirmed",
                JSONObject()
                    .put("command", "child_limiter")
                    .put("requested_enabled", pending)
                    .put("timeout_ms", COMMAND_CONFIRMATION_TIMEOUT_MS),
            )
        }
    }

    init {
        state.setLatestLog(log.file.absolutePath)
        state.setLimiterControlAvailable(false)
        state.clearError()
    }

    fun start() {
        log.record("presence_appeared", JSONObject().put("device_address", deviceAddress))
        connect()
    }

    fun setChildLimiter(enabled: Boolean): Result<Unit> = runCatching {
        val bluetoothGatt: BluetoothGatt
        val characteristic: BluetoothGattCharacteristic
        synchronized(commandLock) {
            check(active) { "Telemetry session is not active" }
            check(telemetryReady) { "Telemetry is not ready" }
            check(pendingLimiterEnabled == null) { "Another limiter command is still pending" }
            bluetoothGatt = checkNotNull(gatt) { "GATT is not connected" }
            characteristic = checkNotNull(writeCharacteristic) {
                "The remote does not expose the F1F1 write characteristic"
            }
            pendingLimiterEnabled = enabled
        }

        val value = BackfireProtocol.childLimiterCommand(enabled)
        log.record(
            "application_command_requested",
            JSONObject()
                .put("command", "child_limiter")
                .put("requested_enabled", enabled)
                .put("value_hex", value.hex())
                .put("source", "user_confirmed_hidden_control")
                .put("verification", "protocol_research_not_live_tested"),
        )
        val result = bluetoothGatt.writeCharacteristic(
            characteristic,
            value,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
        if (result != BluetoothGatt.GATT_SUCCESS) {
            synchronized(commandLock) { pendingLimiterEnabled = null }
            error("Android rejected the GATT write request with status $result")
        }
        state.setLimiterCommandStatus(
            "${if (enabled) "Enable" else "Disable"} sent; waiting for telemetry confirmation",
        )
        handler.removeCallbacks(commandTimeoutRunnable)
        handler.postDelayed(commandTimeoutRunnable, COMMAND_CONFIRMATION_TIMEOUT_MS)
        Unit
    }.onFailure { error ->
        state.setLimiterCommandStatus(error.message ?: "Limiter command failed")
        log.record(
            "application_command_rejected",
            JSONObject()
                .put("command", "child_limiter")
                .put("requested_enabled", enabled)
                .put("reason", error.message ?: error.javaClass.simpleName),
        )
    }

    fun stop(reason: String) {
        if (!active) return
        active = false
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(commandTimeoutRunnable)
        enterGattStage(GattStage.IDLE, null)
        resetCommandTransport("Telemetry session stopped")
        val existingGatt = gatt
        gatt = null
        runCatching { existingGatt?.disconnect() }
        runCatching { existingGatt?.close() }
        reportGattActive(false)
        state.setConnection("Idle — $reason")
        val finalSequence = log.closeSegment(
            reason,
            JSONObject()
                .put("notification_count", notificationCount.get())
                .put("notification_bytes", notificationBytes.get())
                .put("frame_count", frameCount.get())
                .put("crc_error_count", crcErrorCount.get()),
        )
        log.awaitClosed()
        rideStore.endSegment(reason, finalSequence)
    }

    private fun connect() {
        if (!active) return
        handler.removeCallbacks(reconnectRunnable)
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            failAndStop("BLUETOOTH_CONNECT permission is missing")
            return
        }
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            scheduleReconnect("Bluetooth is disabled")
            return
        }

        try {
            state.setConnection("Connecting to $deviceAddress")
            log.record(
                "gatt_connect_attempt",
                JSONObject()
                    .put("attempt", retryAttempt + 1)
                    .put("device_address", deviceAddress)
                    .put("auto_connect", false)
                    .put("transport", "LE"),
            )
            val device = adapter.getRemoteDevice(deviceAddress)
            val newGatt = device.connectGatt(
                context,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                handler,
            )
            if (newGatt == null) {
                scheduleReconnect("connectGatt returned no GATT client")
                return
            }
            gatt = newGatt
            enterGattStage(GattStage.CONNECTING, newGatt)
        } catch (error: Exception) {
            logError("gatt_connect_exception", error)
            scheduleReconnect(error.message ?: error.javaClass.simpleName)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(bluetoothGatt: BluetoothGatt, status: Int, newState: Int) {
            log.record(
                "gatt_connection_state",
                JSONObject()
                    .put("status", status)
                    .put("new_state", newState)
                    .put("new_state_name", bluetoothStateName(newState)),
            )
            if (!active) {
                bluetoothGatt.close()
                return
            }
            if (!isCurrentGatt(bluetoothGatt, "connection_state")) {
                runCatching { bluetoothGatt.disconnect() }
                runCatching { bluetoothGatt.close() }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        scheduleReconnect("Connected with GATT status $status", bluetoothGatt)
                        return
                    }
                    reportGattActive(true)
                    retryAttempt = 0
                    state.setConnection("Connected — discovering services")
                    enterGattStage(GattStage.DISCOVERING_SERVICES, bluetoothGatt)
                    val started = bluetoothGatt.discoverServices()
                    log.record("service_discovery_requested", JSONObject().put("accepted", started))
                    if (!started) {
                        scheduleReconnect("Service discovery was rejected", bluetoothGatt)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    reportGattActive(false)
                    if (gatt === bluetoothGatt) gatt = null
                    bluetoothGatt.close()
                    scheduleReconnect("GATT disconnected with status $status")
                }
            }
        }

        override fun onServicesDiscovered(bluetoothGatt: BluetoothGatt, status: Int) {
            if (!isCurrentGatt(bluetoothGatt, "services_discovered")) return
            val services = describeServices(bluetoothGatt.services)
            log.record(
                "gatt_services_discovered",
                JSONObject().put("status", status).put("services", services),
            )
            if (status != BluetoothGatt.GATT_SUCCESS) {
                scheduleReconnect("Service discovery failed with status $status", bluetoothGatt)
                return
            }

            val notify = bluetoothGatt
                .getService(BackfireProtocol.SERVICE_UUID)
                ?.getCharacteristic(BackfireProtocol.NOTIFY_UUID)
            writeCharacteristic = bluetoothGatt
                .getService(BackfireProtocol.SERVICE_UUID)
                ?.getCharacteristic(BackfireProtocol.WRITE_UUID)
            log.record(
                "limiter_control_capability",
                JSONObject().put("f1f1_write_characteristic_found", writeCharacteristic != null),
            )
            if (notify == null) {
                log.record(
                    "protocol_error",
                    JSONObject().put("reason", "F1F2 notification characteristic not found"),
                )
                state.setError("Remote does not expose the expected F1F2 characteristic")
                scheduleReconnect("F1F2 notification characteristic not found", bluetoothGatt)
                return
            }
            state.setConnection("Connected — enabling telemetry")
            enableNotifications(bluetoothGatt, notify)
        }

        override fun onDescriptorWrite(
            bluetoothGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            log.record(
                "gatt_descriptor_write",
                JSONObject()
                    .put("descriptor_uuid", descriptor.uuid.toString())
                    .put("characteristic_uuid", descriptor.characteristic.uuid.toString())
                    .put("status", status),
            )
            if (!isCurrentGatt(bluetoothGatt, "descriptor_write")) return
            if (descriptor.uuid == BackfireProtocol.CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                enterGattStage(GattStage.LISTENING, bluetoothGatt)
                state.setConnection("Listening for telemetry")
                telemetryReady = true
                state.setLimiterControlAvailable(writeCharacteristic != null)
                state.clearError()
                log.record("telemetry_listening", JSONObject())
            } else if (descriptor.uuid == BackfireProtocol.CCCD_UUID) {
                scheduleReconnect("CCCD write failed with GATT status $status", bluetoothGatt)
            }
        }

        override fun onCharacteristicChanged(
            bluetoothGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (!isCurrentGatt(bluetoothGatt, "characteristic_changed")) return
            handleNotification(characteristic, value)
        }

        override fun onCharacteristicWrite(
            bluetoothGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!isCurrentGatt(bluetoothGatt, "characteristic_write")) return
            if (characteristic.uuid != BackfireProtocol.WRITE_UUID) return
            val pending = synchronized(commandLock) { pendingLimiterEnabled }
            log.record(
                "application_command_write_result",
                JSONObject()
                    .put("command", "child_limiter")
                    .put("requested_enabled", pending ?: JSONObject.NULL)
                    .put("gatt_status", status),
            )
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (pending != null) {
                    state.setLimiterCommandStatus("GATT write accepted; waiting for telemetry confirmation")
                }
            } else {
                handler.removeCallbacks(commandTimeoutRunnable)
                synchronized(commandLock) { pendingLimiterEnabled = null }
                state.setLimiterCommandStatus("Limiter write failed with GATT status $status")
            }
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            log.record("gatt_service_changed", JSONObject())
            if (!active || !isCurrentGatt(gatt, "service_changed")) return
            resetCommandTransport("Remote services changed")
            state.setConnection("Connected — refreshing services")
            enterGattStage(GattStage.DISCOVERING_SERVICES, gatt)
            if (!gatt.discoverServices()) {
                scheduleReconnect("Service rediscovery was rejected", gatt)
            }
        }
    }

    private fun enableNotifications(
        bluetoothGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        val localEnabled = bluetoothGatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(BackfireProtocol.CCCD_UUID)
        log.record(
            "notification_setup",
            JSONObject()
                .put("characteristic_uuid", characteristic.uuid.toString())
                .put("local_enabled", localEnabled)
                .put("cccd_found", cccd != null),
        )
        if (!localEnabled || cccd == null) {
            scheduleReconnect("Could not enable F1F2 notifications", bluetoothGatt)
            return
        }

        enterGattStage(GattStage.ENABLING_NOTIFICATIONS, bluetoothGatt)
        val result = bluetoothGatt.writeDescriptor(
            cccd,
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
        )
        log.record("cccd_write_requested", JSONObject().put("request_status", result))
        if (result != BluetoothStatusCodes.SUCCESS) {
            scheduleReconnect("CCCD write request was rejected with status $result", bluetoothGatt)
        }
    }

    private fun handleNotification(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (!active) return
        notificationCount.incrementAndGet()
        notificationBytes.addAndGet(value.size.toLong())
        log.record(
            "notification",
            JSONObject()
                .put("characteristic_uuid", characteristic.uuid.toString())
                .put("bytes_length", value.size)
                .put("value_hex", value.hex()),
        )
        decoder.feed(value).forEach(::recordFrame)
    }

    private fun recordFrame(frame: TelemetryFrame) {
        val number = frameCount.incrementAndGet()
        if (!frame.crcValid) crcErrorCount.incrementAndGet()
        val speed = JSONArray()
            .put(frame.speedCandidatesKmh.first)
            .put(frame.speedCandidatesKmh.second)
        val unknown = JSONObject()
            .put("byte_1", frame.raw.copyOfRange(1, 2).hex())
            .put("byte_3", frame.raw.copyOfRange(3, 4).hex())
            .put("bytes_12_15", frame.raw.copyOfRange(12, 16).hex())
            .put("bytes_21_22", frame.raw.copyOfRange(21, 23).hex())

        log.record(
            "telemetry_frame",
            JSONObject()
                .put("frame_number", number)
                .put("raw_hex", frame.raw.hex())
                .put("frame_length", frame.raw.size)
                .put("mode_code", frame.modeCode)
                .put("mode", frame.mode)
                .put("board_battery_percent", frame.boardBatteryPercent)
                .put("speed_candidates_kmh", speed)
                .put("speed_kmh", frame.speedKmh)
                .put("pack_voltage_v", frame.packVoltageV)
                .put("load_raw_signed_be", frame.loadRaw)
                .put("trip_km", frame.tripKm)
                .put("odometer_km", frame.odometerKm)
                .put("unknown", unknown)
                .put("crc_expected", frame.expectedCrc)
                .put("crc_actual", frame.actualCrc)
                .put("crc_valid", frame.crcValid),
        )
        rideStore.recordFrame(frame)
        state.setFrame(number, frame)
        batteryWarnings.maybeNotifyBoard(
            sessionId = segment.sessionId,
            batteryPercent = frame.boardBatteryPercent,
            packVoltageV = frame.packVoltageV,
        )
        confirmLimiterCommandFromTelemetry(frame)
    }

    private fun scheduleReconnect(reason: String, staleGatt: BluetoothGatt? = null) {
        if (!active) return
        enterGattStage(GattStage.IDLE, null)
        if (staleGatt != null) {
            if (gatt === staleGatt) gatt = null
            runCatching { staleGatt.disconnect() }
            runCatching { staleGatt.close() }
        }
        reportGattActive(false)
        resetCommandTransport("Connection interrupted before limiter confirmation")
        handler.removeCallbacks(reconnectRunnable)
        retryAttempt = (retryAttempt + 1).coerceAtMost(10)
        val delayMs = (2_000L shl (retryAttempt - 1).coerceAtMost(5)).coerceAtMost(60_000L)
        state.setConnection("Disconnected — retry in ${delayMs / 1000}s")
        log.record(
            "gatt_reconnect_scheduled",
            JSONObject()
                .put("reason", reason)
                .put("attempt", retryAttempt)
                .put("delay_ms", delayMs),
        )
        handler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun enterGattStage(stage: GattStage, bluetoothGatt: BluetoothGatt?) {
        handler.removeCallbacks(gattWatchdogRunnable)
        gattDeadline = gattProgress.enter(stage)
        watchdogGatt = bluetoothGatt
        log.record(
            "gatt_stage_changed",
            JSONObject()
                .put("stage", stage.logName)
                .put("timeout_ms", stage.timeoutMs ?: JSONObject.NULL),
        )
        stage.timeoutMs?.let { handler.postDelayed(gattWatchdogRunnable, it) }
    }

    private fun isCurrentGatt(bluetoothGatt: BluetoothGatt, callbackName: String): Boolean {
        if (gatt === bluetoothGatt) return true
        log.record(
            "stale_gatt_callback_ignored",
            JSONObject().put("callback", callbackName),
        )
        return false
    }

    private fun reportGattActive(active: Boolean) {
        if (reportedGattActive == active) return
        reportedGattActive = active
        onGattActiveChanged(active)
    }

    private fun failAndStop(message: String) {
        state.setError(message)
        log.record("fatal_error", JSONObject().put("message", message))
        stop("fatal_error")
    }

    private fun confirmLimiterCommandFromTelemetry(frame: TelemetryFrame) {
        if (!frame.crcValid) return
        val pending = synchronized(commandLock) { pendingLimiterEnabled } ?: return
        if (!BackfireProtocol.childLimiterCommandConfirmed(pending, frame.modeCode)) return
        synchronized(commandLock) { pendingLimiterEnabled = null }
        handler.removeCallbacks(commandTimeoutRunnable)
        state.setLimiterCommandStatus(
            "Confirmed ${if (pending) "enabled" else "disabled"} by telemetry",
        )
        log.record(
            "application_command_confirmed_by_telemetry",
            JSONObject()
                .put("command", "child_limiter")
                .put("requested_enabled", pending)
                .put("mode_code", frame.modeCode)
                .put("mode", frame.mode),
        )
    }

    private fun resetCommandTransport(reason: String) {
        handler.removeCallbacks(commandTimeoutRunnable)
        telemetryReady = false
        writeCharacteristic = null
        state.setLimiterControlAvailable(false)
        val wasPending = synchronized(commandLock) {
            (pendingLimiterEnabled != null).also { pendingLimiterEnabled = null }
        }
        if (wasPending) state.setLimiterCommandStatus(reason)
    }

    private fun logError(type: String, error: Throwable) {
        log.record(
            type,
            JSONObject()
                .put("exception", error.javaClass.name)
                .put("message", error.message ?: JSONObject.NULL),
        )
    }

    private fun describeServices(services: List<BluetoothGattService>): JSONArray {
        val result = JSONArray()
        services.forEach { service ->
            val characteristics = JSONArray()
            service.characteristics.forEach { characteristic ->
                characteristics.put(
                    JSONObject()
                        .put("uuid", characteristic.uuid.toString())
                        .put("properties", characteristic.properties)
                        .put(
                            "descriptors",
                            JSONArray(characteristic.descriptors.map { it.uuid.toString() }),
                        ),
                )
            }
            result.put(
                JSONObject()
                    .put("uuid", service.uuid.toString())
                    .put("type", service.type)
                    .put("characteristics", characteristics),
            )
        }
        return result
    }

    private fun bluetoothStateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "UNKNOWN_$state"
    }

    companion object {
        private const val COMMAND_CONFIRMATION_TIMEOUT_MS = 8_000L
    }
}

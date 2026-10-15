package at.themrcodes.ridershub

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import java.time.Instant

/**
 * Optional 1 Hz barometric pressure for relative-height grade. Phones without a pressure
 * sensor never register; the physics model then infers grade from aligned drive power.
 * Pressure is stored only in the private ride log, never as a location.
 */
internal class BarometerSampler(
    context: Context,
    private val onSample: (pressureHPa: Double, wallTime: Instant, elapsedRealtimeNs: Long) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private var lastSampleElapsedNs: Long? = null
    private var running = false

    val available: Boolean get() = sensor != null

    fun start() {
        if (running || sensor == null) return
        lastSampleElapsedNs = null
        running = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        val hPa = event.values.firstOrNull()?.toDouble() ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        if (!hPa.isFinite() || hPa <= 0 || lastSampleElapsedNs?.let { now - it < SAMPLE_INTERVAL_NS } == true) return
        lastSampleElapsedNs = now
        onSample(hPa, Instant.now(), now)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val SAMPLE_INTERVAL_NS = 1_000_000_000L
    }
}

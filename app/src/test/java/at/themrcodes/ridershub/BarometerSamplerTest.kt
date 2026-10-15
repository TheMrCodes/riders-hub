package at.themrcodes.ridershub

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class BarometerSamplerTest {
    @Test fun firstSampleIsRecordedThenThrottledAndRestartAcceptsAFreshSample() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(SensorManager::class.java)
        shadowOf(manager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_PRESSURE))
        val samples = mutableListOf<Double>()
        val sampler = BarometerSampler(app) { value, _, _ -> samples.add(value) }
        val event = ShadowSensorManager.createSensorEvent(1).apply { values[0] = 1013f }
        sampler.start()
        sampler.onSensorChanged(event)
        assertEquals(listOf(1013.0), samples)
        sampler.onSensorChanged(event)
        assertEquals(1, samples.size)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        sampler.onSensorChanged(event)
        assertEquals(2, samples.size)
        sampler.stop()
        sampler.onSensorChanged(event)
        assertEquals(2, samples.size)
        sampler.start()
        sampler.onSensorChanged(event)
        assertEquals(3, samples.size)
        sampler.stop()
    }
}

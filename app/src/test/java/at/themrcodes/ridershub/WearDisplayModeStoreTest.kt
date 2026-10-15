package at.themrcodes.ridershub

import android.app.Application
import at.themrcodes.ridershub.wear.shared.WearDashboardMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class WearDisplayModeStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val store = WearDisplayModeStore(context)
    @Before fun clear() { context.getSharedPreferences("wear_display_modes", 0).edit().clear().commit() }

    @Test fun nodeModesRemainIndependentAndSurviveRecreation() {
        store.set("watch-a", WearDashboardMode.RACE)
        store.set("watch-b", WearDashboardMode.AMBIENT)
        val restored = WearDisplayModeStore(context)
        assertEquals(WearDashboardMode.RACE, restored.mode("watch-a"))
        assertEquals(WearDashboardMode.AMBIENT, restored.mode("watch-b"))
        store.set("watch-a", null)
        assertEquals(WearDashboardMode.AMBIENT, restored.mode("watch-a"))
    }

    @Test fun delayedBootstrapCannotOverrideANewerModeEvent() {
        val revision = store.revision()
        store.set("watch-a", WearDashboardMode.AMBIENT)
        store.bootstrap(mapOf("watch-a" to WearDashboardMode.RACE), revision)
        assertEquals(WearDashboardMode.AMBIENT, store.mode("watch-a"))
        store.bootstrap(mapOf("watch-a" to WearDashboardMode.RACE), store.revision())
        assertEquals(WearDashboardMode.RACE, store.mode("watch-a"))
    }
}

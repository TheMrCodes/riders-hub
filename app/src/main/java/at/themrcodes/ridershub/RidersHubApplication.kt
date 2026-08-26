package at.themrcodes.ridershub

import android.app.Application

class RidersHubApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStateStore(this).resetRuntimeState()
        BatteryWarningNotifier.createChannel(this)
    }
}

package at.themrcodes.ridershub

import android.app.Application
import at.themrcodes.ridershub.api.ApiIntegration
import at.themrcodes.ridershub.charger.ChargerNotifier
import at.themrcodes.ridershub.charger.ChargerStore

class RidersHubApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStateStore(this).resetRuntimeState()
        ChargerStore(this).resetRuntime()
        BatteryWarningNotifier.createChannel(this)
        ChargerNotifier.createChannel(this)
        ApiIntegration.get(this).recoverDelivery()
    }
}

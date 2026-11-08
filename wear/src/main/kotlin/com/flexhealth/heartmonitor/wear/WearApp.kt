package com.flexhealth.heartmonitor.wear

import android.app.Application
import com.flexhealth.heartmonitor.core.android.KeystorePairingStore
import com.flexhealth.heartmonitor.wear.settings.WatchSettings

/** Contenedor de dependencias del reloj (inyección manual, sin frameworks adicionales). */
class WearApp : Application() {
    val settings by lazy { WatchSettings(this) }
    val pairings by lazy { KeystorePairingStore(this, "watch_pairings.bin") }
}

val android.content.Context.wearApp: WearApp get() = applicationContext as WearApp

package com.flexhealth.heartmonitor.receiver

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.flexhealth.heartmonitor.core.android.KeystorePairingStore
import com.flexhealth.heartmonitor.engine.EngineClock
import com.flexhealth.heartmonitor.engine.HeartRateEngine
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.receiver.data.MonitorMode
import com.flexhealth.heartmonitor.receiver.data.ReceiverSettings
import com.flexhealth.heartmonitor.receiver.transport.DataLayerReceiver
import com.flexhealth.heartmonitor.receiver.transport.LanReceiver
import com.flexhealth.heartmonitor.receiver.transport.ReceiverTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Reloj del motor basado en el reloj monotónico de Android (incluye el tiempo en reposo). */
object AndroidEngineClock : EngineClock {
    override fun wallMs() = System.currentTimeMillis()
    override fun elapsedMs() = SystemClock.elapsedRealtime()
}

/** Dependencias de la app receptora (inyección manual). Una instancia por proceso. */
class AppGraph(context: Context) {
    val settings = ReceiverSettings(context)
    val engine = HeartRateEngine(AndroidEngineClock)
    val pairingStore by lazy { KeystorePairingStore(context, "receiver_pairings.bin") }
    val lan by lazy { LanReceiver(context, pairingStore) }
    val dataLayer by lazy { DataLayerReceiver(context) }

    /** Modo activo en el servicio (`null` = servicio detenido). */
    val activeMode = MutableStateFlow<MonitorMode?>(null)

    fun transportFor(mode: MonitorMode): ReceiverTransport = when (mode) {
        MonitorMode.PHONE_BLUETOOTH -> dataLayer
        MonitorMode.TABLET_WIFI -> lan
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val linkStatus: Flow<LinkStatus> = activeMode.flatMapLatest { m -> m?.let { transportFor(it).status } ?: flowOf(LinkStatus()) }
}

class ReceiverApp : Application() {
    val graph by lazy { AppGraph(this) }
}

val Context.graph: AppGraph get() = (applicationContext as ReceiverApp).graph

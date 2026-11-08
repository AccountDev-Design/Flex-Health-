package com.flexhealth.heartmonitor.receiver.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flexhealth.heartmonitor.engine.MonitorSnapshot
import com.flexhealth.heartmonitor.lan.PairingRequest
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.crypto.PairingRecord
import com.flexhealth.heartmonitor.receiver.data.MonitorMode
import com.flexhealth.heartmonitor.receiver.data.OverlayPrefs
import com.flexhealth.heartmonitor.receiver.data.ReceiverConfig
import com.flexhealth.heartmonitor.receiver.graph
import com.flexhealth.heartmonitor.receiver.service.MonitorService
import com.flexhealth.heartmonitor.receiver.transport.PairingUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    val engine = g.engine

    val config: StateFlow<ReceiverConfig?> = g.settings.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val snapshot: StateFlow<MonitorSnapshot> = g.engine.snapshot
    val link: StateFlow<LinkStatus> = g.linkStatus.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinkStatus())
    val running: StateFlow<Boolean> = g.activeMode.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, g.activeMode.value != null)
    val pairing: StateFlow<PairingUiState> = g.lan.pairing
    val paired: StateFlow<List<PairingRecord>> = g.lan.paired

    fun start() = MonitorService.start(getApplication())
    fun stop() = MonitorService.stop(getApplication())

    fun setMode(m: MonitorMode) = viewModelScope.launch { g.settings.setMode(m) }
    fun setWindow(sec: Int) = viewModelScope.launch { g.settings.setGraphWindow(sec) }
    fun updateOverlay(f: (OverlayPrefs) -> OverlayPrefs) = viewModelScope.launch { g.settings.updateOverlay(f) }

    /** El servidor local debe estar activo para emparejar: arranca el servicio en modo tablet. */
    fun openPairing() = viewModelScope.launch {
        g.settings.setMode(MonitorMode.TABLET_WIFI)
        MonitorService.start(getApplication())
        // Espera a que el servidor arranque antes de abrir la ventana.
        repeat(50) { if (g.lan.port != 0) { g.lan.openPairingWindow(); return@launch }; kotlinx.coroutines.delay(100) }
    }
    fun closePairing() = g.lan.closePairingWindow()
    fun accept(r: PairingRequest) = g.lan.accept(r)
    fun reject(r: PairingRequest) = g.lan.reject(r)
    fun forget(r: PairingRecord) = g.lan.forget(r)
    fun localAddress(): String? = g.lan.localAddress()
    fun serverPort(): Int = g.lan.port
}

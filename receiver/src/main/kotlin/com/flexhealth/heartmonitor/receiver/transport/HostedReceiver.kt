package com.flexhealth.heartmonitor.receiver.transport

import android.util.Log
import com.flexhealth.heartmonitor.lan.LanServerListener
import com.flexhealth.heartmonitor.lan.PairingHost
import com.flexhealth.heartmonitor.lan.PairingRequest
import com.flexhealth.heartmonitor.lan.PeerInfo
import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.crypto.PairingRecord
import com.flexhealth.heartmonitor.protocol.crypto.PairingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PairingUiState(
    val windowOpen: Boolean = false,
    val secondsLeft: Int = 0,
    val request: PairingRequest? = null,
    val lastResult: String? = null,
)

/** Relojes emparejados (compartidos por Wi-Fi y Bluetooth LE: la misma clave sirve para ambos). */
class PairedDevices(private val store: PairingStore) {
    private val _list = MutableStateFlow(store.all())
    val list: StateFlow<List<PairingRecord>> = _list.asStateFlow()
    fun refresh() { _list.value = store.all() }
    fun delete(record: PairingRecord) { store.delete(record.pairingId); refresh() }
}

/**
 * Base de los receptores que actúan como servidor del protocolo seguro (Wi-Fi y Bluetooth LE): estado del
 * enlace, ventana de emparejamiento, solicitudes y relojes emparejados. La lógica es la que antes tenía
 * LanReceiver, ahora compartida.
 */
abstract class HostedReceiver(protected val store: PairingStore, protected val paired: PairedDevices) : ReceiverTransport {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    protected val _status = MutableStateFlow(LinkStatus(LinkState.IDLE))
    override val status: StateFlow<LinkStatus> = _status.asStateFlow()
    private val _pairing = MutableStateFlow(PairingUiState())
    val pairing: StateFlow<PairingUiState> = _pairing.asStateFlow()
    @Volatile protected var sink: ((HeartRatePacket) -> Unit)? = null
    private var windowJob: Job? = null

    /** Servidor activo (o `null` si el transporte está detenido). */
    protected abstract val pairingHost: PairingHost?

    /** `true` cuando el servidor está listo para aceptar relojes. */
    abstract val ready: Boolean

    /** Texto de espera específico del transporte (IP:puerto, «anunciando por Bluetooth»…). */
    protected abstract fun waitingDetail(base: String): String

    protected val listener = object : LanServerListener {
        override fun onPairingRequest(request: PairingRequest) { _pairing.value = _pairing.value.copy(request = request) }
        override fun onPairingFinished(requestId: Int, record: PairingRecord?, error: String?) {
            paired.refresh()
            _pairing.value = PairingUiState(lastResult = record?.let { "Emparejado con ${it.peerName}" } ?: error)
            if (record != null) closePairingWindow()
        }
        override fun onClientAuthenticated(peer: PeerInfo) { _status.value = LinkStatus(LinkState.CONNECTED, peer.name, peer.address) }
        override fun onPacket(peer: PeerInfo, packet: HeartRatePacket) { sink?.invoke(packet) }
        override fun onRtt(peer: PeerInfo, rttMs: Long) { _status.value = _status.value.copy(rttMs = rttMs) }
        override fun onClientDisconnected(peer: PeerInfo?, reason: String) { waiting("Reloj desconectado; esperando reconexión") }
        override fun onServerError(message: String) { Log.w(javaClass.simpleName, message) }
    }

    protected fun waiting(detail: String) {
        _status.value = LinkStatus(LinkState.SEARCHING, detail = waitingDetail(detail))
    }

    /** Abre la ventana de emparejamiento durante [seconds] segundos. */
    fun openPairingWindow(seconds: Int = 120) {
        pairingHost?.pairingOpen = true
        windowJob?.cancel()
        windowJob = scope.launch {
            for (left in seconds downTo 1) {
                _pairing.value = _pairing.value.copy(windowOpen = true, secondsLeft = left)
                delay(1_000)
            }
            closePairingWindow()
        }
    }

    fun closePairingWindow() {
        pairingHost?.pairingOpen = false
        windowJob?.cancel()
        _pairing.value = _pairing.value.copy(windowOpen = false, secondsLeft = 0)
    }

    fun accept(request: PairingRequest) { pairingHost?.acceptPairing(request.id) }
    fun reject(request: PairingRequest) { pairingHost?.rejectPairing(request.id) }

    /** Corta la sesión activa si pertenece a [record] (al olvidar un reloj). */
    fun disconnectIfActive(record: PairingRecord) {
        val h = pairingHost ?: return
        if (h.activePeer?.pairingId == record.pairingId) h.disconnectActive("pairing removed")
    }
}

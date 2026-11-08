package com.flexhealth.heartmonitor.receiver.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.flexhealth.heartmonitor.core.android.DeviceIdentity
import com.flexhealth.heartmonitor.core.android.NsdAdvertiser
import com.flexhealth.heartmonitor.lan.LanServer
import com.flexhealth.heartmonitor.lan.LanServerConfig
import com.flexhealth.heartmonitor.lan.LanServerListener
import com.flexhealth.heartmonitor.lan.PairingRequest
import com.flexhealth.heartmonitor.lan.PeerInfo
import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
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
import java.net.BindException
import java.net.Inet4Address

data class PairingUiState(
    val windowOpen: Boolean = false,
    val secondsLeft: Int = 0,
    val request: PairingRequest? = null,
    val lastResult: String? = null,
)

/**
 * Servidor local de la tablet: WebSocket autenticado + anuncio mDNS. Funciona sin Internet y sin
 * teléfono: solo necesita que el reloj y la tablet estén en la misma red Wi-Fi.
 */
class LanReceiver(private val context: Context, private val store: PairingStore) : ReceiverTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val advertiser = NsdAdvertiser(context)
    private val _status = MutableStateFlow(LinkStatus(LinkState.IDLE))
    override val status: StateFlow<LinkStatus> = _status.asStateFlow()
    private val _pairing = MutableStateFlow(PairingUiState())
    val pairing: StateFlow<PairingUiState> = _pairing.asStateFlow()
    private val _paired = MutableStateFlow(store.all())
    val paired: StateFlow<List<PairingRecord>> = _paired.asStateFlow()

    @Volatile private var server: LanServer? = null
    @Volatile private var sink: ((HeartRatePacket) -> Unit)? = null
    private var startJob: Job? = null
    private var windowJob: Job? = null
    @Volatile var port: Int = 0; private set

    private val listener = object : LanServerListener {
        override fun onPairingRequest(request: PairingRequest) { _pairing.value = _pairing.value.copy(request = request) }
        override fun onPairingFinished(requestId: Int, record: PairingRecord?, error: String?) {
            _paired.value = store.all()
            _pairing.value = PairingUiState(lastResult = record?.let { "Emparejado con ${it.peerName}" } ?: error)
            if (record != null) closePairingWindow()
        }
        override fun onClientAuthenticated(peer: PeerInfo) { _status.value = LinkStatus(LinkState.CONNECTED, peer.name, peer.address) }
        override fun onPacket(peer: PeerInfo, packet: HeartRatePacket) { sink?.invoke(packet) }
        override fun onRtt(peer: PeerInfo, rttMs: Long) { _status.value = _status.value.copy(rttMs = rttMs) }
        override fun onClientDisconnected(peer: PeerInfo?, reason: String) { waiting("Reloj desconectado; esperando reconexión") }
        override fun onServerError(message: String) { Log.w(TAG, message) }
    }

    override fun start(sink: (HeartRatePacket) -> Unit) {
        this.sink = sink
        if (server != null || startJob?.isActive == true) return
        _status.value = LinkStatus(LinkState.SEARCHING, detail = "Iniciando servidor local")
        startJob = scope.launch {
            val id = DeviceIdentity.id(context)
            val name = DeviceIdentity.displayName()
            val s = try {
                LanServer(LanServerConfig(id, name, ProtocolConstants.LAN_DEFAULT_PORT), store, listener).also { port = it.start() }
            } catch (e: BindException) {
                // Puerto ocupado: se usa uno efímero (se anuncia igualmente por mDNS).
                LanServer(LanServerConfig(id, name, 0), store, listener).also { port = it.start() }
            } catch (e: Exception) {
                _status.value = LinkStatus(LinkState.ERROR, detail = "No se pudo abrir el servidor: ${e.message}"); return@launch
            }
            server = s
            advertiser.register("FlexHeart ${DeviceIdentity.model}", port, id) { ok, err ->
                if (!ok) Log.w(TAG, "mDNS no disponible: $err (el reloj puede usar la IP manual)")
            }
            waiting("Esperando al reloj")
        }
    }

    private fun waiting(detail: String) {
        _status.value = LinkStatus(LinkState.SEARCHING, detail = "$detail · ${localAddress() ?: "sin Wi-Fi"}:$port")
    }

    /** Dirección IPv4 de la tablet en la red Wi-Fi (para la entrada manual en el reloj). */
    fun localAddress(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return null
        return cm.getLinkProperties(net)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
    }

    /** Abre la ventana de emparejamiento durante [seconds] segundos. */
    fun openPairingWindow(seconds: Int = 120) {
        server?.pairingOpen = true
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
        server?.pairingOpen = false
        windowJob?.cancel()
        _pairing.value = _pairing.value.copy(windowOpen = false, secondsLeft = 0)
    }

    fun accept(request: PairingRequest) { server?.acceptPairing(request.id) }
    fun reject(request: PairingRequest) { server?.rejectPairing(request.id) }

    fun forget(record: PairingRecord) {
        store.delete(record.pairingId)
        if (server?.activePeer?.pairingId == record.pairingId) server?.disconnectActive("pairing removed")
        _paired.value = store.all()
    }

    override fun stop() {
        startJob?.cancel()
        closePairingWindow()
        advertiser.unregister()
        server?.close()
        server = null
        port = 0
        sink = null
        _status.value = LinkStatus(LinkState.IDLE)
    }

    private companion object { const val TAG = "LanReceiver" }
}

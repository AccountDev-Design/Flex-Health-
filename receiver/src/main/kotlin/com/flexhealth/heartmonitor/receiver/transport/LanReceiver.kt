package com.flexhealth.heartmonitor.receiver.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.flexhealth.heartmonitor.core.android.DeviceIdentity
import com.flexhealth.heartmonitor.core.android.NsdAdvertiser
import com.flexhealth.heartmonitor.lan.LanServer
import com.flexhealth.heartmonitor.lan.LanServerConfig
import com.flexhealth.heartmonitor.lan.PairingHost
import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import com.flexhealth.heartmonitor.protocol.crypto.PairingStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.BindException
import java.net.Inet4Address

/**
 * Servidor local de la tablet: WebSocket autenticado + anuncio mDNS. Funciona sin Internet y sin
 * teléfono: solo necesita que el reloj y la tablet estén en la misma red Wi-Fi.
 */
class LanReceiver(private val context: Context, store: PairingStore, paired: PairedDevices) : HostedReceiver(store, paired) {
    private val advertiser = NsdAdvertiser(context)

    @Volatile private var server: LanServer? = null
    private var startJob: Job? = null
    @Volatile var port: Int = 0; private set

    override val pairingHost: PairingHost? get() = server
    override val ready: Boolean get() = port != 0

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

    override fun waitingDetail(base: String) = "$base · ${localAddress() ?: "sin Wi-Fi"}:$port"

    /** Dirección IPv4 de la tablet en la red Wi-Fi (para la entrada manual en el reloj). */
    fun localAddress(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return null
        return cm.getLinkProperties(net)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
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

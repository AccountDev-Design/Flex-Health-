package com.flexhealth.heartmonitor.wear.transport

import android.content.Context
import com.flexhealth.heartmonitor.core.android.NsdBrowser
import com.flexhealth.heartmonitor.lan.LanClients
import com.flexhealth.heartmonitor.lan.LanEndpoint
import com.flexhealth.heartmonitor.lan.LanLink
import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.crypto.PairingRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Envío directo reloj → tablet por Wi-Fi local: obtiene una red Wi-Fi, localiza la tablet
 * emparejada por mDNS (o usa la última dirección conocida) y mantiene un [LanLink] autenticado.
 * No pasa por el teléfono ni por Internet.
 */
class LanSender(
    context: Context,
    private val scope: CoroutineScope,
    private val record: PairingRecord,
    private val lastKnown: LanEndpoint?,
    private val onEndpointResolved: suspend (LanEndpoint) -> Unit,
) {
    private val wifi = WifiNetworkRequester(context)
    private val nsd = NsdBrowser(context)
    private val client: OkHttpClient = LanClients.okHttp(OkHttpClient.Builder().socketFactory(wifi.socketFactory))
    private val _status = MutableStateFlow(LinkStatus(LinkState.IDLE, record.peerName))
    val status: StateFlow<LinkStatus> = _status.asStateFlow()
    private var link: LanLink? = null
    private var mirror: Job? = null

    @Volatile private var cached: LanEndpoint? = lastKnown
    @Volatile private var preferCached = true
    private var netWatch: Job? = null

    fun start() {
        if (link != null) return
        wifi.request()
        val l = LanLink(scope, client, record, resolveEndpoint = ::resolve)
        link = l
        // Al recuperar la red Wi-Fi se reintenta al momento, sin esperar el backoff (causa C4).
        netWatch = scope.launch {
            var had = wifi.network.value != null
            wifi.network.collect { n -> if (n != null && !had) l.reconnectNow(); had = n != null }
        }
        mirror = scope.launch {
            l.status.collect { s ->
                if (s.state == LinkState.CONNECTED) preferCached = true
                _status.value = if (wifi.network.value == null && s.state != LinkState.CONNECTED) {
                    s.copy(state = LinkState.UNAVAILABLE, detail = "Esperando red Wi-Fi del reloj")
                } else s
            }
        }
        l.start()
    }

    /**
     * Primero la última dirección que funcionó (reconexión inmediata, sin esperar a mDNS); si ese intento
     * falla, el siguiente busca la tablet por mDNS por si cambió de IP. Alterna hasta conectar.
     */
    private suspend fun resolve(): LanEndpoint? {
        val network = wifi.await(15_000) ?: return null
        val known = cached
        if (preferCached && known != null) { preferCached = false; return known }
        preferCached = true
        val found = nsd.find(record.peerId, network, 6_000)
        if (found != null) { cached = found; onEndpointResolved(found); return found }
        return known // mDNS bloqueado por el router: se intenta la última dirección conocida
    }

    fun send(packet: HeartRatePacket): Boolean = link?.send(packet) ?: false

    /** Cierre inmediato sin suspender (seguro en el hilo principal durante onDestroy). */
    fun close() {
        netWatch?.cancel(); netWatch = null
        mirror?.cancel(); mirror = null
        link?.close(); link = null
        wifi.release()
        nsd.close()
    }

    suspend fun stop() {
        netWatch?.cancel(); netWatch = null
        mirror?.cancel(); mirror = null
        link?.stop(); link = null
        wifi.release()
        nsd.close()
        _status.value = LinkStatus(LinkState.IDLE, record.peerName)
    }
}

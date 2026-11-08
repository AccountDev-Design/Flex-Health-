package com.flexhealth.heartmonitor.wear.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Solicita explícitamente una red Wi-Fi en el reloj. En Wear OS el Wi-Fi puede estar apagado
 * mientras hay Bluetooth con el teléfono; la documentación oficial indica pedir la red con
 * ConnectivityManager.requestNetwork(TRANSPORT_WIFI). Se elimina NET_CAPABILITY_INTERNET para
 * aceptar redes locales sin salida a Internet (p. ej. un router sin WAN o un hotspot local).
 *
 * El tráfico del WebSocket se ata a esa red mediante [socketFactory], de modo que nunca sale
 * por el proxy Bluetooth del teléfono.
 */
class WifiNetworkRequester(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _network = MutableStateFlow<Network?>(null)
    val network: StateFlow<Network?> = _network.asStateFlow()
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun request() {
        if (callback != null) return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _network.value = network }
            override fun onLost(network: Network) { if (_network.value == network) _network.value = null }
            override fun onUnavailable() { _network.value = null }
        }
        callback = cb
        cm.requestNetwork(req, cb)
    }

    @Synchronized
    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        _network.value = null
    }

    suspend fun await(timeoutMs: Long): Network? =
        _network.value ?: withTimeoutOrNull(timeoutMs) { network.filterNotNull().first() }

    /** SocketFactory que usa siempre la red Wi-Fi obtenida (o falla si no la hay). */
    val socketFactory: SocketFactory = object : SocketFactory() {
        private fun f(): SocketFactory = _network.value?.socketFactory ?: throw java.io.IOException("Sin red Wi-Fi")
        override fun createSocket(): Socket = f().createSocket()
        override fun createSocket(host: String, port: Int): Socket = f().createSocket(host, port)
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = f().createSocket(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress, port: Int): Socket = f().createSocket(host, port)
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            f().createSocket(address, port, localAddress, localPort)
    }
}

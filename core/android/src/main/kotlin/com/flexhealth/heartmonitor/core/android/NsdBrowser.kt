package com.flexhealth.heartmonitor.core.android

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import com.flexhealth.heartmonitor.lan.LanEndpoint
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.Executors
import kotlin.coroutines.resume

data class DiscoveredReceiver(val name: String, val receiverId: String?, val endpoint: LanEndpoint)

/** Descubre tablets Flex Heart en la red local mediante mDNS. */
class NsdBrowser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val resolveMutex = Mutex() // antes de API 34 solo se permite una resolución simultánea

    /** Lista viva de receptores descubiertos. [network]: red Wi-Fi a usar (API 33+), o la predeterminada. */
    fun discover(network: Network? = null): Flow<List<DiscoveredReceiver>> = callbackFlow {
        val found = LinkedHashMap<String, DiscoveredReceiver>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close(IllegalStateException("mDNS error $errorCode")) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                // Resolución en el ámbito del flujo (no en el hilo de callbacks de NSD).
                launch {
                    val r = withTimeoutOrNull(5_000) { resolve(info) }
                    if (r != null) synchronized(found) { found[info.serviceName] = r; trySend(found.values.toList()) }
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) { found.remove(info.serviceName); trySend(found.values.toList()) }
            }
        }
        trySend(emptyList())
        if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            nsd.discoverServices(ProtocolConstants.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, network, executor, listener)
        } else {
            nsd.discoverServices(ProtocolConstants.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    /** Busca un receptor concreto por su identificador (anunciado en el TXT "rid"). */
    suspend fun find(receiverId: String, network: Network?, timeoutMs: Long): LanEndpoint? = withTimeoutOrNull(timeoutMs) {
        discover(network).mapNotNull { list -> list.firstOrNull { it.receiverId == receiverId }?.endpoint }.firstOrNull()
    }

    private suspend fun resolve(info: NsdServiceInfo): DiscoveredReceiver? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) resolveModern(info) else resolveMutex.withLock { resolveLegacy(info) }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun resolveModern(info: NsdServiceInfo): DiscoveredReceiver? = suspendCancellableCoroutine { cont ->
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) { if (cont.isActive) cont.resume(null) }
            override fun onServiceUpdated(s: NsdServiceInfo) {
                val addr = s.hostAddresses.firstOrNull { it is Inet4Address } ?: s.hostAddresses.firstOrNull()
                if (addr != null && cont.isActive) {
                    runCatching { nsd.unregisterServiceInfoCallback(this) }
                    cont.resume(toReceiver(s, addr.hostAddress ?: return))
                }
            }
            override fun onServiceLost() = Unit
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        nsd.registerServiceInfoCallback(info, executor, cb)
        cont.invokeOnCancellation { runCatching { nsd.unregisterServiceInfoCallback(cb) } }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveLegacy(info: NsdServiceInfo): DiscoveredReceiver? = suspendCancellableCoroutine { cont ->
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
            override fun onServiceResolved(s: NsdServiceInfo) {
                val host = s.host?.hostAddress
                if (cont.isActive) cont.resume(host?.let { toReceiver(s, it) })
            }
        })
    }

    private fun toReceiver(s: NsdServiceInfo, host: String): DiscoveredReceiver {
        val rid = s.attributes[ProtocolConstants.NSD_TXT_RECEIVER_ID]?.decodeToString()
        return DiscoveredReceiver(s.serviceName, rid, LanEndpoint(host, s.port))
    }

    fun close() { executor.shutdownNow() }
}

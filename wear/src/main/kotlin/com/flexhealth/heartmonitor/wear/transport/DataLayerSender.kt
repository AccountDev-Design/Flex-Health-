package com.flexhealth.heartmonitor.wear.transport

import android.content.Context
import android.util.Log
import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.PacketCodec
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicLong

/**
 * Envío al teléfono emparejado mediante Wear Data Layer (MessageClient).
 *
 * Data Layer usa Bluetooth cuando hay conexión directa y, si no, puede enrutar por Wi-Fi/LTE a
 * través de servidores de Google. [LinkStatus.nearby] (Node.isNearby) indica si el nodo está
 * conectado directamente. Para la ruta estrictamente local hacia la tablet se usa LanSender.
 */
class DataLayerSender(context: Context, private val scope: CoroutineScope) {
    private val capabilityClient = Wearable.getCapabilityClient(context)
    private val messageClient = Wearable.getMessageClient(context)
    @Volatile private var nodes: Set<Node> = emptySet()
    private val _status = MutableStateFlow(LinkStatus(LinkState.IDLE))
    val status: StateFlow<LinkStatus> = _status.asStateFlow()
    val sent = AtomicLong(0)
    val failed = AtomicLong(0)

    private val listener = CapabilityClient.OnCapabilityChangedListener { info -> update(info) }

    fun start() {
        _status.value = LinkStatus(LinkState.SEARCHING)
        capabilityClient.addListener(listener, ProtocolConstants.DATA_LAYER_CAPABILITY_RECEIVER)
        scope.launch {
            try {
                update(capabilityClient.getCapability(ProtocolConstants.DATA_LAYER_CAPABILITY_RECEIVER, CapabilityClient.FILTER_REACHABLE).await())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Data Layer no disponible", e)
                _status.value = LinkStatus(LinkState.UNAVAILABLE, detail = e.message)
            }
        }
    }

    private fun update(info: CapabilityInfo) {
        nodes = info.nodes
        val best = info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
        _status.value = if (best == null) {
            LinkStatus(LinkState.UNAVAILABLE, detail = "Teléfono no alcanzable o app no instalada")
        } else {
            LinkStatus(LinkState.CONNECTED, best.displayName, nearby = best.isNearby)
        }
    }

    fun send(packet: HeartRatePacket) {
        val targets = nodes
        if (targets.isEmpty()) return
        val bytes = PacketCodec.encode(packet)
        for (node in targets) {
            messageClient.sendMessage(node.id, ProtocolConstants.DATA_LAYER_PATH_HEART_RATE, bytes)
                .addOnSuccessListener { sent.incrementAndGet() }
                .addOnFailureListener {
                    failed.incrementAndGet()
                    _status.value = _status.value.copy(detail = "Error de envío: ${it.message}")
                }
        }
    }

    fun stop() {
        capabilityClient.removeListener(listener, ProtocolConstants.DATA_LAYER_CAPABILITY_RECEIVER)
        nodes = emptySet()
        _status.value = LinkStatus(LinkState.IDLE)
    }

    private companion object { const val TAG = "DataLayerSender" }
}

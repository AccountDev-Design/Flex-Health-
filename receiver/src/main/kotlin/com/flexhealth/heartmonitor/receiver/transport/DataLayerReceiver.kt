package com.flexhealth.heartmonitor.receiver.transport

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.PacketCodec
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Recepción desde el reloj emparejado por Wear Data Layer. Solo la app del reloj con el mismo
 * applicationId y la misma firma puede enviar mensajes a esta ruta (garantía de la plataforma).
 * [LinkStatus.nearby] = Node.isNearby: `true` indica conexión directa (normalmente Bluetooth).
 */
class DataLayerReceiver(context: Context) : ReceiverTransport {
    private val messages = Wearable.getMessageClient(context)
    private val capabilities = Wearable.getCapabilityClient(context)
    private val _status = MutableStateFlow(LinkStatus(LinkState.IDLE))
    override val status: StateFlow<LinkStatus> = _status.asStateFlow()
    private var sink: ((HeartRatePacket) -> Unit)? = null
    @Volatile var lastMessageElapsed: Long = 0; private set
    @Volatile var rejected: Long = 0; private set

    private val messageListener = MessageClient.OnMessageReceivedListener { ev: MessageEvent ->
        if (ev.path != ProtocolConstants.DATA_LAYER_PATH_HEART_RATE) return@OnMessageReceivedListener
        val packet = PacketCodec.decode(ev.data)
        if (packet == null) { rejected++; return@OnMessageReceivedListener }
        lastMessageElapsed = SystemClock.elapsedRealtime()
        sink?.invoke(packet)
    }

    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { update(it) }

    override fun start(sink: (HeartRatePacket) -> Unit) {
        this.sink = sink
        _status.value = LinkStatus(LinkState.SEARCHING, detail = "Buscando el reloj emparejado")
        val uri = Uri.Builder().scheme("wear").path(ProtocolConstants.DATA_LAYER_PATH_HEART_RATE).build()
        messages.addListener(messageListener, uri, MessageClient.FILTER_LITERAL)
        capabilities.addListener(capabilityListener, WATCH_CAPABILITY)
        capabilities.getCapability(WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { update(it) }
            .addOnFailureListener {
                Log.w(TAG, "Data Layer no disponible", it)
                _status.value = LinkStatus(LinkState.UNAVAILABLE, detail = "Wear OS / Google Play Services no disponible: ${it.message}")
            }
    }

    private fun update(info: CapabilityInfo) {
        val node = info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
        _status.value = if (node == null) {
            LinkStatus(LinkState.UNAVAILABLE, detail = "Reloj no conectado o app Flex Heart no instalada en el reloj")
        } else {
            LinkStatus(LinkState.CONNECTED, peerName = node.displayName, nearby = node.isNearby,
                detail = if (node.isNearby) "Conexión directa" else "Ruta indirecta (red/nube de Google)")
        }
    }

    override fun stop() {
        messages.removeListener(messageListener)
        capabilities.removeListener(capabilityListener, WATCH_CAPABILITY)
        sink = null
        _status.value = LinkStatus(LinkState.IDLE)
    }

    private companion object {
        const val TAG = "DataLayerReceiver"
        /** Declarada por la app del reloj en wear/src/main/res/values/wear.xml. */
        const val WATCH_CAPABILITY = "flex_heart_source"
    }
}

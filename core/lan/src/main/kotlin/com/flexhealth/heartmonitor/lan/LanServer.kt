package com.flexhealth.heartmonitor.lan

import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LanErrorCodes
import com.flexhealth.heartmonitor.protocol.LanMessage
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import com.flexhealth.heartmonitor.protocol.crypto.PairingRecord
import com.flexhealth.heartmonitor.protocol.crypto.PairingStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

data class LanServerConfig(
    val receiverId: String,
    val receiverName: String,
    /** 0 = puerto efímero. El puerto real se anuncia por mDNS. */
    val port: Int = ProtocolConstants.LAN_DEFAULT_PORT,
    /** `null` = todas las interfaces de la red local. */
    val bindHost: String? = null,
    val authTimeoutMs: Long = 10_000,
    /**
     * Cierra una sesión autenticada si no llega NINGUNA trama del reloj en este tiempo. Sustituye la
     * detección de Java-WebSocket, que solo cuenta los PONG de WebSocket (no las muestras ni los pings
     * cifrados) y cortaba relojes activos con la CPU suspendida (causa C1, ver LivenessEvidenceTest).
     */
    val idleTimeoutMs: Long = 45_000,
    val pairingTimeoutMs: Long = 180_000,
    val maxAuthFailuresPerMinute: Int = 5,
) {
    fun session() = SessionConfig(receiverId, receiverName, authTimeoutMs, idleTimeoutMs, pairingTimeoutMs, maxAuthFailuresPerMinute)
}

data class PeerInfo(val pairingId: String, val name: String, val address: String)

data class PairingRequest(val id: Int, val deviceName: String, val code: String, val address: String)

interface LanServerListener {
    fun onPairingRequest(request: PairingRequest) {}
    fun onPairingFinished(requestId: Int, record: PairingRecord?, error: String?) {}
    fun onClientAuthenticated(peer: PeerInfo) {}
    fun onPacket(peer: PeerInfo, packet: HeartRatePacket) {}
    fun onRtt(peer: PeerInfo, rttMs: Long) {}
    fun onClientDisconnected(peer: PeerInfo?, reason: String) {}
    fun onServerError(message: String) {}
}

/**
 * Servidor WebSocket local de la tablet. No usa Internet ni servidores externos.
 *
 * Seguridad: un cliente solo puede enviar datos tras demostrar posesión de una clave de
 * emparejamiento (reto-respuesta HMAC con nonces frescos). Los datos viajan cifrados con
 * AES-256-GCM. El emparejamiento solo se acepta mientras el usuario mantiene abierta la
 * ventana de emparejamiento ([pairingOpen]) y confirma el código en ambos dispositivos.
 * La lógica de sesión vive en [SessionHost] (compartida con Bluetooth LE).
 */
class LanServer(
    private val config: LanServerConfig,
    store: PairingStore,
    private val listener: LanServerListener,
    clock: () -> Long = System::currentTimeMillis,
) : PairingHost {
    private val host = SessionHost(config.session(), store, listener, clock, "flexheart-lan-timer")
    private var server: Impl? = null

    override var pairingOpen: Boolean
        get() = host.pairingOpen
        set(v) { host.pairingOpen = v }

    override val activePeer: PeerInfo? get() = host.activePeer

    /** Arranca el servidor y devuelve el puerto real. */
    suspend fun start(timeoutMs: Long = 5_000): Int {
        check(server == null) { "Servidor ya iniciado" }
        val started = CompletableDeferred<Int>()
        val address = config.bindHost?.let { InetSocketAddress(it, config.port) } ?: InetSocketAddress(config.port)
        val impl = Impl(address, started)
        impl.isReuseAddr = true
        // La detección de Java-WebSocket solo cuenta PONG de WebSocket y cortaba relojes activos (causa C1):
        // la vida de la sesión la vigila SessionHost con idleTimeoutMs sobre cualquier trama.
        impl.connectionLostTimeout = 0
        impl.setMaxPendingConnections(8)
        host.start()
        server = impl
        impl.start()
        return try {
            withTimeout(timeoutMs) { started.await() }
        } catch (e: Throwable) {
            stop(); throw e
        }
    }

    fun stop() {
        val s = server ?: return
        server = null
        host.stop()
        try { s.stop(1_000, "server stopping") } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    /** Cierra el ejecutor de temporizadores. Llamar una vez al destruir el servidor. */
    fun close() { stop(); host.close() }

    override fun acceptPairing(requestId: Int) = host.acceptPairing(requestId)

    override fun rejectPairing(requestId: Int) = host.rejectPairing(requestId)

    /** Desconecta al cliente activo (p. ej. al borrar su emparejamiento). */
    override fun disconnectActive(reason: String) = host.disconnectActive(reason)

    private class WsPipe(private val ws: WebSocket, override val address: String) : ServerPipe {
        override val isOpen: Boolean get() = ws.isOpen
        override fun send(text: String) { if (ws.isOpen) ws.send(text) }
        override fun close(reason: String) = ws.close(if (reason == "bye" || reason == "paired" || reason == "rejected") 1000 else 1008, reason)
    }

    private inner class Impl(address: InetSocketAddress, private val started: CompletableDeferred<Int>) :
        WebSocketServer(address, listOf(Draft_6455(emptyList(), ProtocolConstants.LAN_MAX_FRAME_BYTES))) {

        override fun onStart() { started.complete(port) }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val address = conn.remoteSocketAddress?.address?.hostAddress ?: "?"
            if (handshake.resourceDescriptor != ProtocolConstants.LAN_WS_PATH) { conn.close(1008, "bad path"); return }
            host.attach(WsPipe(conn, address))?.let { conn.setAttachment(it) }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            conn.getAttachment<SessionHost.Session>()?.onText(message) ?: conn.close(1008, "no state")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            conn.getAttachment<SessionHost.Session>()?.onClosed("code=$code ${reason.orEmpty()}")
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            if (conn == null) {
                if (!started.isCompleted) started.completeExceptionally(ex) else listener.onServerError(ex.message ?: ex.toString())
            }
        }
    }
}

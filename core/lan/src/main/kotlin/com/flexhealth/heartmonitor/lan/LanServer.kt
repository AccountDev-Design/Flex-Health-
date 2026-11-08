package com.flexhealth.heartmonitor.lan

import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LanErrorCodes
import com.flexhealth.heartmonitor.protocol.LanMessage
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import com.flexhealth.heartmonitor.protocol.SecureBody
import com.flexhealth.heartmonitor.protocol.crypto.CryptoBytes
import com.flexhealth.heartmonitor.protocol.crypto.PairingRecord
import com.flexhealth.heartmonitor.protocol.crypto.PairingResponder
import com.flexhealth.heartmonitor.protocol.crypto.PairingStore
import com.flexhealth.heartmonitor.protocol.crypto.SecureChannel
import com.flexhealth.heartmonitor.protocol.crypto.SecurityProtocolException
import com.flexhealth.heartmonitor.protocol.crypto.SessionKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class LanServerConfig(
    val receiverId: String,
    val receiverName: String,
    /** 0 = puerto efímero. El puerto real se anuncia por mDNS. */
    val port: Int = ProtocolConstants.LAN_DEFAULT_PORT,
    /** `null` = todas las interfaces de la red local. */
    val bindHost: String? = null,
    val authTimeoutMs: Long = 10_000,
    val pairingTimeoutMs: Long = 180_000,
    val maxAuthFailuresPerMinute: Int = 5,
)

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
 */
class LanServer(
    private val config: LanServerConfig,
    private val store: PairingStore,
    private val listener: LanServerListener,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile var pairingOpen: Boolean = false

    private val timers = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "flexheart-lan-timer").apply { isDaemon = true } }
    private val requestIds = AtomicInteger(0)
    private val pendingPairings = ConcurrentHashMap<Int, Conn>()
    private val authFailures = ConcurrentHashMap<String, MutableList<Long>>()
    @Volatile private var active: Conn? = null
    private var server: Impl? = null

    val activePeer: PeerInfo? get() = active?.peer

    /** Arranca el servidor y devuelve el puerto real. */
    suspend fun start(timeoutMs: Long = 5_000): Int {
        check(server == null) { "Servidor ya iniciado" }
        val started = CompletableDeferred<Int>()
        val address = config.bindHost?.let { InetSocketAddress(it, config.port) } ?: InetSocketAddress(config.port)
        val impl = Impl(address, started)
        impl.isReuseAddr = true
        impl.connectionLostTimeout = 15
        impl.setMaxPendingConnections(8)
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
        active = null
        pendingPairings.clear()
        try { s.stop(1_000, "server stopping") } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    /** Cierra el ejecutor de temporizadores. Llamar una vez al destruir el servidor. */
    fun close() { stop(); timers.shutdownNow() }

    fun acceptPairing(requestId: Int) { pendingPairings[requestId]?.userAccepted() }

    fun rejectPairing(requestId: Int) { pendingPairings.remove(requestId)?.userRejected() }

    /** Desconecta al cliente activo (p. ej. al borrar su emparejamiento). */
    fun disconnectActive(reason: String) { active?.closeWith(LanErrorCodes.REJECTED, reason) }

    private fun tooManyFailures(address: String): Boolean {
        val now = clock()
        val list = authFailures[address] ?: return false
        synchronized(list) {
            list.removeAll { now - it > 60_000 }
            return list.size >= config.maxAuthFailuresPerMinute
        }
    }

    private fun recordFailure(address: String) {
        val list = authFailures.getOrPut(address) { mutableListOf() }
        synchronized(list) { list.add(clock()) }
    }

    // ------------------------------------------------------------------------------------

    private inner class Conn(val ws: WebSocket, val address: String) {
        private var phase = Phase.NEW
        private var responder: PairingResponder? = null
        private var requestId: Int = -1
        private var userAccepted = false
        private var keys: SessionKeys? = null
        private var record: PairingRecord? = null
        private var serverNonce: ByteArray? = null
        private var channel: SecureChannel? = null
        private var timeout: ScheduledFuture<*>? = null
        private var closing = false
        var peer: PeerInfo? = null
            private set

        fun armTimeout(ms: Long) {
            timeout?.cancel(false)
            timeout = timers.schedule({ closeWith(LanErrorCodes.TIMEOUT, "handshake timeout") }, ms, TimeUnit.MILLISECONDS)
        }

        @Synchronized
        fun onText(text: String) {
            val msg = LanMessage.decode(text)
            if (msg == null) { closeWith(LanErrorCodes.PROTOCOL, "malformed"); return }
            try {
                when (phase) {
                    Phase.NEW -> onFirst(msg)
                    Phase.PAIR_COMMITTED -> {
                        val nonce = msg as? LanMessage.PairNonce ?: return protocolError()
                        send(responder!!.onNonce(nonce))
                        phase = Phase.PAIR_CODE
                        requestId = requestIds.incrementAndGet()
                        pendingPairings[requestId] = this
                        listener.onPairingRequest(
                            PairingRequest(requestId, responder!!.initiatorName ?: "?", responder!!.verificationCode!!, address),
                        )
                    }
                    Phase.PAIR_CODE -> {
                        when (msg) {
                            is LanMessage.PairConfirm -> { responder!!.onConfirm(msg); maybeFinishPairing() }
                            is LanMessage.Error -> { finishPairing(null, "El reloj canceló"); ws.close() }
                            else -> protocolError()
                        }
                    }
                    Phase.AWAIT_AUTH -> {
                        val auth = msg as? LanMessage.Auth ?: return protocolError()
                        val k = keys!!
                        if (!CryptoBytes.constantTimeEquals(CryptoBytes.unb64(auth.mac), k.clientProof())) {
                            recordFailure(address)
                            send(LanMessage.AuthResult(false, reason = LanErrorCodes.AUTH_FAILED))
                            closeWith(null, "auth failed")
                            return
                        }
                        timeout?.cancel(false)
                        channel = SecureChannel.forServer(k)
                        val rec = record!!
                        val p = PeerInfo(rec.pairingId, rec.peerName, address)
                        // Un único reloj activo: una reconexión del mismo reloj sustituye a la anterior.
                        val previous = active
                        if (previous != null && previous !== this) {
                            if (previous.peer?.pairingId == rec.pairingId) previous.closeWith(null, "replaced")
                            else { send(LanMessage.AuthResult(false, reason = LanErrorCodes.BUSY)); closeWith(null, "busy"); return }
                        }
                        peer = p
                        active = this
                        phase = Phase.AUTHENTICATED
                        send(LanMessage.AuthResult(true, CryptoBytes.b64(k.serverProof())))
                        listener.onClientAuthenticated(p)
                    }
                    Phase.AUTHENTICATED -> {
                        val secure = msg as? LanMessage.Secure ?: return protocolError()
                        val plain = channel!!.open(secure) ?: return closeWith(LanErrorCodes.PROTOCOL, "bad frame")
                        when (val body = SecureBody.decode(plain)) {
                            is SecureBody.Sample -> listener.onPacket(peer!!, body.packet)
                            is SecureBody.Ping -> {
                                sendSecure(SecureBody.Pong(body.sentAtMs))
                                body.lastRttMs?.let { listener.onRtt(peer!!, it) }
                            }
                            is SecureBody.Bye -> ws.close(1000, "bye")
                            is SecureBody.Pong, null -> protocolError()
                        }
                    }
                    Phase.CLOSED -> Unit
                }
            } catch (e: SecurityProtocolException) {
                if (phase == Phase.PAIR_CODE || phase == Phase.PAIR_COMMITTED) finishPairing(null, e.message)
                recordFailure(address)
                closeWith(LanErrorCodes.AUTH_FAILED, e.message)
            }
        }

        private fun onFirst(msg: LanMessage) {
            when (msg) {
                is LanMessage.PairHello -> {
                    if (!pairingOpen) return closeWith(LanErrorCodes.PAIRING_CLOSED, "pairing window closed")
                    if (pendingPairings.isNotEmpty()) return closeWith(LanErrorCodes.BUSY, "another pairing in progress")
                    val r = PairingResponder(config.receiverId, config.receiverName, clock)
                    responder = r
                    send(r.onHello(msg))
                    phase = Phase.PAIR_COMMITTED
                    armTimeout(config.pairingTimeoutMs)
                }
                is LanMessage.Hello -> {
                    if (msg.protocolVersion !in ProtocolConstants.MIN_SUPPORTED_VERSION..ProtocolConstants.PROTOCOL_VERSION) {
                        return closeWith(LanErrorCodes.UNSUPPORTED_VERSION, "version ${msg.protocolVersion}")
                    }
                    val rec = store.find(msg.pairingId)
                    val clientNonce = CryptoBytes.unb64(msg.clientNonce)
                    if (rec == null) { recordFailure(address); return closeWith(LanErrorCodes.UNKNOWN_PAIRING, "unknown pairing") }
                    if (clientNonce?.size != SessionKeys.NONCE_BYTES) return protocolError()
                    val sn = CryptoBytes.random(SessionKeys.NONCE_BYTES)
                    serverNonce = sn
                    record = rec
                    keys = SessionKeys.derive(rec.key(), rec.pairingId, clientNonce, sn)
                    phase = Phase.AWAIT_AUTH
                    send(LanMessage.Challenge(CryptoBytes.b64(sn), clock()))
                }
                else -> protocolError()
            }
        }

        @Synchronized
        fun userAccepted() { userAccepted = true; maybeFinishPairing() }

        @Synchronized
        fun userRejected() {
            responder?.let { send(it.reject("Rechazado en la tablet")) }
            finishPairing(null, "Rechazado en la tablet")
            ws.close(1000, "rejected")
        }

        private fun maybeFinishPairing() {
            val r = responder ?: return
            if (!userAccepted || !r.initiatorConfirmed) return
            val (result, rec) = r.accept()
            store.save(rec)
            send(result)
            finishPairing(rec, null)
            ws.close(1000, "paired")
        }

        private fun finishPairing(rec: PairingRecord?, error: String?) {
            if (requestId < 0) return
            pendingPairings.remove(requestId)
            timeout?.cancel(false)
            listener.onPairingFinished(requestId, rec, error)
            requestId = -1
        }

        private fun protocolError() = closeWith(LanErrorCodes.PROTOCOL, "unexpected message in $phase")

        fun send(msg: LanMessage) { if (ws.isOpen) ws.send(LanMessage.encode(msg)) }

        private fun sendSecure(body: SecureBody) { channel?.let { send(it.seal(SecureBody.encode(body))) } }

        @Synchronized
        fun closeWith(code: String?, message: String?) {
            if (phase == Phase.CLOSED || closing) return
            closing = true
            if (code != null) send(LanMessage.Error(code, message))
            ws.close(1008, message?.take(100) ?: code ?: "closed")
        }

        @Synchronized
        fun onClosed(reason: String) {
            if (phase == Phase.CLOSED) return
            val wasAuth = phase == Phase.AUTHENTICATED
            phase = Phase.CLOSED
            timeout?.cancel(false)
            finishPairing(null, "Conexión cerrada")
            if (active === this) active = null
            if (wasAuth) listener.onClientDisconnected(peer, reason)
        }
    }

    private enum class Phase { NEW, PAIR_COMMITTED, PAIR_CODE, AWAIT_AUTH, AUTHENTICATED, CLOSED }

    private inner class Impl(address: InetSocketAddress, private val started: CompletableDeferred<Int>) :
        WebSocketServer(address, listOf(Draft_6455(emptyList(), ProtocolConstants.LAN_MAX_FRAME_BYTES))) {

        override fun onStart() { started.complete(port) }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val address = conn.remoteSocketAddress?.address?.hostAddress ?: "?"
            if (handshake.resourceDescriptor != ProtocolConstants.LAN_WS_PATH) { conn.close(1008, "bad path"); return }
            if (tooManyFailures(address)) {
                conn.send(LanMessage.encode(LanMessage.Error(LanErrorCodes.RATE_LIMITED)))
                conn.close(1008, "rate limited"); return
            }
            val c = Conn(conn, address)
            conn.setAttachment(c)
            c.armTimeout(config.authTimeoutMs)
        }

        override fun onMessage(conn: WebSocket, message: String) {
            conn.getAttachment<Conn>()?.onText(message) ?: conn.close(1008, "no state")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            conn.getAttachment<Conn>()?.onClosed("code=$code ${reason.orEmpty()}")
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            if (conn == null) {
                if (!started.isCompleted) started.completeExceptionally(ex) else listener.onServerError(ex.message ?: ex.toString())
            }
        }
    }
}

package com.flexhealth.heartmonitor.lan

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Parámetros de sesión comunes a todos los transportes del receptor (Wi-Fi y Bluetooth LE). */
data class SessionConfig(
    val receiverId: String,
    val receiverName: String,
    val authTimeoutMs: Long = 10_000,
    val idleTimeoutMs: Long = 45_000,
    val pairingTimeoutMs: Long = 180_000,
    val maxAuthFailuresPerMinute: Int = 5,
)

/**
 * Lado servidor (tablet) del protocolo seguro, independiente del transporte: emparejamiento por
 * comparación numérica, autenticación mutua, canal AES-256-GCM con anti-replay, un único reloj activo,
 * límite de intentos fallidos y cierre por inactividad. Lo usan [LanServer] (WebSocket) y el servidor
 * GATT de la app receptora (Bluetooth LE). El código de sesión es el mismo que antes estaba en LanServer.
 */
class SessionHost(
    private val config: SessionConfig,
    private val store: PairingStore,
    private val listener: LanServerListener,
    private val clock: () -> Long = System::currentTimeMillis,
    threadName: String = "flexheart-session-timer",
) : PairingHost {
    @Volatile override var pairingOpen: Boolean = false

    private val timers = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, threadName).apply { isDaemon = true } }
    private val requestIds = AtomicInteger(0)
    private val pendingPairings = ConcurrentHashMap<Int, Session>()
    private val authFailures = ConcurrentHashMap<String, MutableList<Long>>()
    private val liveConns = ConcurrentHashMap.newKeySet<Session>()
    private var idleCheck: ScheduledFuture<*>? = null
    @Volatile private var active: Session? = null

    override val activePeer: PeerInfo? get() = active?.peer

    @Synchronized
    fun start() {
        if (idleCheck != null) return
        val period = (config.idleTimeoutMs / 3).coerceAtLeast(100)
        idleCheck = timers.scheduleWithFixedDelay({
            val now = System.nanoTime() / 1_000_000
            liveConns.forEach { it.closeIfIdle(now) }
        }, period, period, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stop() {
        idleCheck?.cancel(false); idleCheck = null
        active = null
        pendingPairings.clear()
        liveConns.clear()
    }

    /** Cierra el ejecutor de temporizadores. Llamar una vez al destruir el host. */
    fun close() { stop(); timers.shutdownNow() }

    /** Registra una conexión nueva del transporte. Devuelve `null` si la dirección está bloqueada temporalmente. */
    fun attach(pipe: ServerPipe): Session? {
        if (tooManyFailures(pipe.address)) {
            pipe.send(LanMessage.encode(LanMessage.Error(LanErrorCodes.RATE_LIMITED)))
            pipe.close("rate limited")
            return null
        }
        val c = Session(pipe)
        liveConns.add(c)
        c.armTimeout(config.authTimeoutMs)
        return c
    }

    override fun acceptPairing(requestId: Int) { pendingPairings[requestId]?.userAccepted() }

    override fun rejectPairing(requestId: Int) { pendingPairings.remove(requestId)?.userRejected() }

    /** Desconecta al cliente activo (p. ej. al borrar su emparejamiento). */
    override fun disconnectActive(reason: String) { active?.closeWith(LanErrorCodes.REJECTED, reason) }

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

    inner class Session internal constructor(private val pipe: ServerPipe) {
        val address: String get() = pipe.address
        internal var phase = Phase.NEW
        private var responder: PairingResponder? = null
        private var requestId: Int = -1
        private var userAccepted = false
        private var keys: SessionKeys? = null
        private var record: PairingRecord? = null
        private var serverNonce: ByteArray? = null
        private var channel: SecureChannel? = null
        private var timeout: ScheduledFuture<*>? = null
        private var closing = false
        @Volatile private var lastFrameAt = System.nanoTime() / 1_000_000

        fun closeIfIdle(nowMs: Long) {
            if (phase == Phase.AUTHENTICATED && nowMs - lastFrameAt > config.idleTimeoutMs) closeWith(LanErrorCodes.TIMEOUT, "idle")
        }
        var peer: PeerInfo? = null
            private set

        fun armTimeout(ms: Long) {
            timeout?.cancel(false)
            timeout = timers.schedule({ closeWith(LanErrorCodes.TIMEOUT, "handshake timeout") }, ms, TimeUnit.MILLISECONDS)
        }

        @Synchronized
        fun onText(text: String) {
            lastFrameAt = System.nanoTime() / 1_000_000
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
                            is LanMessage.Error -> { finishPairing(null, "El reloj canceló"); pipe.close("cancelled") }
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
                            is SecureBody.Bye -> pipe.close("bye")
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
            pipe.close("rejected")
        }

        private fun maybeFinishPairing() {
            val r = responder ?: return
            if (!userAccepted || !r.initiatorConfirmed) return
            val (result, rec) = r.accept()
            store.save(rec)
            send(result)
            finishPairing(rec, null)
            pipe.close("paired")
        }

        private fun finishPairing(rec: PairingRecord?, error: String?) {
            if (requestId < 0) return
            pendingPairings.remove(requestId)
            timeout?.cancel(false)
            listener.onPairingFinished(requestId, rec, error)
            requestId = -1
        }

        private fun protocolError() = closeWith(LanErrorCodes.PROTOCOL, "unexpected message in $phase")

        fun send(msg: LanMessage) { if (pipe.isOpen) pipe.send(LanMessage.encode(msg)) }

        private fun sendSecure(body: SecureBody) { channel?.let { send(it.seal(SecureBody.encode(body))) } }

        @Synchronized
        fun closeWith(code: String?, message: String?) {
            if (phase == Phase.CLOSED || closing) return
            closing = true
            if (code != null) send(LanMessage.Error(code, message))
            pipe.close(message?.take(100) ?: code ?: "closed")
        }

        @Synchronized
        fun onClosed(reason: String) {
            if (phase == Phase.CLOSED) return
            val wasAuth = phase == Phase.AUTHENTICATED
            phase = Phase.CLOSED
            liveConns.remove(this)
            timeout?.cancel(false)
            finishPairing(null, "Conexión cerrada")
            if (active === this) active = null
            if (wasAuth) listener.onClientDisconnected(peer, reason)
        }
    }

    internal enum class Phase { NEW, PAIR_COMMITTED, PAIR_CODE, AWAIT_AUTH, AUTHENTICATED, CLOSED }
}

/** Operaciones de emparejamiento y control que la UI de la tablet necesita, comunes a todos los transportes. */
interface PairingHost {
    var pairingOpen: Boolean
    val activePeer: PeerInfo?
    fun acceptPairing(requestId: Int)
    fun rejectPairing(requestId: Int)
    fun disconnectActive(reason: String)
}

/** Conexión del lado servidor de un transporte concreto (WebSocket o GATT). */
interface ServerPipe {
    /** Identificador del extremo remoto (IP o dirección Bluetooth), usado para limitar intentos. */
    val address: String
    val isOpen: Boolean
    fun send(text: String)
    fun close(reason: String)
}

package com.flexhealth.heartmonitor.lan

import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LanErrorCodes
import com.flexhealth.heartmonitor.protocol.LanMessage
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import com.flexhealth.heartmonitor.protocol.SecureBody
import com.flexhealth.heartmonitor.protocol.crypto.CryptoBytes
import com.flexhealth.heartmonitor.protocol.crypto.PairingInitiator
import com.flexhealth.heartmonitor.protocol.crypto.PairingRecord
import com.flexhealth.heartmonitor.protocol.crypto.SecureChannel
import com.flexhealth.heartmonitor.protocol.crypto.SecurityProtocolException
import com.flexhealth.heartmonitor.protocol.crypto.SessionKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class LanEndpoint(val host: String, val port: Int) {
    val url: String get() {
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "ws://$h:$port${ProtocolConstants.LAN_WS_PATH}"
    }
}

object LanClients {
    /** Cliente OkHttp adecuado para la red local. En el reloj se le inyecta el SocketFactory de la red Wi-Fi. */
    fun okHttp(builder: OkHttpClient.Builder = OkHttpClient.Builder()): OkHttpClient = builder
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS) // detecta conexiones muertas
        .retryOnConnectionFailure(false)
        .build()
}

/**
 * Emparejamiento desde el reloj. [onCode] muestra el código; [awaitUserConfirm] debe suspender
 * hasta que el usuario confirme (true) o cancele (false) en el reloj.
 */
suspend fun pairWithReceiver(
    client: OkHttpClient,
    endpoint: LanEndpoint,
    deviceId: String,
    deviceName: String,
    onCode: (code: String, receiverName: String) -> Unit,
    awaitUserConfirm: suspend () -> Boolean,
    stepTimeoutMs: Long = 10_000,
    decisionTimeoutMs: Long = 120_000,
): PairingRecord {
    val conn = WsConnection.open(client, endpoint.url, stepTimeoutMs)
    try {
        val initiator = PairingInitiator(deviceId, deviceName)
        conn.send(initiator.hello())
        val commit = conn.receive(stepTimeoutMs) as? LanMessage.PairCommit ?: throw LanException("Respuesta inesperada")
        conn.send(initiator.onCommit(commit))
        val reveal = conn.receive(stepTimeoutMs) as? LanMessage.PairReveal ?: throw LanException("Respuesta inesperada")
        val code = initiator.onReveal(reveal)
        onCode(code, initiator.peerName ?: "?")
        if (!awaitUserConfirm()) {
            conn.send(LanMessage.Error(LanErrorCodes.REJECTED, "Cancelado en el reloj"))
            throw LanException("Emparejamiento cancelado", LanErrorCodes.REJECTED)
        }
        conn.send(initiator.confirm())
        val result = conn.receive(decisionTimeoutMs) as? LanMessage.PairResult ?: throw LanException("Respuesta inesperada")
        return initiator.onResult(result)
    } catch (e: SecurityProtocolException) {
        throw LanException(e.message ?: "Error de seguridad", LanErrorCodes.AUTH_FAILED, e)
    } finally {
        conn.close()
    }
}

/**
 * Enlace persistente y autenticado reloj → tablet con reconexión automática (backoff exponencial).
 * Las muestras se envían en tiempo real; si el enlace está caído se descartan (al reconectar se
 * continúa con datos nuevos, sin reenviar lecturas antiguas).
 */
class LanLink(
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val record: PairingRecord,
    /** Resuelve la dirección actual de la tablet (mDNS o última conocida). `null` = no encontrada. */
    private val resolveEndpoint: suspend () -> LanEndpoint?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pingIntervalMs: Long = 5_000,
    private val stepTimeoutMs: Long = 8_000,
    private val maxBackoffMs: Long = 15_000,
) {
    private val _status = MutableStateFlow(LinkStatus(LinkState.IDLE, record.peerName))
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    @Volatile private var conn: WsConnection? = null
    @Volatile private var channel: SecureChannel? = null
    @Volatile private var lastRtt: Long? = null
    @Volatile private var authenticated = false
    private var job: Job? = null

    var sent: Long = 0; private set
    var dropped: Long = 0; private set

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    suspend fun stop() {
        val c = conn
        channel?.let { ch -> c?.send(ch.seal(SecureBody.encode(SecureBody.Bye("stopped")))) }
        job?.cancel(); job?.join(); job = null
        c?.close("stopped")
        conn = null; channel = null
        _status.value = LinkStatus(LinkState.IDLE, record.peerName)
    }

    /** Cierre inmediato sin suspender (p. ej. desde onDestroy en el hilo principal). */
    fun close() {
        job?.cancel(); job = null
        conn?.cancel(); conn = null; channel = null
        _status.value = LinkStatus(LinkState.IDLE, record.peerName)
    }

    /** Envía un paquete si el enlace está autenticado. Devuelve `false` si se descartó. */
    fun send(packet: HeartRatePacket): Boolean {
        val c = conn; val ch = channel
        if (c == null || ch == null) { dropped++; return false }
        val ok = synchronized(ch) { c.send(ch.seal(SecureBody.encode(SecureBody.Sample(packet)))) }
        if (ok) sent++ else dropped++
        return ok
    }

    private suspend fun loop() {
        var backoff = 1_000L
        while (true) {
            _status.value = LinkStatus(LinkState.SEARCHING, record.peerName)
            val endpoint = try { resolveEndpoint() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (endpoint == null) {
                _status.value = LinkStatus(LinkState.UNAVAILABLE, record.peerName, "Tablet no encontrada en la red")
            } else {
                authenticated = false
                try {
                    session(endpoint)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LanException) {
                    if (e.code == LanErrorCodes.UNKNOWN_PAIRING || e.code == LanErrorCodes.AUTH_FAILED) {
                        _status.value = LinkStatus(LinkState.ERROR, record.peerName, "La tablet no reconoce este reloj: vuelve a emparejar")
                        return
                    }
                    _status.value = LinkStatus(LinkState.ERROR, record.peerName, e.message)
                } catch (e: Exception) {
                    _status.value = LinkStatus(LinkState.ERROR, record.peerName, e.message ?: e.toString())
                } finally {
                    channel = null
                    conn?.cancel(); conn = null
                }
                // Tras una sesión que llegó a autenticarse, reintenta rápido.
                if (authenticated) backoff = 1_000L
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
        }
    }

    private suspend fun session(endpoint: LanEndpoint) {
        _status.value = LinkStatus(LinkState.CONNECTING, record.peerName, endpoint.host)
        val c = WsConnection.open(client, endpoint.url, stepTimeoutMs)
        conn = c
        _status.value = LinkStatus(LinkState.AUTHENTICATING, record.peerName, endpoint.host)
        val clientNonce = CryptoBytes.random(SessionKeys.NONCE_BYTES)
        c.send(LanMessage.Hello(ProtocolConstants.PROTOCOL_VERSION, record.pairingId, CryptoBytes.b64(clientNonce), clock()))
        val challenge = c.receive(stepTimeoutMs) as? LanMessage.Challenge ?: throw LanException("Reto inesperado")
        val serverNonce = CryptoBytes.unb64(challenge.serverNonce)?.takeIf { it.size == SessionKeys.NONCE_BYTES }
            ?: throw LanException("Nonce del servidor inválido")
        val keys = SessionKeys.derive(record.key(), record.pairingId, clientNonce, serverNonce)
        c.send(LanMessage.Auth(CryptoBytes.b64(keys.clientProof())))
        val result = c.receive(stepTimeoutMs) as? LanMessage.AuthResult ?: throw LanException("Respuesta inesperada")
        if (!result.ok) throw LanException("Autenticación rechazada", result.reason)
        if (!CryptoBytes.constantTimeEquals(CryptoBytes.unb64(result.mac), keys.serverProof())) {
            // La tablet no demostró conocer la clave: posible suplantación.
            throw LanException("La tablet no superó la autenticación", LanErrorCodes.AUTH_FAILED)
        }
        val ch = SecureChannel.forClient(keys)
        channel = ch
        authenticated = true
        _status.value = LinkStatus(LinkState.CONNECTED, record.peerName, endpoint.host, rttMs = lastRtt)
        coroutineScope {
            val pinger = launch {
                while (true) {
                    synchronized(ch) { c.send(ch.seal(SecureBody.encode(SecureBody.Ping(clock(), lastRtt)))) }
                    delay(pingIntervalMs)
                }
            }
            try {
                while (true) {
                    val msg = c.receive(pingIntervalMs * 3) as? LanMessage.Secure ?: throw LanException("Trama inesperada")
                    val body = ch.open(msg)?.let { SecureBody.decode(it) } ?: throw LanException("Trama no auténtica")
                    when (body) {
                        is SecureBody.Pong -> {
                            lastRtt = (clock() - body.echoSentAtMs).coerceAtLeast(0)
                            _status.value = _status.value.copy(rttMs = lastRtt)
                        }
                        is SecureBody.Bye -> throw LanException("La tablet cerró la sesión: ${body.reason}")
                        else -> Unit
                    }
                }
            } finally {
                pinger.cancel()
            }
        }
    }
}

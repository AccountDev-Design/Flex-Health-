package com.flexhealth.heartmonitor.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * Mensajes del canal WebSocket local (reloj ↔ tablet). Los mensajes en claro solo se usan para
 * el emparejamiento y la autenticación; tras [AuthResult] todo viaja dentro de [Secure]
 * (AES-256-GCM). Ver CONNECTION_PROTOCOL.md.
 */
@Serializable
sealed interface LanMessage {

    // ---------- Emparejamiento (comparación numérica con compromiso) ----------

    /** Reloj → tablet. */
    @Serializable @SerialName("pair_hello")
    data class PairHello(
        val protocolVersion: Int,
        val deviceId: String,
        val deviceName: String,
        val publicKey: String,
    ) : LanMessage

    /** Tablet → reloj. `commitment` = H(pubT, pubW, nonceT): fija nonceT antes de conocer nonceW. */
    @Serializable @SerialName("pair_commit")
    data class PairCommit(
        val receiverId: String,
        val receiverName: String,
        val publicKey: String,
        val commitment: String,
    ) : LanMessage

    /** Reloj → tablet. */
    @Serializable @SerialName("pair_nonce")
    data class PairNonce(val nonce: String) : LanMessage

    /** Tablet → reloj: revela nonceT; el reloj verifica el compromiso. */
    @Serializable @SerialName("pair_reveal")
    data class PairReveal(val nonce: String) : LanMessage

    /** Reloj → tablet, tras confirmar el usuario que el código coincide en el reloj. */
    @Serializable @SerialName("pair_confirm")
    data class PairConfirm(val mac: String) : LanMessage

    /** Tablet → reloj, tras la decisión del usuario en la tablet. */
    @Serializable @SerialName("pair_result")
    data class PairResult(val accepted: Boolean, val mac: String? = null, val reason: String? = null) : LanMessage

    // ---------- Autenticación de sesión ----------

    @Serializable @SerialName("hello")
    data class Hello(
        val protocolVersion: Int,
        val pairingId: String,
        val clientNonce: String,
        val clientTimeMs: Long,
    ) : LanMessage

    @Serializable @SerialName("challenge")
    data class Challenge(val serverNonce: String, val serverTimeMs: Long) : LanMessage

    @Serializable @SerialName("auth")
    data class Auth(val mac: String) : LanMessage

    @Serializable @SerialName("auth_result")
    data class AuthResult(val ok: Boolean, val mac: String? = null, val reason: String? = null) : LanMessage

    // ---------- Datos cifrados ----------

    @Serializable @SerialName("secure")
    data class Secure(val counter: Long, val payload: String) : LanMessage

    @Serializable @SerialName("error")
    data class Error(val code: String, val message: String? = null) : LanMessage

    companion object {
        fun encode(msg: LanMessage): String = ProtocolJson.encodeToString(serializer(), msg)

        fun decode(text: String): LanMessage? {
            if (text.length > ProtocolConstants.LAN_MAX_FRAME_BYTES) return null
            return try {
                ProtocolJson.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/** Contenido cifrado dentro de [LanMessage.Secure]. */
@Serializable
sealed interface SecureBody {
    @Serializable @SerialName("sample")
    data class Sample(val packet: HeartRatePacket) : SecureBody

    /** El reloj informa su último RTT medido para que la tablet lo muestre en diagnóstico. */
    @Serializable @SerialName("ping")
    data class Ping(val sentAtMs: Long, val lastRttMs: Long? = null) : SecureBody

    @Serializable @SerialName("pong")
    data class Pong(val echoSentAtMs: Long) : SecureBody

    @Serializable @SerialName("bye")
    data class Bye(val reason: String? = null) : SecureBody

    companion object {
        fun encode(body: SecureBody): ByteArray = ProtocolJson.encodeToString(serializer(), body).encodeToByteArray()

        fun decode(bytes: ByteArray): SecureBody? = try {
            ProtocolJson.decodeFromString(serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

object LanErrorCodes {
    const val UNSUPPORTED_VERSION = "unsupported_version"
    const val PAIRING_CLOSED = "pairing_closed"
    const val UNKNOWN_PAIRING = "unknown_pairing"
    const val AUTH_FAILED = "auth_failed"
    const val PROTOCOL = "protocol_error"
    const val BUSY = "busy"
    const val RATE_LIMITED = "rate_limited"
    const val REJECTED = "rejected"
    const val TIMEOUT = "timeout"
}

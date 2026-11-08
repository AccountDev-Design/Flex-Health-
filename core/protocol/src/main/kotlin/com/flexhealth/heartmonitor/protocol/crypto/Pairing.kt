package com.flexhealth.heartmonitor.protocol.crypto

import com.flexhealth.heartmonitor.protocol.LanMessage
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import com.flexhealth.heartmonitor.protocol.crypto.CryptoBytes.label

/**
 * Emparejamiento por comparación numérica (inspirado en Bluetooth Secure Simple Pairing):
 *
 * 1. W→T PairHello(pubW)
 * 2. T→W PairCommit(pubT, C = H(pubT, pubW, nT))
 * 3. W→T PairNonce(nW)
 * 4. T→W PairReveal(nT)          — W comprueba C
 * 5. Ambos muestran code = 6 dígitos de H(pubW, pubT, nW, nT, idW, idT)
 * 6. W→T PairConfirm(MAC_K("w"))  — tras confirmar el usuario en el reloj
 * 7. T→W PairResult(MAC_K("t"))   — tras aceptar el usuario en la tablet
 *
 * K = HKDF(ECDH(pubW, pubT), salt = transcript). El compromiso impide que un atacante en medio
 * elija nonces para forzar códigos iguales en las dos mitades: su probabilidad de éxito
 * por intento es 1/10⁶ y cada intento requiere la interacción del usuario.
 */
object PairingMath {
    const val NONCE_BYTES = 16
    const val CODE_DIGITS = 6

    fun commitment(responderPub: ByteArray, initiatorPub: ByteArray, responderNonce: ByteArray): ByteArray =
        CryptoBytes.sha256(label("FHM-commit-v1"), responderPub, initiatorPub, responderNonce)

    fun transcript(
        initiatorPub: ByteArray, responderPub: ByteArray,
        initiatorNonce: ByteArray, responderNonce: ByteArray,
        initiatorId: String, responderId: String,
    ): ByteArray = CryptoBytes.sha256(
        label("FHM-pair-v1"), initiatorPub, responderPub, initiatorNonce, responderNonce,
        label(initiatorId), label(responderId),
    )

    fun verificationCode(transcript: ByteArray): String {
        val h = CryptoBytes.sha256(label("FHM-code-v1"), transcript)
        val v = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or
            ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
        return (v % 1_000_000L).toString().padStart(CODE_DIGITS, '0')
    }

    fun pairingKey(sharedSecret: ByteArray, transcript: ByteArray): ByteArray =
        Hkdf.derive(sharedSecret, transcript, label("FHM pairing key v1"), 32)

    fun pairingId(transcript: ByteArray): String =
        CryptoBytes.hex(CryptoBytes.sha256(label("FHM-pairing-id"), transcript)).take(32)

    fun confirmMac(key: ByteArray, role: String, transcript: ByteArray): ByteArray =
        CryptoBytes.hmac(key, label("FHM-confirm-v1"), label(role), transcript)

    internal const val ROLE_INITIATOR = "initiator"
    internal const val ROLE_RESPONDER = "responder"
}

/** Lado del reloj. Una instancia por intento de emparejamiento. */
class PairingInitiator(
    private val deviceId: String,
    private val deviceName: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private enum class Step { NEW, HELLO_SENT, NONCE_SENT, CODE_READY, CONFIRM_SENT, DONE, FAILED }

    private val keys = EcdhKeyPair.generate()
    private val nonce = CryptoBytes.random(PairingMath.NONCE_BYTES)
    private var step = Step.NEW
    private var responderPub: ByteArray? = null
    private var responderId: String? = null
    private var responderName: String? = null
    private var commitment: ByteArray? = null
    private var transcript: ByteArray? = null
    private var key: ByteArray? = null

    /** Código de 6 dígitos que debe coincidir con el que muestra la tablet. */
    var verificationCode: String? = null
        private set

    val peerName: String? get() = responderName

    fun hello(): LanMessage.PairHello {
        check(step == Step.NEW)
        step = Step.HELLO_SENT
        return LanMessage.PairHello(ProtocolConstants.PROTOCOL_VERSION, deviceId, deviceName, CryptoBytes.b64(keys.publicEncoded))
    }

    fun onCommit(msg: LanMessage.PairCommit): LanMessage.PairNonce = guard {
        expect(Step.HELLO_SENT)
        responderPub = CryptoBytes.unb64(msg.publicKey)?.also { EcdhKeyPair.decodePublic(it) }
            ?: fail("Clave pública inválida")
        commitment = CryptoBytes.unb64(msg.commitment)?.takeIf { it.size == 32 } ?: fail("Compromiso inválido")
        responderId = msg.receiverId.take(64)
        responderName = msg.receiverName.take(64)
        step = Step.NONCE_SENT
        LanMessage.PairNonce(CryptoBytes.b64(nonce))
    }

    /** Verifica el compromiso y calcula el código de verificación. */
    fun onReveal(msg: LanMessage.PairReveal): String = guard {
        expect(Step.NONCE_SENT)
        val rNonce = CryptoBytes.unb64(msg.nonce)?.takeIf { it.size == PairingMath.NONCE_BYTES } ?: fail("Nonce inválido")
        val rPub = responderPub!!
        if (!CryptoBytes.constantTimeEquals(commitment, PairingMath.commitment(rPub, keys.publicEncoded, rNonce))) {
            fail("El compromiso no coincide: posible ataque de intermediario")
        }
        val t = PairingMath.transcript(keys.publicEncoded, rPub, nonce, rNonce, deviceId, responderId!!)
        transcript = t
        key = PairingMath.pairingKey(keys.agree(rPub), t)
        step = Step.CODE_READY
        PairingMath.verificationCode(t).also { verificationCode = it }
    }

    /** Llamar solo cuando el usuario confirma en el reloj que los códigos coinciden. */
    fun confirm(): LanMessage.PairConfirm = guard {
        expect(Step.CODE_READY)
        step = Step.CONFIRM_SENT
        LanMessage.PairConfirm(CryptoBytes.b64(PairingMath.confirmMac(key!!, PairingMath.ROLE_INITIATOR, transcript!!)))
    }

    fun onResult(msg: LanMessage.PairResult): PairingRecord = guard {
        expect(Step.CONFIRM_SENT)
        if (!msg.accepted) fail("La tablet rechazó el emparejamiento${msg.reason?.let { ": $it" } ?: ""}")
        val expected = PairingMath.confirmMac(key!!, PairingMath.ROLE_RESPONDER, transcript!!)
        if (!CryptoBytes.constantTimeEquals(CryptoBytes.unb64(msg.mac), expected)) fail("Confirmación de la tablet inválida")
        step = Step.DONE
        PairingRecord(PairingMath.pairingId(transcript!!), responderId!!, responderName!!, CryptoBytes.b64(key!!), clock())
    }

    private fun expect(s: Step) { if (step != s) fail("Mensaje de emparejamiento fuera de orden") }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: SecurityProtocolException) {
        step = Step.FAILED; throw e
    }
}

/** Lado de la tablet. Una instancia por conexión que intenta emparejarse. */
class PairingResponder(
    private val receiverId: String,
    private val receiverName: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private enum class Step { NEW, COMMIT_SENT, CODE_READY, DONE, FAILED }

    private val keys = EcdhKeyPair.generate()
    private val nonce = CryptoBytes.random(PairingMath.NONCE_BYTES)
    private var step = Step.NEW
    private var initiatorPub: ByteArray? = null
    private var transcript: ByteArray? = null
    private var key: ByteArray? = null

    var initiatorId: String? = null
        private set
    var initiatorName: String? = null
        private set
    var verificationCode: String? = null
        private set
    /** `true` cuando el reloj ya envió una confirmación válida. */
    var initiatorConfirmed: Boolean = false
        private set

    fun onHello(msg: LanMessage.PairHello): LanMessage.PairCommit = guard {
        expect(Step.NEW)
        if (msg.protocolVersion != ProtocolConstants.PROTOCOL_VERSION) fail("Versión de protocolo no soportada")
        initiatorPub = CryptoBytes.unb64(msg.publicKey)?.also { EcdhKeyPair.decodePublic(it) } ?: fail("Clave pública inválida")
        initiatorId = msg.deviceId.take(64)
        initiatorName = msg.deviceName.take(64)
        step = Step.COMMIT_SENT
        LanMessage.PairCommit(
            receiverId, receiverName, CryptoBytes.b64(keys.publicEncoded),
            CryptoBytes.b64(PairingMath.commitment(keys.publicEncoded, initiatorPub!!, nonce)),
        )
    }

    fun onNonce(msg: LanMessage.PairNonce): LanMessage.PairReveal = guard {
        expect(Step.COMMIT_SENT)
        val iNonce = CryptoBytes.unb64(msg.nonce)?.takeIf { it.size == PairingMath.NONCE_BYTES } ?: fail("Nonce inválido")
        val t = PairingMath.transcript(initiatorPub!!, keys.publicEncoded, iNonce, nonce, initiatorId!!, receiverId)
        transcript = t
        key = PairingMath.pairingKey(keys.agree(initiatorPub!!), t)
        verificationCode = PairingMath.verificationCode(t)
        step = Step.CODE_READY
        LanMessage.PairReveal(CryptoBytes.b64(nonce))
    }

    fun onConfirm(msg: LanMessage.PairConfirm) = guard {
        expect(Step.CODE_READY)
        val expected = PairingMath.confirmMac(key!!, PairingMath.ROLE_INITIATOR, transcript!!)
        if (!CryptoBytes.constantTimeEquals(CryptoBytes.unb64(msg.mac), expected)) fail("Confirmación del reloj inválida")
        initiatorConfirmed = true
    }

    /** El usuario aceptó en la tablet. Requiere que el reloj haya confirmado antes. */
    fun accept(): Pair<LanMessage.PairResult, PairingRecord> = guard {
        expect(Step.CODE_READY)
        if (!initiatorConfirmed) fail("El reloj aún no ha confirmado el código")
        step = Step.DONE
        val mac = PairingMath.confirmMac(key!!, PairingMath.ROLE_RESPONDER, transcript!!)
        LanMessage.PairResult(true, CryptoBytes.b64(mac)) to
            PairingRecord(PairingMath.pairingId(transcript!!), initiatorId!!, initiatorName!!, CryptoBytes.b64(key!!), clock())
    }

    fun reject(reason: String): LanMessage.PairResult {
        step = Step.FAILED
        return LanMessage.PairResult(false, reason = reason)
    }

    private fun expect(s: Step) { if (step != s) fail("Mensaje de emparejamiento fuera de orden") }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: SecurityProtocolException) {
        step = Step.FAILED; throw e
    }
}

internal fun fail(message: String): Nothing = throw SecurityProtocolException(message)

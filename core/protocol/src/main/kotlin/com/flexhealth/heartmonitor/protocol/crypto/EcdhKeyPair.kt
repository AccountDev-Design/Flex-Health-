package com.flexhealth.heartmonitor.protocol.crypto

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * Par de claves efímero ECDH sobre P-256 (secp256r1). Se eligió P-256 en lugar de X25519
 * porque está disponible en todas las versiones de Android soportadas (API 30+) y en la JVM.
 */
class EcdhKeyPair private constructor(private val keyPair: KeyPair) {

    /** Clave pública codificada X.509 (SubjectPublicKeyInfo). */
    val publicEncoded: ByteArray = keyPair.public.encoded

    /** Calcula el secreto compartido con la clave pública del otro extremo. */
    fun agree(peerPublicEncoded: ByteArray): ByteArray {
        if (peerPublicEncoded.contentEquals(publicEncoded)) {
            throw SecurityProtocolException("La clave pública del par es la propia (ataque de reflexión)")
        }
        val peer = decodePublic(peerPublicEncoded)
        return try {
            KeyAgreement.getInstance("ECDH").run {
                init(keyPair.private)
                doPhase(peer, true)
                generateSecret()
            }
        } catch (e: GeneralSecurityException) {
            throw SecurityProtocolException("Acuerdo ECDH fallido", e)
        } catch (e: IllegalStateException) {
            throw SecurityProtocolException("Acuerdo ECDH fallido", e)
        }
    }

    companion object {
        private const val CURVE_FIELD_BITS = 256

        fun generate(): EcdhKeyPair = EcdhKeyPair(
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            }.generateKeyPair(),
        )

        fun decodePublic(encoded: ByteArray): ECPublicKey {
            if (encoded.size !in 64..160) throw SecurityProtocolException("Longitud de clave pública inválida")
            val key = try {
                KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
            } catch (e: GeneralSecurityException) {
                throw SecurityProtocolException("Clave pública inválida", e)
            }
            if (key !is ECPublicKey || key.params.curve.field.fieldSize != CURVE_FIELD_BITS) {
                throw SecurityProtocolException("Curva no soportada")
            }
            return key
        }
    }
}

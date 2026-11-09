package com.flexhealth.heartmonitor.protocol.crypto

import com.flexhealth.heartmonitor.protocol.LanMessage
import com.flexhealth.heartmonitor.protocol.crypto.CryptoBytes.label
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Claves de una sesión, derivadas de la clave de emparejamiento y de dos nonces frescos. */
class SessionKeys private constructor(
    val clientToServer: ByteArray,
    val serverToClient: ByteArray,
    private val auth: ByteArray,
    private val transcript: ByteArray,
) {
    fun clientProof(): ByteArray = CryptoBytes.hmac(auth, label("FHM-auth-client-v1"), transcript)
    fun serverProof(): ByteArray = CryptoBytes.hmac(auth, label("FHM-auth-server-v1"), transcript)

    companion object {
        const val NONCE_BYTES = 16

        fun derive(pairingKey: ByteArray, pairingId: String, clientNonce: ByteArray, serverNonce: ByteArray): SessionKeys {
            require(clientNonce.size == NONCE_BYTES && serverNonce.size == NONCE_BYTES)
            val transcript = CryptoBytes.sha256(label("FHM-session-v1"), label(pairingId), clientNonce, serverNonce)
            val okm = Hkdf.derive(pairingKey, transcript, label("FHM session keys v1"), 96)
            return SessionKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64), okm.copyOfRange(64, 96), transcript)
        }
    }
}

/**
 * Canal autenticado y cifrado AES-256-GCM. Cada dirección usa su propia clave, por lo que el
 * nonce (contador de 64 bits) nunca se repite con la misma clave. El receptor exige contadores
 * estrictamente crecientes: rechaza repeticiones (replay) y tramas reordenadas.
 */
class SecureChannel(sendKey: ByteArray, recvKey: ByteArray) {
    private val sendKeySpec = SecretKeySpec(sendKey, "AES")
    private val recvKeySpec = SecretKeySpec(recvKey, "AES")
    private var sendCounter = 0L
    private var lastReceived = -1L
    // Instancias reutilizadas (acceso sincronizado): evita la búsqueda de proveedor JCA en cada mensaje.
    private val encryptCipher: Cipher = Cipher.getInstance(TRANSFORMATION)
    private val decryptCipher: Cipher = Cipher.getInstance(TRANSFORMATION)

    @Synchronized
    fun seal(plaintext: ByteArray): LanMessage.Secure {
        val counter = sendCounter++
        val cipher = encryptCipher
        cipher.init(Cipher.ENCRYPT_MODE, sendKeySpec, GCMParameterSpec(TAG_BITS, nonce(counter)))
        cipher.updateAAD(aad(counter))
        return LanMessage.Secure(counter, CryptoBytes.b64(cipher.doFinal(plaintext)))
    }

    /** Devuelve el texto en claro, o `null` si la trama es falsa, está alterada o repetida. */
    @Synchronized
    fun open(msg: LanMessage.Secure): ByteArray? {
        if (msg.counter <= lastReceived) return null
        val data = CryptoBytes.unb64(msg.payload) ?: return null
        if (data.size < TAG_BITS / 8) return null
        return try {
            val cipher = decryptCipher
            cipher.init(Cipher.DECRYPT_MODE, recvKeySpec, GCMParameterSpec(TAG_BITS, nonce(msg.counter)))
            cipher.updateAAD(aad(msg.counter))
            cipher.doFinal(data).also { lastReceived = msg.counter }
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private val AAD_PREFIX = label("FHM-secure-v1")

        private fun nonce(counter: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
        private fun aad(counter: Long): ByteArray = AAD_PREFIX + ByteBuffer.allocate(8).putLong(counter).array()

        fun forClient(keys: SessionKeys) = SecureChannel(keys.clientToServer, keys.serverToClient)
        fun forServer(keys: SessionKeys) = SecureChannel(keys.serverToClient, keys.clientToServer)
    }
}

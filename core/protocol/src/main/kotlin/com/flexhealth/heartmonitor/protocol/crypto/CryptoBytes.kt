package com.flexhealth.heartmonitor.protocol.crypto

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Excepción para cualquier fallo criptográfico o de protocolo de seguridad. */
class SecurityProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Utilidades criptográficas basadas solo en JCA estándar (disponible en JVM y Android). */
object CryptoBytes {
    private val random = SecureRandom()

    fun random(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun unb64(value: String?): ByteArray? = try {
        if (value == null || value.length > 4096) null else Base64.getDecoder().decode(value)
    } catch (e: IllegalArgumentException) {
        null
    }

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append(HEX[v ushr 4]); append(HEX[v and 0x0f])
        }
    }

    /** SHA-256 de partes con prefijo de longitud (evita ambigüedades de concatenación). */
    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) {
            md.update(ByteBuffer.allocate(4).putInt(p.size).array()); md.update(p)
        }
        return md.digest()
    }

    /** HMAC-SHA256 de partes con prefijo de longitud. */
    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) {
            mac.update(ByteBuffer.allocate(4).putInt(p.size).array()); mac.update(p)
        }
        return mac.doFinal()
    }

    /** HMAC-SHA256 "crudo" (sin prefijos), usado por HKDF (RFC 5869). */
    internal fun hmacRaw(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun constantTimeEquals(a: ByteArray?, b: ByteArray?): Boolean =
        a != null && b != null && MessageDigest.isEqual(a, b)

    fun label(text: String): ByteArray = text.encodeToByteArray()

    private const val HEX = "0123456789abcdef"
}

/** HKDF-SHA256 (RFC 5869). */
object Hkdf {
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val prk = CryptoBytes.hmacRaw(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = CryptoBytes.hmacRaw(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }
}

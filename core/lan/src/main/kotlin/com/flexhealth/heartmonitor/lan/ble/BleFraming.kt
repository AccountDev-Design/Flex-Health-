package com.flexhealth.heartmonitor.lan.ble

import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import com.flexhealth.heartmonitor.protocol.crypto.CryptoBytes

class BleFramingException(message: String) : Exception(message)

/**
 * Fragmentación de mensajes del protocolo sobre GATT. Cada fragmento = 1 byte de cabecera + datos:
 * bit 7 = último fragmento; bits 0–6 = identificador del mensaje (0–127, rotatorio por dirección).
 * Cada fragmento cabe en una escritura/notificación ATT (MTU − 3 bytes de cabecera ATT).
 */
object BleFraming {
    const val HEADER_BYTES = 1
    private const val FLAG_LAST = 0x80
    private const val ID_MASK = 0x7F
    private const val ATT_HEADER = 3

    fun maxPayload(mtu: Int): Int = (mtu.coerceAtLeast(ProtocolConstants.BLE_DEFAULT_MTU) - ATT_HEADER - HEADER_BYTES).coerceAtLeast(1)

    fun fragment(message: ByteArray, mtu: Int, messageId: Int): List<ByteArray> {
        require(message.isNotEmpty()) { "mensaje vacío" }
        require(message.size <= ProtocolConstants.LAN_MAX_FRAME_BYTES) { "mensaje demasiado grande" }
        val max = maxPayload(mtu)
        val id = messageId and ID_MASK
        val out = ArrayList<ByteArray>((message.size + max - 1) / max)
        var pos = 0
        while (pos < message.size) {
            val n = minOf(max, message.size - pos)
            val last = pos + n == message.size
            val chunk = ByteArray(n + HEADER_BYTES)
            chunk[0] = (id or if (last) FLAG_LAST else 0).toByte()
            System.arraycopy(message, pos, chunk, HEADER_BYTES, n)
            out += chunk
            pos += n
        }
        return out
    }

    /** Hash corto del id del receptor anunciado por BLE (no revela el id ni la clave). */
    fun receiverIdHash(receiverId: String): ByteArray =
        CryptoBytes.sha256(CryptoBytes.label("FHM-ble-rid-v1"), CryptoBytes.label(receiverId)).copyOf(ProtocolConstants.BLE_RID_HASH_BYTES)

    /**
     * Reensamblado para una dirección. Si llega un fragmento de otro mensaje antes de completar el actual
     * (fragmento perdido), se descarta el parcial y se resincroniza: el protocolo seguro tolera huecos.
     */
    class Reassembler(private val maxBytes: Int = ProtocolConstants.LAN_MAX_FRAME_BYTES) {
        private var buffer = java.io.ByteArrayOutputStream()
        private var currentId = -1
        var discarded: Int = 0; private set

        @Synchronized
        fun accept(chunk: ByteArray): ByteArray? {
            if (chunk.size <= HEADER_BYTES) throw BleFramingException("fragmento vacío")
            val header = chunk[0].toInt() and 0xFF
            val id = header and ID_MASK
            if (currentId != -1 && id != currentId) { discarded++; reset() }
            currentId = id
            if (buffer.size() + chunk.size - HEADER_BYTES > maxBytes) { discarded++; reset(); throw BleFramingException("mensaje demasiado grande") }
            buffer.write(chunk, HEADER_BYTES, chunk.size - HEADER_BYTES)
            if (header and FLAG_LAST == 0) return null
            val msg = buffer.toByteArray()
            reset()
            return msg
        }

        @Synchronized
        fun reset() { buffer = java.io.ByteArrayOutputStream(); currentId = -1 }
    }
}

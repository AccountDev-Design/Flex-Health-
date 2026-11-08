package com.flexhealth.heartmonitor.protocol.crypto

import kotlinx.serialization.Serializable

/**
 * Resultado persistente de un emparejamiento. Contiene una clave secreta de 256 bits:
 * en Android se guarda cifrada con una clave no exportable del Android Keystore
 * (ver core:android KeystorePairingStore). Nunca se escribe en el código fuente.
 */
@Serializable
data class PairingRecord(
    val pairingId: String,
    val peerId: String,
    val peerName: String,
    val keyB64: String,
    val createdAtMs: Long,
) {
    fun key(): ByteArray = CryptoBytes.unb64(keyB64) ?: throw SecurityProtocolException("Clave corrupta")

    override fun toString(): String = "PairingRecord(pairingId=$pairingId, peer=$peerName, key=***)"
}

/** Almacén de emparejamientos. Implementación en memoria para pruebas; Keystore en Android. */
interface PairingStore {
    fun find(pairingId: String): PairingRecord?
    fun all(): List<PairingRecord>
    fun save(record: PairingRecord)
    fun delete(pairingId: String)
}

class InMemoryPairingStore : PairingStore {
    private val map = LinkedHashMap<String, PairingRecord>()
    @Synchronized override fun find(pairingId: String) = map[pairingId]
    @Synchronized override fun all() = map.values.toList()
    @Synchronized override fun save(record: PairingRecord) { map[record.pairingId] = record }
    @Synchronized override fun delete(pairingId: String) { map.remove(pairingId) }
}

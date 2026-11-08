package com.flexhealth.heartmonitor.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Configuración JSON única para todo el protocolo (tolerante a campos futuros). */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}

object PacketCodec {
    fun encode(packet: HeartRatePacket): ByteArray =
        ProtocolJson.encodeToString(HeartRatePacket.serializer(), packet).encodeToByteArray()

    /** Devuelve `null` si el contenido no es JSON válido o no tiene los campos obligatorios. */
    fun decode(bytes: ByteArray): HeartRatePacket? {
        if (bytes.isEmpty() || bytes.size > ProtocolConstants.LAN_MAX_FRAME_BYTES) return null
        return try {
            ProtocolJson.decodeFromString(HeartRatePacket.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

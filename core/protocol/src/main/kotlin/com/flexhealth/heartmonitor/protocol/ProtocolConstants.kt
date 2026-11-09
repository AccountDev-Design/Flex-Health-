package com.flexhealth.heartmonitor.protocol

/**
 * Constantes compartidas por el reloj, el teléfono y la tablet.
 *
 * Política de versionado:
 * - Cambios aditivos (campos nuevos opcionales, nuevos valores de enum) NO incrementan
 *   [PROTOCOL_VERSION]: los receptores ignoran claves desconocidas y convierten enums
 *   desconocidos en `UNKNOWN`.
 * - Cambios incompatibles incrementan [PROTOCOL_VERSION]; los receptores rechazan versiones
 *   fuera de [MIN_SUPPORTED_VERSION]..[PROTOCOL_VERSION] con un error explícito.
 */
object ProtocolConstants {
    const val PROTOCOL_VERSION: Int = 1
    const val MIN_SUPPORTED_VERSION: Int = 1

    const val METRIC_HEART_RATE: String = "heart_rate"

    /** Rango fisiológicamente plausible para un sensor óptico de muñeca. Fuera de él se descarta. */
    const val BPM_MIN: Int = 25
    const val BPM_MAX: Int = 250

    const val MAX_SESSION_ID_LENGTH: Int = 64
    const val MAX_DEVICE_LENGTH: Int = 48

    /** 2020-01-01T00:00:00Z. Cualquier timestamp anterior indica un reloj sin sincronizar. */
    const val MIN_VALID_TIMESTAMP_MS: Long = 1_577_836_800_000L

    // --- Wear Data Layer (modo smartphone) ---
    const val DATA_LAYER_PATH_HEART_RATE: String = "/flexheart/v1/hr"
    /** Capability declarada por la app receptora en res/values/wear.xml. */
    const val DATA_LAYER_CAPABILITY_RECEIVER: String = "flex_heart_receiver"

    // --- Red local (modo tablet) ---
    const val NSD_SERVICE_TYPE: String = "_flexheart._tcp."
    const val NSD_TXT_RECEIVER_ID: String = "rid"
    const val NSD_TXT_PROTOCOL: String = "v"
    const val LAN_DEFAULT_PORT: Int = 47_820
    const val LAN_WS_PATH: String = "/flexheart"
    /** Tamaño máximo de una trama WebSocket aceptada (las legítimas ocupan < 1 KB). */
    const val LAN_MAX_FRAME_BYTES: Int = 8 * 1024

    // --- Bluetooth LE directo reloj ↔ tablet (tablet = servidor GATT, reloj = cliente) ---
    /** Servicio Flex Heart. UUID propio de 128 bits generado para este proyecto. */
    const val BLE_SERVICE_UUID: String = "8e4f1a00-6b2d-4c73-9a51-3f0d2c7b5e19"
    /** Reloj → tablet (Write con respuesta). */
    const val BLE_RX_UUID: String = "8e4f1a01-6b2d-4c73-9a51-3f0d2c7b5e19"
    /** Tablet → reloj (Notify). */
    const val BLE_TX_UUID: String = "8e4f1a02-6b2d-4c73-9a51-3f0d2c7b5e19"
    /** Client Characteristic Configuration Descriptor (estándar Bluetooth SIG). */
    const val BLE_CCCD_UUID: String = "00002902-0000-1000-8000-00805f9b34fb"
    /** MTU solicitada por el reloj (máximo de Android). La real la negocia la pila Bluetooth. */
    const val BLE_REQUESTED_MTU: Int = 517
    const val BLE_DEFAULT_MTU: Int = 23
    /** Bytes del hash del id del receptor incluidos en el anuncio (identifica la tablet emparejada). */
    const val BLE_RID_HASH_BYTES: Int = 6
}

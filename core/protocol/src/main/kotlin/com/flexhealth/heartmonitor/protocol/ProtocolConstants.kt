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
}

package com.flexhealth.heartmonitor.protocol

/** Resultado de validar un paquete recibido. */
sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val reason: Reason) : ValidationResult

    enum class Reason {
        UNSUPPORTED_VERSION,
        BAD_SESSION_ID,
        BAD_SEQUENCE,
        BAD_DEVICE,
        UNKNOWN_METRIC,
        BPM_OUT_OF_RANGE,
        BAD_TIMESTAMP,
    }
}

/**
 * Validación sintáctica y semántica de un [HeartRatePacket]. No depende del reloj del receptor
 * (el desfase entre dispositivos se trata en ClockSync); solo rechaza valores imposibles.
 */
object PacketValidator {
    private val SESSION_ID_REGEX = Regex("^[A-Za-z0-9_-]{8,${ProtocolConstants.MAX_SESSION_ID_LENGTH}}$")

    fun validate(p: HeartRatePacket): ValidationResult {
        val r = when {
            p.protocolVersion < ProtocolConstants.MIN_SUPPORTED_VERSION ||
                p.protocolVersion > ProtocolConstants.PROTOCOL_VERSION -> ValidationResult.Reason.UNSUPPORTED_VERSION
            !SESSION_ID_REGEX.matches(p.sessionId) -> ValidationResult.Reason.BAD_SESSION_ID
            p.sequence < 0 -> ValidationResult.Reason.BAD_SEQUENCE
            p.device.isBlank() || p.device.length > ProtocolConstants.MAX_DEVICE_LENGTH -> ValidationResult.Reason.BAD_DEVICE
            p.metric != ProtocolConstants.METRIC_HEART_RATE -> ValidationResult.Reason.UNKNOWN_METRIC
            p.bpm != null && p.bpm !in ProtocolConstants.BPM_MIN..ProtocolConstants.BPM_MAX -> ValidationResult.Reason.BPM_OUT_OF_RANGE
            p.timestampMs < ProtocolConstants.MIN_VALID_TIMESTAMP_MS -> ValidationResult.Reason.BAD_TIMESTAMP
            else -> null
        }
        return if (r == null) ValidationResult.Valid else ValidationResult.Invalid(r)
    }
}

package com.flexhealth.heartmonitor.protocol

import kotlinx.serialization.Serializable

/**
 * Unidad de datos que el reloj envía a cualquier receptor (Data Layer o red local).
 *
 * - [bpm] es `null` en paquetes de estado: el reloj nunca inventa una lectura cuando el sensor
 *   no entrega datos; en su lugar informa [sensorStatus].
 * - [timestampMs] es la hora de pared del reloj (epoch ms) en la que el sensor tomó la muestra,
 *   no la hora de envío. El receptor estima el desfase entre relojes (ver ClockSync en engine).
 * - [sequence] crece de forma monótona dentro de una [sessionId], para detectar duplicados,
 *   pérdidas y desorden.
 */
@Serializable
data class HeartRatePacket(
    val protocolVersion: Int = ProtocolConstants.PROTOCOL_VERSION,
    val sessionId: String,
    val sequence: Long,
    val device: String,
    val metric: String = ProtocolConstants.METRIC_HEART_RATE,
    val bpm: Int? = null,
    val timestampMs: Long,
    val sensorStatus: SensorStatus = SensorStatus.UNKNOWN,
    val quality: ReadingQuality = ReadingQuality.UNKNOWN,
    /** Fuente de datos en el reloj: "exercise", "measure"… (diagnóstico). */
    val source: String? = null,
) {
    val isSample: Boolean get() = bpm != null
}

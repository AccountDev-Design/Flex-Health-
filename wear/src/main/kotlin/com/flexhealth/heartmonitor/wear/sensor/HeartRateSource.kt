package com.flexhealth.heartmonitor.wear.sensor

import com.flexhealth.heartmonitor.protocol.ReadingQuality
import com.flexhealth.heartmonitor.protocol.SensorStatus
import kotlinx.coroutines.flow.Flow

/** Evento emitido por una fuente de frecuencia cardíaca. */
sealed interface SensorEvent {
    /** Medición real del sensor. [timestampMs] = momento de la medición (epoch ms del reloj). */
    data class Reading(val bpm: Int, val timestampMs: Long, val quality: ReadingQuality) : SensorEvent

    /** Cambio de estado del sensor. No contiene ningún valor de BPM. */
    data class Status(val status: SensorStatus, val detail: String? = null) : SensorEvent
}

/**
 * Abstracción de proveedor de frecuencia cardíaca. Permite cambiar entre Health Services
 * (ExerciseClient / MeasureClient) o, en el futuro, Samsung Health Sensor SDK, sin tocar el
 * servicio, los transportes ni la UI.
 *
 * Contrato:
 * - [events] es un flujo frío: al recolectarlo se activa el sensor y al cancelarlo se libera.
 * - Nunca emite lecturas sintéticas ni repite la última lectura: si el sensor no entrega datos,
 *   solo emite [SensorEvent.Status].
 */
interface HeartRateSource {
    val id: String

    /** Comprueba si el dispositivo soporta esta fuente (capacidades reales del hardware). */
    suspend fun isSupported(): Boolean

    fun events(): Flow<SensorEvent>
}

object HeartRateSourceIds {
    /** Health Services ExerciseClient: continuo, también con la pantalla apagada (con batching). */
    const val EXERCISE = "exercise"
    /** Health Services MeasureClient: actualizaciones rápidas solo mientras la app está visible. */
    const val MEASURE = "measure"
}

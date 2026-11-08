package com.flexhealth.heartmonitor.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Estado del sensor en el reloj. Valores desconocidos (versiones futuras) se leen como [UNKNOWN]. */
@Serializable
enum class SensorStatus {
    /** El sensor entrega lecturas. */
    @SerialName("active") ACTIVE,
    /** El sensor está buscando señal (recién iniciado o mal colocado). */
    @SerialName("acquiring") ACQUIRING,
    /** El reloj indica que no está en la muñeca. */
    @SerialName("off_body") OFF_BODY,
    /** El sensor no está disponible. */
    @SerialName("unavailable") UNAVAILABLE,
    /** Falta el permiso de frecuencia cardíaca. */
    @SerialName("permission_denied") PERMISSION_DENIED,
    /** Otra app (p. ej. un entrenamiento de Samsung Health) tiene el ejercicio activo. */
    @SerialName("busy") BUSY,
    /** Monitorización detenida. */
    @SerialName("stopped") STOPPED,
    @SerialName("error") ERROR,
    @SerialName("unknown") UNKNOWN,
}

/** Calidad informada por Health Services (HeartRateAccuracy.SensorStatus). */
@Serializable
enum class ReadingQuality {
    @SerialName("high") HIGH,
    @SerialName("medium") MEDIUM,
    @SerialName("low") LOW,
    @SerialName("unreliable") UNRELIABLE,
    @SerialName("unknown") UNKNOWN,
}

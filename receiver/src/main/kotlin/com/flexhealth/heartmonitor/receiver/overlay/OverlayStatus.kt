package com.flexhealth.heartmonitor.receiver.overlay

import com.flexhealth.heartmonitor.engine.Freshness
import com.flexhealth.heartmonitor.engine.MonitorSnapshot
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.SensorStatus

/** Severidad de un aviso del overlay. */
enum class Severity { OK, WARN, BAD, IDLE }

data class StatusLine(val text: String, val severity: Severity)

/**
 * Textos y colores del overlay, separados del dibujo para poder probarlos. El indicador (punto) refleja
 * SOLO el enlace; la calidad y actualidad del dato se indican con texto, para no confundir un hueco de
 * datos con una desconexión ni ocultar ninguna de las dos cosas.
 */
object OverlayStatus {
    fun linkSeverity(state: LinkState): Severity = when (state) {
        LinkState.CONNECTED -> Severity.OK
        LinkState.SEARCHING, LinkState.CONNECTING, LinkState.AUTHENTICATING, LinkState.PAIRING -> Severity.WARN
        LinkState.ERROR, LinkState.UNAVAILABLE -> Severity.BAD
        LinkState.IDLE -> Severity.IDLE
    }

    /** Aviso de datos por prioridad, o `null` si la lectura mostrada es actual y fiable. */
    fun dataLine(s: MonitorSnapshot): StatusLine? {
        val issue = s.signalIssue
        return when {
        s.freshness == Freshness.LOST -> StatusLine("Sin señal", Severity.BAD)
        issue != null -> StatusLine(shortLabel(issue), Severity.BAD)
        s.freshness == Freshness.WAITING -> StatusLine("Esperando datos", Severity.WARN)
        s.verifying -> StatusLine("Verificando", Severity.WARN)
        s.freshness == Freshness.STALE -> StatusLine("Desactualizado", Severity.WARN)
        !s.reliable -> StatusLine("No fiable", Severity.WARN)
        else -> null
        }
    }

    fun shortLabel(status: SensorStatus): String = when (status) {
        SensorStatus.OFF_BODY -> "Fuera de la muñeca"
        SensorStatus.ACQUIRING -> "Buscando pulso"
        SensorStatus.UNAVAILABLE -> "Sensor no disponible"
        SensorStatus.PERMISSION_DENIED -> "Sin permiso"
        SensorStatus.BUSY -> "Sensor ocupado"
        SensorStatus.STOPPED -> "Detenido"
        SensorStatus.ERROR -> "Error del sensor"
        SensorStatus.ACTIVE, SensorStatus.UNKNOWN -> ""
    }
}

package com.flexhealth.heartmonitor.lan

import com.flexhealth.heartmonitor.protocol.LanMessage

/**
 * Conexión del lado cliente (reloj) de un transporte concreto: WebSocket ([WsConnection]) o GATT
 * (Bluetooth LE). El protocolo seguro ([LanLink], [pairOverPipe]) es el mismo para ambos.
 */
interface ClientPipe {
    /** Descripción para la UI (IP o nombre/dirección Bluetooth). */
    val label: String

    /** Envía un mensaje. Devuelve `false` si la conexión ya no admite envíos. */
    fun send(msg: LanMessage): Boolean

    /** Siguiente mensaje; lanza [LanException] (con el código del par si envió un Error) o por tiempo agotado. */
    suspend fun receive(timeoutMs: Long): LanMessage

    /** Cierre ordenado. */
    fun close(reason: String = "bye")

    /** Cierre inmediato. */
    fun cancel()
}

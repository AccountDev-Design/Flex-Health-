package com.flexhealth.heartmonitor.receiver.transport

import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LinkStatus
import kotlinx.coroutines.flow.StateFlow

/** Transporte de entrada del receptor (Data Layer o red local). */
interface ReceiverTransport {
    val status: StateFlow<LinkStatus>

    /** Empieza a recibir. [sink] puede llamarse desde cualquier hilo. */
    fun start(sink: (HeartRatePacket) -> Unit)

    fun stop()
}

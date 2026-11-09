package com.flexhealth.heartmonitor.wear.transport

import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LinkStatus
import kotlinx.coroutines.flow.StateFlow

/** Envío reloj → tablet, por Wi-Fi ([LanSender]) o Bluetooth LE ([BleSender]). Seleccionable en Ajustes. */
interface TabletSender {
    val status: StateFlow<LinkStatus>
    fun start()
    fun send(packet: HeartRatePacket): Boolean
    suspend fun stop()
    /** Cierre inmediato sin suspender (onDestroy). */
    fun close()
}

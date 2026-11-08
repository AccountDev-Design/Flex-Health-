package com.flexhealth.heartmonitor.wear.service

import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.ReadingQuality
import com.flexhealth.heartmonitor.protocol.SensorStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class WatchMonitorState(
    val running: Boolean = false,
    val sourceId: String? = null,
    val sessionId: String? = null,
    val sensorStatus: SensorStatus = SensorStatus.STOPPED,
    val sensorDetail: String? = null,
    val lastBpm: Int? = null,
    val lastQuality: ReadingQuality = ReadingQuality.UNKNOWN,
    /** SystemClock.elapsedRealtime() de la última lectura real. */
    val lastReadingElapsed: Long? = null,
    val readings: Long = 0,
    val packetsSent: Long = 0,
    val phone: LinkStatus? = null,
    val tablet: LinkStatus? = null,
    val lowLatency: Boolean = false,
)

/** Estado observable del servicio (proceso único: un singleton es suficiente y evita bindings). */
object MonitoringRepository {
    private val _state = MutableStateFlow(WatchMonitorState())
    val state: StateFlow<WatchMonitorState> = _state.asStateFlow()

    internal fun update(f: (WatchMonitorState) -> WatchMonitorState) = _state.update(f)
    internal fun reset() { _state.value = WatchMonitorState() }
}

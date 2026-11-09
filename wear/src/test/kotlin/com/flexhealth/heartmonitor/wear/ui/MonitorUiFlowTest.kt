package com.flexhealth.heartmonitor.wear.ui

import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.wear.service.MonitoringRepository
import com.flexhealth.heartmonitor.wear.ui.screens.monitorUiFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** P3: los cambios que la pantalla no muestra (RTT, detalle, contadores) no deben recomponerla. */
class MonitorUiFlowTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun invisibleChangesDoNotEmit() = runTest(UnconfinedTestDispatcher()) {
        MonitoringRepository.reset()
        val emissions = mutableListOf<Any>()
        val job = launch { monitorUiFlow().toList(emissions) }
        MonitoringRepository.update { it.copy(running = true, phone = LinkStatus(LinkState.CONNECTED, "A55")) } // visible
        repeat(50) { i ->
            MonitoringRepository.update { it.copy(phone = it.phone!!.copy(rttMs = i.toLong(), detail = "d$i")) } // invisible
            MonitoringRepository.counters.packetsSent.incrementAndGet() // invisible
        }
        MonitoringRepository.update { it.copy(lastBpm = 80, lastReadingElapsed = 1) } // visible
        job.cancel()
        assertEquals("inicial + 2 cambios visibles", 3, emissions.size)
        MonitoringRepository.reset()
    }
}

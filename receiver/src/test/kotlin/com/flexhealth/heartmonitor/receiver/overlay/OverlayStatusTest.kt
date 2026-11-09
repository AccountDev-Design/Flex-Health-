package com.flexhealth.heartmonitor.receiver.overlay

import com.flexhealth.heartmonitor.engine.Freshness
import com.flexhealth.heartmonitor.engine.MonitorSnapshot
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.SensorStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayStatusTest {
    private val live = MonitorSnapshot(bpm = 80, freshness = Freshness.LIVE)

    @Test fun indicatorReflectsOnlyTheLink() {
        assertEquals(Severity.OK, OverlayStatus.linkSeverity(LinkState.CONNECTED))
        assertEquals(Severity.WARN, OverlayStatus.linkSeverity(LinkState.SEARCHING))
        assertEquals(Severity.BAD, OverlayStatus.linkSeverity(LinkState.ERROR))
        assertEquals(Severity.IDLE, OverlayStatus.linkSeverity(LinkState.IDLE))
    }

    @Test fun dataProblemsAreAlwaysAnnouncedWithPriority() {
        assertNull(OverlayStatus.dataLine(live))
        assertEquals("No fiable", OverlayStatus.dataLine(live.copy(reliable = false))!!.text)
        assertEquals("Desactualizado", OverlayStatus.dataLine(live.copy(freshness = Freshness.STALE, reliable = false))!!.text)
        assertEquals("Verificando", OverlayStatus.dataLine(live.copy(verifying = true))!!.text)
        assertEquals("Fuera de la muñeca", OverlayStatus.dataLine(live.copy(signalIssue = SensorStatus.OFF_BODY))!!.text)
        assertEquals("Sin señal", OverlayStatus.dataLine(live.copy(freshness = Freshness.LOST, signalIssue = SensorStatus.OFF_BODY))!!.text)
        assertEquals(Severity.BAD, OverlayStatus.dataLine(live.copy(freshness = Freshness.LOST))!!.severity)
        assertEquals("Esperando datos", OverlayStatus.dataLine(MonitorSnapshot())!!.text)
    }
}

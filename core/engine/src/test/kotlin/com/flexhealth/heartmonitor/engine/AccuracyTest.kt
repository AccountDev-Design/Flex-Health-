package com.flexhealth.heartmonitor.engine

import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.ReadingQuality
import com.flexhealth.heartmonitor.protocol.SensorStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Precisión: nunca mostrar como actual un dato antiguo, no fiable o sospechoso. */
class AccuracyTest {
    private val c = FakeClock()
    private val e = HeartRateEngine(c)
    private var seq = 0L

    private fun sample(bpm: Int, quality: ReadingQuality = ReadingQuality.HIGH, ageMs: Long = 0) = e.onPacket(
        HeartRatePacket(sessionId = "session-accuracy", sequence = seq++, device = "SM-R950", bpm = bpm,
            timestampMs = c.wall - ageMs, sensorStatus = SensorStatus.ACTIVE, quality = quality),
    )
    private fun status(s: SensorStatus) = e.onPacket(
        HeartRatePacket(sessionId = "session-accuracy", sequence = seq++, device = "SM-R950", bpm = null, timestampMs = c.wall, sensorStatus = s),
    )
    private fun tick(ms: Long) { c.advance(ms); e.tick() }

    @Test fun offBodyHidesLastBpmImmediately() {
        sample(80); tick(1000)
        status(SensorStatus.OFF_BODY)
        assertNull("no debe mostrarse un BPM antiguo con el reloj fuera de la muñeca", e.snapshot.value.displayBpm)
        assertEquals(SensorStatus.OFF_BODY, e.snapshot.value.signalIssue)
        sample(82)
        assertEquals(82, e.snapshot.value.displayBpm)
        assertNull(e.snapshot.value.signalIssue)
    }

    @Test fun acquiringAfterSamplesAlsoHidesValue() {
        sample(75); tick(500)
        status(SensorStatus.ACQUIRING)
        assertNull(e.snapshot.value.displayBpm)
        // Los latidos de estado "active" del reloj no ocultan el valor.
        sample(76); tick(500); status(SensorStatus.ACTIVE)
        assertEquals(76, e.snapshot.value.displayBpm)
    }

    @Test fun unreliableReadingsAreFlaggedAndExcludedFromStatsAndGraph() {
        sample(70); tick(1000); sample(72); tick(1000)
        sample(140, ReadingQuality.UNRELIABLE)
        val s = e.snapshot.value
        assertEquals("la lectura real se muestra…", 140, s.displayBpm)
        assertFalse("…pero marcada como no fiable", s.reliable)
        assertEquals(72, s.windowMax)
        assertEquals(1L, s.stats.unreliable)
        val h = HistoryWindow(16); e.copyHistory(60_000, h)
        assertTrue(h.unreliable[h.count - 1])
        val l = SparklineLayout(16)
        val n = l.compute(h, 60_000, 600f, 100f)
        assertEquals("el punto no fiable no entra en la línea", 2, (0 until n).count { !l.points[it * 2].isNaN() })
        tick(1000); sample(73)
        assertTrue(e.snapshot.value.reliable)
    }

    @Test fun isolatedSpikeIsDiscardedNotDisplayed() {
        sample(70); tick(1000); sample(71); tick(1000)
        assertEquals(IngestResult.SUSPICIOUS, sample(190))
        assertEquals("el pico no se muestra", 71, e.snapshot.value.bpm)
        assertTrue(e.snapshot.value.verifying)
        tick(1000); sample(72)
        assertEquals(72, e.snapshot.value.displayBpm)
        assertFalse(e.snapshot.value.verifying)
        assertEquals(1L, e.snapshot.value.stats.suspicious)
        val h = HistoryWindow(16); e.copyHistory(60_000, h)
        assertFalse("el pico descartado no está en el historial", h.bpm.take(h.count).contains(190))
    }

    @Test fun sustainedRealChangeIsConfirmedAndKept() {
        sample(70); tick(1000)
        assertEquals(IngestResult.SUSPICIOUS, sample(112))
        tick(1000); sample(115)
        assertEquals(115, e.snapshot.value.displayBpm)
        val h = HistoryWindow(16); e.copyHistory(60_000, h)
        assertEquals(listOf(70, 112, 115), h.bpm.take(h.count))
        assertEquals(1L, e.snapshot.value.stats.confirmedJumps)
        assertEquals(0L, e.snapshot.value.stats.suspicious)
    }

    @Test fun gradualChangesAndLongGapsAreNotFlagged() {
        sample(70)
        for (b in listOf(78, 86, 94, 102)) { tick(1000); assertEquals(IngestResult.SAMPLE, sample(b)) }
        tick(15_000)
        assertEquals("tras un hueco largo no hay referencia válida", IngestResult.SAMPLE, sample(150))
    }

    @Test fun staleDataIsNeverPresentedAsCurrent() {
        sample(90); tick(3_000)
        assertEquals(Freshness.STALE, e.snapshot.value.freshness)
        tick(2_000)
        assertNull(e.snapshot.value.displayBpm)
    }
}

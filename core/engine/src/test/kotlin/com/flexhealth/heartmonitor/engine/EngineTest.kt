package com.flexhealth.heartmonitor.engine

import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.SensorStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeClock(var wall: Long = 1_794_150_000_000, var elapsed: Long = 10_000) : EngineClock {
    override fun wallMs() = wall
    override fun elapsedMs() = elapsed
    fun advance(ms: Long) { wall += ms; elapsed += ms }
}

class EngineTest {
    private val watchSkewMs = 3_600_000L // el reloj del Watch va 1 h adelantado: no debe importar

    private fun pkt(seq: Long, bpm: Int?, measuredWall: Long, session: String = "session-0001", status: SensorStatus = SensorStatus.ACTIVE) =
        HeartRatePacket(sessionId = session, sequence = seq, device = "SM-R950", bpm = bpm, timestampMs = measuredWall + watchSkewMs, sensorStatus = status)

    @Test fun freshnessTransitionsAt3And5Seconds() {
        val c = FakeClock(); val e = HeartRateEngine(c)
        assertEquals(Freshness.WAITING, e.snapshot.value.freshness)
        e.onPacket(pkt(0, 80, c.wall))
        assertEquals(Freshness.LIVE, e.snapshot.value.freshness)
        assertEquals(80, e.snapshot.value.displayBpm)
        c.advance(2_999); e.tick(); assertEquals(Freshness.LIVE, e.snapshot.value.freshness)
        c.advance(1); assertEquals(2_000L, e.tick()); assertEquals(Freshness.STALE, e.snapshot.value.freshness)
        assertEquals(80, e.snapshot.value.displayBpm)
        c.advance(2_000); assertNull(e.tick()); assertEquals(Freshness.LOST, e.snapshot.value.freshness)
        assertNull("con señal perdida no se muestra BPM", e.snapshot.value.displayBpm)
        // Reconexión: datos nuevos, continúa normalmente.
        e.onPacket(pkt(1, 95, c.wall))
        assertEquals(Freshness.LIVE, e.snapshot.value.freshness)
        assertEquals(95, e.snapshot.value.displayBpm)
    }

    @Test fun statusPacketsNeverCreateSamples() {
        val c = FakeClock(); val e = HeartRateEngine(c)
        assertEquals(IngestResult.STATUS, e.onPacket(pkt(0, null, c.wall, status = SensorStatus.OFF_BODY)))
        assertEquals(SensorStatus.OFF_BODY, e.snapshot.value.sensorStatus)
        assertEquals(Freshness.WAITING, e.snapshot.value.freshness)
        val h = HistoryWindow(16); assertEquals(0, e.copyHistory(60_000, h))
    }

    @Test fun duplicatesAndOutOfOrder() {
        val c = FakeClock(); val e = HeartRateEngine(c)
        e.onPacket(pkt(0, 70, c.wall)); c.advance(1000)
        e.onPacket(pkt(2, 72, c.wall))
        assertEquals(IngestResult.DUPLICATE, e.onPacket(pkt(2, 72, c.wall)))
        assertEquals(1L, e.snapshot.value.stats.lost)
        // La secuencia 1 llega tarde: se acepta en el historial pero no cambia el BPM actual.
        assertEquals(IngestResult.SAMPLE, e.onPacket(pkt(1, 99, c.wall - 500)))
        assertEquals(72, e.snapshot.value.bpm)
        assertEquals(0L, e.snapshot.value.stats.lost)
        assertEquals(1L, e.snapshot.value.stats.outOfOrder)
        assertEquals(1L, e.snapshot.value.stats.duplicates)
        val h = HistoryWindow(16); e.copyHistory(60_000, h)
        assertEquals(listOf(70, 99, 72), h.bpm.take(h.count))
    }

    @Test fun invalidPacketsAreCounted() {
        val c = FakeClock(); val e = HeartRateEngine(c)
        assertEquals(IngestResult.INVALID, e.onPacket(pkt(0, 500, c.wall)))
        assertEquals(IngestResult.INVALID, e.onPacket(pkt(0, 70, c.wall).copy(sessionId = "x")))
        assertEquals(2L, e.snapshot.value.stats.invalid)
        assertEquals(Freshness.WAITING, e.snapshot.value.freshness)
    }

    @Test fun newSessionClearsHistory() {
        val c = FakeClock(); val e = HeartRateEngine(c)
        repeat(5) { e.onPacket(pkt(it.toLong(), 70 + it, c.wall)); c.advance(1000) }
        e.onPacket(pkt(0, 100, c.wall, session = "session-0002"))
        val h = HistoryWindow(16); e.copyHistory(60_000, h)
        assertEquals(1, h.count); assertEquals(100, h.bpm[0])
        assertEquals(2L, e.snapshot.value.stats.sessions)
    }

    @Test fun batchedDeliveryIsDetectedAsLate() {
        val c = FakeClock(); val e = HeartRateEngine(c)
        // Calibra con una entrega inmediata.
        e.onPacket(pkt(0, 70, c.wall))
        // Health Services con pantalla apagada: 5 muestras medidas a 1 Hz llegan juntas.
        c.advance(5_000)
        for (i in 1..5) e.onPacket(pkt(i.toLong(), 70 + i, c.wall - (5 - i) * 1000L))
        val h = HistoryWindow(16); e.copyHistory(60_000, h)
        assertEquals(6, h.count)
        assertEquals(listOf(false, true, true, false, false, false), h.late.take(6))
        assertEquals(2L, e.snapshot.value.stats.late)
        assertEquals(75, e.snapshot.value.bpm)
        assertEquals(Freshness.LIVE, e.snapshot.value.freshness)
        // Las edades corresponden al momento de medición, no al de llegada.
        assertEquals(listOf(5000L, 4000L, 3000L, 2000L, 1000L, 0L), h.ageMs.take(6).toList())
    }

    @Test fun historyIsBoundedAnd60sWindowWorks() {
        val c = FakeClock(); val e = HeartRateEngine(c, bufferCapacity = 256)
        for (i in 0 until 1000) { e.onPacket(pkt(i.toLong(), 60 + i % 40, c.wall)); c.advance(1000) }
        c.advance(-1000)
        val h = HistoryWindow(256)
        assertEquals(61, e.copyHistory(60_000, h))
        assertEquals(16, e.copyHistory(15_000, h))
        assertEquals(121, e.copyHistory(120_000, h))
        assertTrue(h.ageMs.take(h.count).all { it <= 120_000 })
        assertEquals(1000L, e.snapshot.value.stats.samples)
        e.reset()
        assertEquals(0, e.copyHistory(120_000, h))
        assertEquals(Freshness.WAITING, e.snapshot.value.freshness)
    }

    @Test fun windowStats() {
        val c = FakeClock(); val e = HeartRateEngine(c)
        listOf(60, 80, 100).forEachIndexed { i, v -> e.onPacket(pkt(i.toLong(), v, c.wall)); c.advance(1000) }
        val s = e.snapshot.value
        assertEquals(60, s.windowMin); assertEquals(100, s.windowMax); assertEquals(80, s.windowAvg)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun tickerUpdatesWithoutPolling() = runTest {
        val c = object : EngineClock {
            override fun wallMs() = 1_794_150_000_000 + testScheduler.currentTime
            override fun elapsedMs() = testScheduler.currentTime
        }
        val e = HeartRateEngine(c)
        val job = launch { e.runFreshnessTicker() }
        runCurrent()
        e.onPacket(pkt(0, 88, c.wallMs()))
        runCurrent()
        advanceTimeBy(3_001); runCurrent()
        assertEquals(Freshness.STALE, e.snapshot.value.freshness)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(Freshness.LOST, e.snapshot.value.freshness)
        job.cancel()
    }
}

class ComponentsTest {
    @Test fun ringBufferKeepsOrderAndCapacity() {
        val b = SampleRingBuffer(4)
        assertTrue(b.add(10, 1)); assertTrue(b.add(30, 3)); assertTrue(b.add(20, 2))
        assertFalse("tiempo duplicado", b.add(20, 9))
        assertTrue(b.add(40, 4)); assertTrue(b.add(50, 5))
        assertEquals(4, b.size)
        assertEquals(listOf(20L, 30L, 40L, 50L), (0 until b.size).map { b.timeAt(it) })
        assertFalse("más antigua que todo con el búfer lleno", b.add(5, 0))
        b.trimBefore(35)
        assertEquals(listOf(40L, 50L), (0 until b.size).map { b.timeAt(it) })
    }

    @Test fun sequenceTracker() {
        val s = SequenceTracker()
        assertEquals(SequenceTracker.Decision.NEW_SESSION, s.check("a", 5))
        assertEquals(SequenceTracker.Decision.IN_ORDER, s.check("a", 6))
        assertEquals(SequenceTracker.Decision.IN_ORDER, s.check("a", 10))
        assertEquals(3L, s.lost)
        assertEquals(SequenceTracker.Decision.OUT_OF_ORDER, s.check("a", 8))
        assertEquals(2L, s.lost)
        assertEquals(SequenceTracker.Decision.DUPLICATE, s.check("a", 8))
        assertEquals(SequenceTracker.Decision.DUPLICATE, s.check("a", 10))
        assertEquals(SequenceTracker.Decision.IN_ORDER, s.check("a", 200))
        assertEquals(SequenceTracker.Decision.TOO_OLD, s.check("a", 9))
        assertEquals(SequenceTracker.Decision.NEW_SESSION, s.check("b", 0))
        assertEquals(0L, s.lost)
    }

    @Test fun clockSyncEstimatesOffsetAndLateness() {
        val cs = ClockSync()
        assertEquals(0L, cs.observe(sampleWallMs = 1_000, receivedWallMs = 51_000))
        assertEquals(2_000L, cs.observe(sampleWallMs = 2_000, receivedWallMs = 54_000))
        assertEquals(50_000L, cs.offsetMs)
    }

    @Test fun sparklineBreaksAtGapsAndNeverInventsPoints() {
        val h = HistoryWindow(8)
        val ages = longArrayOf(20_000, 19_000, 18_000, 5_000, 4_000) // hueco de 13 s
        ages.forEachIndexed { i, a -> h.ageMs[i] = a; h.bpm[i] = 70 + i }
        h.count = ages.size
        val l = SparklineLayout(8)
        val n = l.compute(h, 60_000, 600f, 100f)
        assertEquals(6, n) // 5 puntos reales + 1 marcador de corte
        assertTrue(l.points[3 * 2].isNaN())
        val realX = (0 until n).map { l.points[it * 2] }.filter { !it.isNaN() }
        assertEquals(5, realX.size)
        assertEquals(400f, realX.first(), 0.01f) // 600·(1−20/60)
        assertTrue(l.yMax - l.yMin >= 20)
        h.count = 0
        assertEquals(0, l.compute(h, 60_000, 600f, 100f))
    }
}

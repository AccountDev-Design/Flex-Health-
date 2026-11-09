package com.flexhealth.heartmonitor.engine

import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.SensorStatus
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de rendimiento en la JVM (protección frente a regresiones; los límites son holgados y NO son una
 * medición en el reloj ni en la tablet). Imprimen los tiempos medidos.
 */
class PerformanceTest {
    private fun measureNs(block: () -> Unit): Long { val t = System.nanoTime(); block(); return System.nanoTime() - t }

    @Test fun ingestIsCheapAndMemoryBounded() {
        val c = FakeClock(); val e = HeartRateEngine(c)
        val n = 50_000
        repeat(2_000) { e.onPacket(HeartRatePacket(sessionId = "session-warmup", sequence = it.toLong(), device = "SM-R950", bpm = 70 + it % 10, timestampMs = c.wall, sensorStatus = SensorStatus.ACTIVE)); c.advance(1000) }
        val ns = measureNs {
            for (i in 0 until n) {
                e.onPacket(HeartRatePacket(sessionId = "session-perf", sequence = i.toLong(), device = "SM-R950", bpm = 60 + (i * 7) % 40,
                    timestampMs = c.wall, sensorStatus = SensorStatus.ACTIVE))
                c.advance(1000)
            }
        }
        val perPacketUs = ns / 1000.0 / n
        println("PERF ingest: %.2f µs/paquete".format(perPacketUs))
        assertTrue("ingesta demasiado lenta: $perPacketUs µs", perPacketUs < 200)
        val h = HistoryWindow(4096)
        assertTrue("historial acotado a 120 s", e.copyHistory(10_000_000, h) <= 121)
    }

    @Test fun graphFramePreparationFitsInAFrameBudget() {
        val c = FakeClock(); val e = HeartRateEngine(c)
        repeat(240) { e.onPacket(HeartRatePacket(sessionId = "session-frame", sequence = it.toLong(), device = "SM-R950", bpm = 60 + it % 50, timestampMs = c.wall, sensorStatus = SensorStatus.ACTIVE)); c.advance(500) }
        val h = HistoryWindow(1024); val l = SparklineLayout(1024)
        repeat(2_000) { e.copyHistory(120_000, h); l.compute(h, 120_000, 600f, 120f) }
        val frames = 10_000
        val ns = measureNs { repeat(frames) { e.copyHistory(120_000, h); l.compute(h, 120_000, 600f, 120f, timeShiftMs = (it % 450).toFloat()) } }
        val perFrameUs = ns / 1000.0 / frames
        println("PERF fotograma de gráfica (120 s): %.2f µs".format(perFrameUs))
        assertTrue("preparar un fotograma no debe acercarse a 16 ms: $perFrameUs µs", perFrameUs < 2_000)
    }
}

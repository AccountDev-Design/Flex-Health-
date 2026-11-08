package com.flexhealth.heartmonitor.wear.service

import com.flexhealth.heartmonitor.engine.EngineClock
import com.flexhealth.heartmonitor.engine.HeartRateEngine
import com.flexhealth.heartmonitor.engine.IngestResult
import com.flexhealth.heartmonitor.protocol.PacketValidator
import com.flexhealth.heartmonitor.protocol.ReadingQuality
import com.flexhealth.heartmonitor.protocol.SensorStatus
import com.flexhealth.heartmonitor.protocol.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketFactoryTest {
    private val now = 1_794_150_000_000L

    @Test fun sequencesAreMonotonicAndPacketsValid() {
        val f = PacketFactory("SM-R950", "exercise") { now }
        val packets = listOf(f.status(SensorStatus.ACQUIRING), f.sample(72, now - 500, ReadingQuality.HIGH), f.status())
        assertEquals(listOf(0L, 1L, 2L), packets.map { it.sequence })
        packets.forEach { assertEquals(ValidationResult.Valid, PacketValidator.validate(it)) }
        assertNull("un paquete de estado nunca lleva BPM", packets[2].bpm)
        assertEquals(SensorStatus.ACTIVE, packets[2].sensorStatus) // el latido repite el último estado
    }

    @Test fun eachSessionHasItsOwnId() {
        assertNotEquals(PacketFactory("SM-R950", "exercise").sessionId, PacketFactory("SM-R950", "exercise").sessionId)
    }

    @Test fun watchPacketsAreAcceptedByReceiverEngine() {
        val clock = object : EngineClock { override fun wallMs() = now; override fun elapsedMs() = 50_000L }
        val engine = HeartRateEngine(clock)
        val f = PacketFactory("SM-R950", "exercise") { now }
        assertEquals(IngestResult.STATUS, engine.onPacket(f.status(SensorStatus.ACQUIRING)))
        assertEquals(IngestResult.SAMPLE, engine.onPacket(f.sample(88, now, ReadingQuality.MEDIUM)))
        assertEquals(88, engine.snapshot.value.displayBpm)
        assertEquals("SM-R950", engine.snapshot.value.device)
    }
}

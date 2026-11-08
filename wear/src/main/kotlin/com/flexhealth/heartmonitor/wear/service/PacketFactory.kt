package com.flexhealth.heartmonitor.wear.service

import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.ReadingQuality
import com.flexhealth.heartmonitor.protocol.SensorStatus
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/** Construye paquetes de una sesión con identificador aleatorio y secuencia monótona. */
class PacketFactory(
    private val device: String,
    private val source: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val sessionId: String = "s-" + ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val sequence = AtomicLong(0)

    var lastStatus: SensorStatus = SensorStatus.ACQUIRING
        private set

    fun sample(bpm: Int, timestampMs: Long, quality: ReadingQuality): HeartRatePacket {
        lastStatus = SensorStatus.ACTIVE
        return HeartRatePacket(
            sessionId = sessionId, sequence = sequence.getAndIncrement(), device = device,
            bpm = bpm, timestampMs = timestampMs, sensorStatus = SensorStatus.ACTIVE, quality = quality, source = source,
        )
    }

    /** Paquete de estado: sin BPM. Se usa para informar de cambios y como latido de vida. */
    fun status(status: SensorStatus = lastStatus): HeartRatePacket {
        lastStatus = status
        return HeartRatePacket(
            sessionId = sessionId, sequence = sequence.getAndIncrement(), device = device,
            bpm = null, timestampMs = clock(), sensorStatus = status, source = source,
        )
    }
}

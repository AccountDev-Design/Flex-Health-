package com.flexhealth.heartmonitor.lan.ble

import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LanMessage
import com.flexhealth.heartmonitor.protocol.PacketCodec
import com.flexhealth.heartmonitor.protocol.ReadingQuality
import com.flexhealth.heartmonitor.protocol.SecureBody
import com.flexhealth.heartmonitor.protocol.SensorStatus
import com.flexhealth.heartmonitor.protocol.crypto.CryptoBytes
import com.flexhealth.heartmonitor.protocol.crypto.SecureChannel
import com.flexhealth.heartmonitor.protocol.crypto.SessionKeys
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Benchmark EN EL ORDENADOR del trabajo que el reloj hace por cada muestra (Data Layer + Wi-Fi/BLE):
 * JSON del paquete, cuerpo cifrado AES-GCM, envoltorio y fragmentación BLE. Sirve para comparar versiones
 * y detectar regresiones; NO es el tiempo real en el Exynos W930 del Galaxy Watch6 Classic.
 */
class WatchPipelineBenchmarkTest {
    @Test fun perSampleWorkOnWatchPipeline() {
        val keys = SessionKeys.derive(CryptoBytes.random(32), "pid", CryptoBytes.random(16), CryptoBytes.random(16))
        val ch = SecureChannel.forClient(keys)
        fun once(i: Int): Int {
            val p = HeartRatePacket(sessionId = "s-0123456789abcdef01234567", sequence = i.toLong(), device = "SM-R950", bpm = 60 + i % 60,
                timestampMs = 1_794_150_000_000 + i, sensorStatus = SensorStatus.ACTIVE, quality = ReadingQuality.HIGH, source = "exercise")
            val dataLayer = PacketCodec.encode(p)
            val wire = LanMessage.encode(ch.seal(SecureBody.encode(SecureBody.Sample(p)))).encodeToByteArray()
            return dataLayer.size + BleFraming.fragment(wire, 247, i).size
        }
        repeat(5_000) { once(it) }
        val n = 20_000
        val t = System.nanoTime()
        var sink = 0
        repeat(n) { sink += once(it) }
        val us = (System.nanoTime() - t) / 1000.0 / n
        val wireSize = LanMessage.encode(ch.seal(SecureBody.encode(SecureBody.Sample(HeartRatePacket(sessionId = "s-0123456789abcdef01234567",
            sequence = 1, device = "SM-R950", bpm = 80, timestampMs = 1_794_150_000_000, sensorStatus = SensorStatus.ACTIVE))))).length
        println("BENCH (PC) procesado por muestra en el reloj: %.2f µs · trama cifrada %d B · fragmentos BLE (MTU 247): %d · (MTU 23): %d"
            .format(us, wireSize, BleFraming.fragment(ByteArray(wireSize), 247, 0).size, BleFraming.fragment(ByteArray(wireSize), 23, 0).size))
        assertTrue(sink > 0)
        assertTrue("$us µs", us < 1_000)
    }
}

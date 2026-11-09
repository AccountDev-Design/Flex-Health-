package com.flexhealth.heartmonitor.protocol

import com.flexhealth.heartmonitor.protocol.crypto.CryptoBytes
import com.flexhealth.heartmonitor.protocol.crypto.SecureChannel
import com.flexhealth.heartmonitor.protocol.crypto.SessionKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coste por mensaje cifrado (JVM; límite holgado, no es una medición en el reloj). */
class CryptoPerformanceTest {
    @Test fun sealAndOpenPerMessageIsCheapWithReusedCiphers() {
        val key = CryptoBytes.random(32); val cn = CryptoBytes.random(16); val sn = CryptoBytes.random(16)
        val c = SecureChannel.forClient(SessionKeys.derive(key, "pid", cn, sn))
        val s = SecureChannel.forServer(SessionKeys.derive(key, "pid", cn, sn))
        val body = SecureBody.encode(SecureBody.Sample(HeartRatePacket(sessionId = "session-perf", sequence = 1, device = "SM-R950", bpm = 80, timestampMs = 1_794_150_000_000)))
        repeat(2_000) { s.open(c.seal(body)) }
        val n = 20_000
        val t = System.nanoTime()
        var ok = 0
        repeat(n) { if (s.open(c.seal(body)) != null) ok++ }
        val us = (System.nanoTime() - t) / 1000.0 / n
        println("PERF seal+open: %.2f µs/mensaje".format(us))
        assertEquals("todos los mensajes descifran correctamente con cifradores reutilizados", n, ok)
        assertTrue("$us µs", us < 500)
    }
}

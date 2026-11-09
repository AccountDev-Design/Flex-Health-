package com.flexhealth.heartmonitor.lan.ble

import com.flexhealth.heartmonitor.lan.LanLink
import com.flexhealth.heartmonitor.lan.LanServerListener
import com.flexhealth.heartmonitor.lan.PairingRequest
import com.flexhealth.heartmonitor.lan.PeerInfo
import com.flexhealth.heartmonitor.lan.SessionConfig
import com.flexhealth.heartmonitor.lan.SessionHost
import com.flexhealth.heartmonitor.lan.pairOverPipe
import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LanMessage
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.SensorStatus
import com.flexhealth.heartmonitor.protocol.crypto.CryptoBytes
import com.flexhealth.heartmonitor.protocol.crypto.InMemoryPairingStore
import com.flexhealth.heartmonitor.protocol.crypto.PairingRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Protocolo seguro completo sobre un enlace GATT simulado (fragmentación real, mismas clases de sesión). */
class BleTransportTest {
    private val store = InMemoryPairingStore()
    private val received = Collections.synchronizedList(mutableListOf<HeartRatePacket>())
    private val auths = AtomicInteger(); private val disconnects = AtomicInteger()
    private var codeOnTablet: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var host: SessionHost
    private val listener = object : LanServerListener {
        override fun onPairingRequest(request: PairingRequest) { codeOnTablet = request.code; Thread { Thread.sleep(30); host.acceptPairing(request.id) }.start() }
        override fun onClientAuthenticated(peer: PeerInfo) { auths.incrementAndGet() }
        override fun onPacket(peer: PeerInfo, packet: HeartRatePacket) { received += packet }
        override fun onClientDisconnected(peer: PeerInfo?, reason: String) { disconnects.incrementAndGet() }
    }
    private fun newHost(idleMs: Long = 10_000) = SessionHost(SessionConfig("tablet-id", "Galaxy Tab S10 FE", idleTimeoutMs = idleMs), store, listener).also { host = it; it.start() }
    private val sim by lazy { BleSimulator(host, scope) }

    @After fun tearDown() { scope.cancel(); sim.close(); host.close() }

    private fun pkt(seq: Long) = HeartRatePacket(sessionId = "session-ble-test", sequence = seq, device = "SM-R950", bpm = 60 + (seq % 30).toInt(),
        timestampMs = System.currentTimeMillis(), sensorStatus = SensorStatus.ACTIVE)

    private suspend fun pair(): PairingRecord {
        host.pairingOpen = true
        var watchCode = ""
        val rec = pairOverPipe(sim.connect(), "watch-id", "Galaxy Watch6 Classic", { c, _ -> watchCode = c }, { true })
        assertEquals("mismo código en reloj y tablet", codeOnTablet, watchCode)
        host.pairingOpen = false
        return rec
    }

    private fun link(rec: PairingRecord, ping: Long = 300, liveness: Long = 3_000) =
        LanLink(scope, rec, connect = { sim.connect() }, pingIntervalMs = ping, livenessTimeoutMs = liveness, maxBackoffMs = 500,
            notFoundMessage = "Tablet no encontrada por Bluetooth")

    @Test fun pairsAndStreamsOverMinimumMtuWithHeavyFragmentation() = runBlocking {
        newHost(); sim.mtu = 23 // peor caso: 19 bytes por fragmento
        val rec = pair()
        assertNotNull(store.find(rec.pairingId))
        val l = link(rec); l.start()
        withTimeout(10_000) { l.status.first { it.state == LinkState.CONNECTED } }
        for (i in 0 until 30) assertTrue(l.send(pkt(i.toLong())))
        withTimeout(10_000) { while (received.size < 30) delay(20) }
        assertEquals((0L until 30L).toList(), received.map { it.sequence })
        assertTrue("con MTU 23 cada mensaje ocupa varios fragmentos", sim.written().size > 60)
        l.stop()
    }

    @Test fun negotiatedLargeMtuUsesFewerWrites() = runBlocking {
        newHost(); sim.mtu = 247
        val rec = pair()
        val l = link(rec); l.start()
        withTimeout(10_000) { l.status.first { it.state == LinkState.CONNECTED } }
        val before = sim.written().size
        repeat(10) { assertTrue(l.send(pkt(it.toLong()))) }
        withTimeout(5_000) { while (received.size < 10) delay(20) }
        assertTrue("una muestra cifrada cabe en ≤ 2 escrituras con MTU 247", sim.written().size - before <= 20 + 4)
        l.stop()
    }

    @Test fun wrongKeyIsRejectedAndNoDataIsAccepted() = runBlocking {
        newHost()
        val rec = pair()
        val forged = rec.copy(keyB64 = CryptoBytes.b64(CryptoBytes.random(32)))
        val l = link(forged); l.start()
        withTimeout(10_000) { l.status.first { it.state == LinkState.ERROR && it.detail?.contains("emparejar") == true } }
        assertEquals(0, auths.get())
        assertTrue(received.isEmpty())
        l.stop()
    }

    @Test fun replayedAndInjectedFramesNeverReachTheReceiver() = runBlocking {
        newHost(); sim.mtu = 185
        val rec = pair()
        val l = link(rec, ping = 60_000); l.start()
        withTimeout(10_000) { l.status.first { it.state == LinkState.CONNECTED } }
        assertTrue(l.send(pkt(1)))
        withTimeout(5_000) { while (received.isEmpty()) delay(10) }
        val captured = sim.written().takeLast(BleFraming.fragment(ByteArray(600), 185, 0).size.coerceAtLeast(1))
        sim.replay(captured) // repetición de la muestra cifrada
        delay(500)
        assertEquals("la repetición no produce una lectura duplicada", 1, received.size)
        sim.inject(byteArrayOf(0x85.toByte()) + "{\"type\":\"secure\",\"counter\":999,\"payload\":\"AAAA\"}".encodeToByteArray())
        delay(500)
        assertEquals(1, received.size)
        l.stop()
    }

    @Test fun unauthenticatedBleClientCannotSendSamples() = runBlocking {
        newHost()
        val pipe = sim.connect()
        pipe.send(LanMessage.Secure(0, CryptoBytes.b64(ByteArray(64))))
        delay(500)
        assertFalse("el servidor cierra la conexión", sim.isUp())
        assertTrue(received.isEmpty())
    }

    @Test fun survivesLostNotificationsAndRecoversFromLinkLoss() = runBlocking {
        newHost(); sim.mtu = 185
        val rec = pair()
        val l = link(rec, ping = 200, liveness = 2_500); l.start()
        withTimeout(10_000) { l.status.first { it.state == LinkState.CONNECTED } }
        sim.notificationLossRate = 0.3 // se pierden pongs (tablet → reloj)
        repeat(20) { l.send(pkt(it.toLong())); delay(100) }
        assertEquals("las pérdidas de notificaciones no tiran el enlace", 1, l.sessionsEstablished)
        sim.notificationLossRate = 0.0
        sim.dropCurrent("fuera de alcance")
        withTimeout(10_000) { while (l.sessionsEstablished < 2 || l.status.value.state != LinkState.CONNECTED) delay(50) }
        val before = received.size
        assertTrue(l.send(pkt(100)))
        withTimeout(5_000) { while (received.size == before) delay(20) }
        assertTrue(disconnects.get() >= 1)
        l.stop()
    }

    @Test fun reconnectionReplacesHalfOpenSessionWithoutDuplicates() = runBlocking {
        newHost()
        val rec = pair()
        // Primera sesión autenticada.
        val first = sim.connect()
        val a = LanLink(scope, rec, connect = { first }, pingIntervalMs = 60_000, livenessTimeoutMs = 60_000)
        a.start()
        withTimeout(10_000) { a.status.first { it.state == LinkState.CONNECTED } }
        sim.dropClientOnly() // la tablet conserva una sesión medio abierta
        a.close()
        delay(300)
        assertEquals("sesión antigua aún activa en la tablet", 0, disconnects.get())
        // El reloj vuelve a conectar: la sesión nueva sustituye a la antigua.
        val b = link(rec, ping = 60_000); b.start()
        withTimeout(10_000) { b.status.first { it.state == LinkState.CONNECTED } }
        withTimeout(5_000) { while (disconnects.get() < 1) delay(20) }
        assertEquals(2, auths.get())
        assertEquals("una única sesión activa", 1, disconnects.get())
        repeat(5) { assertTrue(b.send(pkt(it.toLong()))) }
        withTimeout(5_000) { while (received.size < 5) delay(20) }
        delay(300)
        assertEquals("sin lecturas duplicadas", (0L until 5L).toList(), received.map { it.sequence })
        b.stop()
    }

    @Test fun idleBleSessionIsClosedByTheTablet() = runBlocking {
        newHost(idleMs = 600)
        val rec = pair()
        val l = link(rec, ping = 60_000, liveness = 60_000); l.start()
        withTimeout(10_000) { l.status.first { it.state == LinkState.CONNECTED } }
        withTimeout(5_000) { while (disconnects.get() == 0) delay(20) }
        l.stop()
    }
}

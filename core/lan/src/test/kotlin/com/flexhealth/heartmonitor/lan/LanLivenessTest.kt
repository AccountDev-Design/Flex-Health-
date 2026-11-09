package com.flexhealth.heartmonitor.lan

import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LanMessage
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import com.flexhealth.heartmonitor.protocol.SecureBody
import com.flexhealth.heartmonitor.protocol.SensorStatus
import com.flexhealth.heartmonitor.protocol.crypto.CryptoBytes
import com.flexhealth.heartmonitor.protocol.crypto.InMemoryPairingStore
import com.flexhealth.heartmonitor.protocol.crypto.PairingRecord
import com.flexhealth.heartmonitor.protocol.crypto.SecureChannel
import com.flexhealth.heartmonitor.protocol.crypto.SessionKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.java_websocket.client.WebSocketClient
import org.java_websocket.framing.Framedata
import org.java_websocket.handshake.ServerHandshake
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Validación de las correcciones C1, C2 y C4 (reconexiones). Tiempos escalados para pruebas rápidas. */
class LanLivenessTest {
    private val store = InMemoryPairingStore()
    private val record = PairingRecord("0123456789abcdef0123456789abcdef", "tablet-id", "Tab", CryptoBytes.b64(CryptoBytes.random(32)), 0)
        .also { store.save(it.copy(peerId = "watch-id", peerName = "Watch")) }
    private val received = Collections.synchronizedList(mutableListOf<HeartRatePacket>())
    private val rtts = AtomicInteger()
    private val authCount = AtomicInteger()
    private val disconnects = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = mutableListOf<LanServer>()

    private val listener = object : LanServerListener {
        override fun onClientAuthenticated(peer: PeerInfo) { authCount.incrementAndGet() }
        override fun onPacket(peer: PeerInfo, packet: HeartRatePacket) { received += packet }
        override fun onRtt(peer: PeerInfo, rttMs: Long) { rtts.incrementAndGet() }
        override fun onClientDisconnected(peer: PeerInfo?, reason: String) { disconnects.incrementAndGet() }
    }

    private fun server(idleMs: Long, port: Int = 0) = LanServer(LanServerConfig("tablet-id", "Tab", port = port, bindHost = "127.0.0.1", idleTimeoutMs = idleMs), store, listener)
        .also { servers += it }

    @After fun tearDown() { scope.cancel(); servers.forEach { it.close() } }

    private fun pkt(seq: Long) = HeartRatePacket(sessionId = "session-liveness", sequence = seq, device = "SM-R950", bpm = 70,
        timestampMs = System.currentTimeMillis(), sensorStatus = SensorStatus.ACTIVE)

    /** Cliente WebSocket que NO responde a los PING de WebSocket (CPU del reloj suspendida). */
    private inner class NoPongClient(port: Int) : WebSocketClient(URI("ws://127.0.0.1:$port${ProtocolConstants.LAN_WS_PATH}")) {
        val inbox = LinkedBlockingQueue<String>()
        val closed = CountDownLatch(1)
        lateinit var channel: SecureChannel
        override fun onOpen(handshakedata: ServerHandshake?) {}
        override fun onMessage(message: String) { inbox.put(message) }
        override fun onClose(code: Int, reason: String?, remote: Boolean) { closed.countDown() }
        override fun onError(ex: Exception?) {}
        override fun onWebsocketPing(conn: org.java_websocket.WebSocket?, f: Framedata?) {}

        fun authenticate() {
            assertTrue(connectBlocking(5, TimeUnit.SECONDS))
            val cn = CryptoBytes.random(16)
            send(LanMessage.encode(LanMessage.Hello(ProtocolConstants.PROTOCOL_VERSION, record.pairingId, CryptoBytes.b64(cn), 0)))
            val ch = LanMessage.decode(inbox.poll(5, TimeUnit.SECONDS)!!) as LanMessage.Challenge
            val keys = SessionKeys.derive(record.key(), record.pairingId, cn, CryptoBytes.unb64(ch.serverNonce)!!)
            send(LanMessage.encode(LanMessage.Auth(CryptoBytes.b64(keys.clientProof()))))
            assertTrue((LanMessage.decode(inbox.poll(5, TimeUnit.SECONDS)!!) as LanMessage.AuthResult).ok)
            channel = SecureChannel.forClient(keys)
        }

        fun ping() = send(LanMessage.encode(channel.seal(SecureBody.encode(SecureBody.Ping(System.currentTimeMillis(), 5)))))
    }

    @Test fun serverKeepsActiveClientEvenIfItCannotAnswerWebSocketPings() = runBlocking {
        val s = server(idleMs = 1_000); val port = s.start()
        val c = NoPongClient(port); c.authenticate()
        repeat(15) { c.ping(); delay(200) } // 3 s de actividad, 3× el tiempo de inactividad
        assertFalse("no debe cerrarse mientras llegan tramas", c.closed.await(0, TimeUnit.MILLISECONDS))
        assertEquals(0, disconnects.get())
        assertTrue(rtts.get() >= 10)
        c.closeBlocking()
    }

    @Test fun serverClosesAuthenticatedClientThatGoesSilent() = runBlocking {
        val s = server(idleMs = 800); val port = s.start()
        val c = NoPongClient(port); c.authenticate()
        assertTrue("sesión silenciosa cerrada tras el tiempo de inactividad", c.closed.await(4, TimeUnit.SECONDS))
        withTimeout(2_000) { while (disconnects.get() == 0) delay(20) }
    }

    @Test fun linkSurvivesSlowNetworkWithLatePongs() = runBlocking {
        val s = server(idleMs = 10_000); val port = s.start()
        DelayProxy(port).use { proxy ->
            proxy.serverToClientDelayMs = 700
            val link = LanLink(scope, LanClients.okHttp(), record, { LanEndpoint("127.0.0.1", proxy.port) },
                pingIntervalMs = 300, livenessTimeoutMs = 3_000, stepTimeoutMs = 5_000)
            link.start()
            withTimeout(8_000) { link.status.first { it.state == LinkState.CONNECTED } }
            repeat(25) { i -> link.send(pkt(i.toLong())); delay(200) } // 5 s con pongs llegando 700 ms tarde
            assertEquals("sin reconexiones", 1, link.sessionsEstablished)
            assertEquals(LinkState.CONNECTED, link.status.value.state)
            withTimeout(3_000) { while (received.size < 25) delay(20) }
            link.stop()
        }
    }

    @Test fun shortFreezeIsToleratedAndLongFreezeRecoversAutomatically() = runBlocking {
        val s = server(idleMs = 10_000); val port = s.start()
        DelayProxy(port).use { proxy ->
            val link = LanLink(scope, LanClients.okHttp(), record, { LanEndpoint("127.0.0.1", proxy.port) },
                pingIntervalMs = 300, livenessTimeoutMs = 2_500, maxBackoffMs = 500)
            link.start()
            withTimeout(8_000) { link.status.first { it.state == LinkState.CONNECTED } }
            proxy.frozen = true; delay(1_500); proxy.frozen = false // suspensión corta
            delay(800)
            assertEquals("una suspensión corta no provoca reconexión", 1, link.sessionsEstablished)
            proxy.frozen = true; delay(4_000); proxy.frozen = false // red caída más que el margen
            withTimeout(10_000) { while (link.sessionsEstablished < 2 || link.status.value.state != LinkState.CONNECTED) delay(50) }
            val before = received.size
            assertTrue(link.send(pkt(100)))
            withTimeout(3_000) { while (received.size == before) delay(20) }
            link.stop()
        }
    }

    @Test fun reconnectNowSkipsTheBackoff() = runBlocking {
        val s = server(idleMs = 10_000); val port = s.start()
        val link = LanLink(scope, LanClients.okHttp(), record, { LanEndpoint("127.0.0.1", port) }, maxBackoffMs = 60_000)
        link.start()
        withTimeout(5_000) { link.status.first { it.state == LinkState.CONNECTED } }
        s.stop()
        delay(3_500) // backoff acumulado: 1 s + 2 s + 4 s pendiente
        server(idleMs = 10_000, port = port).start()
        val t0 = System.nanoTime()
        link.reconnectNow()
        withTimeout(2_500) { link.status.first { it.state == LinkState.CONNECTED } }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_500)
        link.stop()
    }
}

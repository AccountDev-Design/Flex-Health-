package com.flexhealth.heartmonitor.lan

import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LanErrorCodes
import com.flexhealth.heartmonitor.protocol.LanMessage
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.SensorStatus
import com.flexhealth.heartmonitor.protocol.crypto.CryptoBytes
import com.flexhealth.heartmonitor.protocol.crypto.InMemoryPairingStore
import com.flexhealth.heartmonitor.protocol.crypto.PairingRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Prueba de integración real sobre sockets TCP (loopback): servidor Java-WebSocket (tablet)
 * y cliente OkHttp (reloj), con emparejamiento, autenticación, cifrado y reconexión.
 * No sustituye la prueba en hardware (radio Wi-Fi, mDNS, Wear OS), documentada en TESTING.md.
 */
class LanIntegrationTest {
    private val serverStore = InMemoryPairingStore()
    private val received = Collections.synchronizedList(mutableListOf<HeartRatePacket>())
    private val pairingRequests = Collections.synchronizedList(mutableListOf<PairingRequest>())
    private val authenticated = Collections.synchronizedList(mutableListOf<PeerInfo>())
    private val rtts = Collections.synchronizedList(mutableListOf<Long>())
    private var autoAccept = true
    private lateinit var server: LanServer
    private var port = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = LanClients.okHttp()

    private val listener = object : LanServerListener {
        override fun onPairingRequest(request: PairingRequest) {
            pairingRequests += request
            if (autoAccept) Thread { Thread.sleep(50); server.acceptPairing(request.id) }.start()
        }
        override fun onClientAuthenticated(peer: PeerInfo) { authenticated += peer }
        override fun onPacket(peer: PeerInfo, packet: HeartRatePacket) { received += packet }
        override fun onRtt(peer: PeerInfo, rttMs: Long) { rtts += rttMs }
    }

    private fun newServer(p: Int = 0) = LanServer(LanServerConfig("tablet-id", "Galaxy Tab S10 FE", port = p, bindHost = "127.0.0.1"), serverStore, listener)

    @Before fun setUp() = runBlocking { server = newServer(); port = server.start() }

    @After fun tearDown() { scope.cancel(); server.close() }

    private val endpoint get() = LanEndpoint("127.0.0.1", port)

    private suspend fun pair(): Pair<PairingRecord, String> {
        server.pairingOpen = true
        var watchCode = ""
        val rec = pairWithReceiver(http, endpoint, "watch-id", "Galaxy Watch6 Classic", { code, _ -> watchCode = code }, { true })
        return rec to watchCode
    }

    private fun pkt(seq: Long, bpm: Int?) = HeartRatePacket(
        sessionId = "session-lan-1", sequence = seq, device = "SM-R950", bpm = bpm,
        timestampMs = System.currentTimeMillis(), sensorStatus = SensorStatus.ACTIVE,
    )

    @Test fun pairingRequiresOpenWindow() = runBlocking {
        server.pairingOpen = false
        try {
            pairWithReceiver(http, endpoint, "watch-id", "W", { _, _ -> }, { true }); fail()
        } catch (e: LanException) { assertEquals(LanErrorCodes.PAIRING_CLOSED, e.code) }
    }

    @Test fun pairAuthenticateAndStreamEncryptedSamples() = runBlocking {
        val (record, watchCode) = pair()
        assertEquals("El código del reloj y el de la tablet deben coincidir", pairingRequests.single().code, watchCode)
        assertNotNull(serverStore.find(record.pairingId))

        val link = LanLink(scope, http, record, { endpoint }, pingIntervalMs = 200)
        link.start()
        withTimeout(5_000) { link.status.first { it.state == LinkState.CONNECTED } }
        for (i in 0 until 20) assertTrue(link.send(pkt(i.toLong(), 60 + i)))
        withTimeout(5_000) { while (received.size < 20) kotlinx.coroutines.delay(20) }
        assertEquals((0L until 20L).toList(), received.map { it.sequence })
        assertEquals("Galaxy Watch6 Classic", authenticated.single().name)
        withTimeout(5_000) { while (rtts.isEmpty()) kotlinx.coroutines.delay(20) }
        link.stop()
    }

    @Test fun rejectedPairingIsReportedOnWatch() = runBlocking {
        autoAccept = false
        server.pairingOpen = true
        val requested = CompletableDeferred<Unit>()
        val t = Thread { while (pairingRequests.isEmpty()) Thread.sleep(10); server.rejectPairing(pairingRequests[0].id); requested.complete(Unit) }
        t.start()
        try {
            pairWithReceiver(http, endpoint, "watch-id", "W", { _, _ -> }, { true }); fail()
        } catch (e: LanException) { assertTrue(e.message!!.contains("rechaz", ignoreCase = true)) }
        assertTrue(serverStore.all().isEmpty())
    }

    @Test fun wrongKeyIsRejectedAndLinkStopsRetrying() = runBlocking {
        val (record, _) = pair()
        val forged = record.copy(keyB64 = CryptoBytes.b64(CryptoBytes.random(32)))
        val link = LanLink(scope, http, forged, { endpoint })
        link.start()
        val st = withTimeout(5_000) { link.status.first { it.state == LinkState.ERROR && it.detail?.contains("emparejar") == true } }
        assertNotNull(st)
        assertTrue(authenticated.isEmpty())
        link.stop()
    }

    @Test fun unknownPairingRejected() = runBlocking {
        val fake = PairingRecord("0123456789abcdef0123456789abcdef", "tablet-id", "T", CryptoBytes.b64(CryptoBytes.random(32)), 0)
        val link = LanLink(scope, http, fake, { endpoint })
        link.start()
        withTimeout(5_000) { link.status.first { it.state == LinkState.ERROR && it.detail?.contains("emparejar") == true } }
        link.stop()
    }

    @Test fun unauthenticatedFramesAreNeverDelivered() {
        val closed = CountDownLatch(1)
        val ws = http.newWebSocket(Request.Builder().url(endpoint.url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(LanMessage.encode(LanMessage.Secure(0, CryptoBytes.b64(ByteArray(64)))))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.countDown() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.countDown() }
        })
        assertTrue("el servidor debe cerrar la conexión", closed.await(5, TimeUnit.SECONDS))
        ws.cancel()
        assertTrue(received.isEmpty())
    }

    @Test fun linkReconnectsAfterServerRestart() = runBlocking {
        val (record, _) = pair()
        val link = LanLink(scope, http, record, { endpoint }, maxBackoffMs = 500)
        link.start()
        withTimeout(5_000) { link.status.first { it.state == LinkState.CONNECTED } }
        server.stop()
        withTimeout(10_000) { link.status.first { it.state != LinkState.CONNECTED } }
        assertTrue("sin enlace las muestras se descartan, no se encolan", !link.send(pkt(0, 70)))
        server = newServer(port).also { it.start() }
        withTimeout(15_000) { link.status.first { it.state == LinkState.CONNECTED } }
        assertTrue(link.send(pkt(1, 71)))
        withTimeout(5_000) { while (received.isEmpty()) kotlinx.coroutines.delay(20) }
        assertEquals(1L, received.last().sequence)
        link.stop()
    }
}

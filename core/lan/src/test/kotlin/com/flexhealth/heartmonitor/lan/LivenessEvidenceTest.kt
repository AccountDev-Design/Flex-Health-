package com.flexhealth.heartmonitor.lan

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.java_websocket.client.WebSocketClient
import org.java_websocket.framing.Framedata
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Evidencia reproducible de las causas C1 y C2 (desconexiones con datos fluyendo) usando las mismas
 * librerías y la misma configuración de keepalive que tenía la versión 2f571ad/06866f6, con tiempos
 * escalados para que la prueba sea rápida. Estas pruebas documentan el comportamiento de las librerías;
 * la corrección se valida en LanLivenessTest.
 */
class LivenessEvidenceTest {

    private fun echoServer(lostTimeoutSec: Int, onClose: (String) -> Unit): WebSocketServer {
        val started = CompletableFuture<Unit>()
        val s = object : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
            override fun onOpen(conn: org.java_websocket.WebSocket, handshake: ClientHandshake) {}
            override fun onClose(conn: org.java_websocket.WebSocket, code: Int, reason: String?, remote: Boolean) { onClose("$code $reason") }
            override fun onMessage(conn: org.java_websocket.WebSocket, message: String) { conn.send(message) }
            override fun onError(conn: org.java_websocket.WebSocket?, ex: Exception) {}
            override fun onStart() { started.complete(Unit) }
        }
        s.isReuseAddr = true
        s.connectionLostTimeout = lostTimeoutSec
        s.start(); started.get(5, TimeUnit.SECONDS)
        return s
    }

    /** C1: el servidor cierra a un cliente que envía datos continuamente pero no responde PONG a tiempo. */
    @Test fun javaWebSocketDropsClientThatSendsDataButCannotPong() {
        val closed = CountDownLatch(1); var reason = ""
        val server = echoServer(lostTimeoutSec = 1) { reason = it; closed.countDown() }
        val client = object : WebSocketClient(URI("ws://127.0.0.1:${server.port}/")) {
            override fun onOpen(handshakedata: ServerHandshake?) {}
            override fun onMessage(message: String?) {}
            override fun onClose(code: Int, reason: String?, remote: Boolean) {}
            override fun onError(ex: Exception?) {}
            // Simula un reloj con la CPU suspendida: no contesta a los PING de WebSocket.
            override fun onWebsocketPing(conn: org.java_websocket.WebSocket?, f: Framedata?) {}
        }
        client.connectBlocking(5, TimeUnit.SECONDS)
        val sender = Thread { try { while (true) { client.send("sample"); Thread.sleep(200) } } catch (_: Exception) {} }.apply { isDaemon = true; start() }
        val wasClosed = closed.await(6, TimeUnit.SECONDS)
        sender.interrupt(); client.closeBlocking(); server.stop(500)
        assertTrue("Java-WebSocket debería cerrar por falta de PONG aunque lleguen datos ($reason)", wasClosed)
        assertTrue(reason, reason.contains("pong"))
    }

    /** C2: OkHttp con pingInterval falla en cuanto un PONG llega más tarde que el intervalo. */
    @Test fun okHttpPingIntervalFailsWhenAPongIsLate() {
        val server = echoServer(lostTimeoutSec = 0) {}
        DelayProxy(server.port).use { proxy ->
            proxy.serverToClientDelayMs = 700 // red Wi-Fi en ahorro de energía
            val failure = CompletableFuture<String>()
            val http = OkHttpClient.Builder().pingInterval(300, TimeUnit.MILLISECONDS).build()
            http.newWebSocket(Request.Builder().url("ws://127.0.0.1:${proxy.port}/").build(), object : WebSocketListener() {
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failure.complete(t.message ?: t.toString()) }
            })
            val msg = failure.get(10, TimeUnit.SECONDS)
            assertTrue(msg, msg.contains("didn't receive pong"))
            http.dispatcher.executorService.shutdownNow()
        }
        server.stop(500)
    }
}

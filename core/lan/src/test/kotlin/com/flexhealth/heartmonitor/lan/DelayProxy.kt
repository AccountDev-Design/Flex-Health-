package com.flexhealth.heartmonitor.lan

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

/**
 * Proxy TCP de pruebas que simula una red Wi-Fi con retardo (ahorro de energía) o una suspensión de la
 * CPU del reloj ([frozen]: no se reenvía nada en ninguna dirección mientras está activo).
 */
class DelayProxy(private val targetPort: Int) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    @Volatile var serverToClientDelayMs = 0L
    @Volatile var frozen = false
    private val sockets = CopyOnWriteArrayList<Socket>()

    init {
        Thread({
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                val upstream = Socket(InetAddress.getLoopbackAddress(), targetPort)
                sockets += client; sockets += upstream
                pump(client.getInputStream(), upstream.getOutputStream()) { 0L }
                pump(upstream.getInputStream(), client.getOutputStream()) { serverToClientDelayMs }
            }
        }, "proxy-accept").apply { isDaemon = true }.start()
    }

    private fun pump(input: InputStream, output: OutputStream, delay: () -> Long) {
        val queue = LinkedBlockingQueue<Pair<Long, ByteArray>>()
        Thread({
            val buf = ByteArray(8192)
            try {
                while (true) {
                    while (frozen) Thread.sleep(5)
                    val n = input.read(buf); if (n < 0) break
                    queue.put(System.currentTimeMillis() + delay() to buf.copyOf(n))
                }
            } catch (_: Exception) {}
            queue.put(-1L to ByteArray(0))
        }, "proxy-read").apply { isDaemon = true }.start()
        Thread({
            try {
                while (true) {
                    val (at, bytes) = queue.take(); if (at < 0) break
                    val wait = at - System.currentTimeMillis(); if (wait > 0) Thread.sleep(wait)
                    while (frozen) Thread.sleep(5)
                    output.write(bytes); output.flush()
                }
            } catch (_: Exception) {}
            runCatching { output.close() }
        }, "proxy-write").apply { isDaemon = true }.start()
    }

    override fun close() { runCatching { server.close() }; sockets.forEach { runCatching { it.close() } } }
}

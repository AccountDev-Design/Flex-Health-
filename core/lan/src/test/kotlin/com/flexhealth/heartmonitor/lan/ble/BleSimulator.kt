package com.flexhealth.heartmonitor.lan.ble

import com.flexhealth.heartmonitor.lan.SessionHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Simulador de un enlace GATT para pruebas en la JVM: el reloj escribe fragmentos con respuesta (RX) y
 * la tablet notifica (TX) con una notificación en vuelo, entregadas en hilos distintos (como los hilos
 * binder de Android). Permite MTU arbitraria, latencia, pérdida de notificaciones y cortes del enlace.
 * NO sustituye a la prueba en hardware (radio, pila Bluetooth de Samsung, permisos).
 */
class BleSimulator(private val host: SessionHost, private val scope: CoroutineScope) {
    @Volatile var mtu = 23
    @Volatile var latencyMs = 5L
    @Volatile var notificationLossRate = 0.0
    val connections = AtomicInteger()
    private val serverThread = Executors.newSingleThreadScheduledExecutor { Thread(it, "sim-gatt-server").apply { isDaemon = true } }
    private val clientThread = Executors.newSingleThreadScheduledExecutor { Thread(it, "sim-gatt-client").apply { isDaemon = true } }
    private val rnd = Random(42)
    @Volatile private var current: Link? = null

    inner class Link {
        @Volatile var up = true
        /** El reloj ha perdido el enlace pero la tablet no lo sabe: nada se propaga al servidor. */
        @Volatile var halfOpen = false
        lateinit var client: FramedClientPipe
        lateinit var server: FramedServerPipe
        val injected = mutableListOf<ByteArray>()
        val written = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())

        fun drop(reason: String) {
            if (!up) return
            up = false
            // Asíncrono, como onConnectionStateChange en Android.
            clientThread.execute { client.onDisconnected(reason) }
            serverThread.execute { server.onDisconnected(reason) }
        }
    }

    /** Equivale a conectar por GATT + descubrir servicios + activar notificaciones. */
    fun connect(label: String = "Tab S10 FE (BLE)"): FramedClientPipe {
        val link = Link()
        connections.incrementAndGet()
        link.server = FramedServerPipe(
            address = "AA:BB:CC:00:00:%02d".format(connections.get()),
            mtu = { mtu },
            transmit = { chunk ->
                if (!link.up) false else {
                    clientThread.schedule({
                        if (link.up && !link.halfOpen && rnd.nextDouble() >= notificationLossRate) link.client.onChunk(chunk)
                        serverThread.execute { link.server.onChunkSent(link.up) }
                    }, latencyMs, TimeUnit.MILLISECONDS)
                    true
                }
            },
            disconnect = { reason -> link.drop(reason) },
        )
        link.client = FramedClientPipe(label, scope, { mtu }, writeChunk = { chunk ->
            delay(latencyMs)
            if (!link.up || link.halfOpen) false else {
                link.written += chunk
                serverThread.execute { link.server.onChunk(chunk) }
                true
            }
        }, release = { reason -> if (!link.halfOpen) link.drop(reason) })
        link.server.session = host.attach(link.server)
        current = link
        return link.client
    }

    /**
     * Conexión «medio abierta»: el reloj pierde el enlace pero la tablet no se entera (p. ej. el reloj se
     * reinicia). La sesión antigua sigue viva en la tablet hasta que otra la sustituya o caduque.
     */
    fun dropClientOnly(reason: String = "watch restarted") {
        val l = current ?: return
        l.halfOpen = true
        clientThread.execute { l.client.onDisconnected(reason) }
        current = null
    }

    /** Corte del enlace por radio (fuera de alcance, Bluetooth apagado…). */
    fun dropCurrent(reason: String = "link loss") { current?.drop(reason) }

    /** Reinyecta en la tablet fragmentos capturados (ataque de repetición). */
    fun replay(chunks: List<ByteArray>) { val l = current ?: return; chunks.forEach { c -> serverThread.execute { l.server.onChunk(c) } } }

    /** Escribe bytes arbitrarios en la característica RX (atacante en el enlace). */
    fun inject(bytes: ByteArray) { val l = current ?: return; serverThread.execute { l.server.onChunk(bytes) } }

    fun written(): List<ByteArray> = current?.written?.toList().orEmpty()
    fun isUp() = current?.up == true

    fun close() { serverThread.shutdownNow(); clientThread.shutdownNow() }
}

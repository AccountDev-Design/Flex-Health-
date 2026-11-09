package com.flexhealth.heartmonitor.lan.ble

import com.flexhealth.heartmonitor.lan.ClientPipe
import com.flexhealth.heartmonitor.lan.LanException
import com.flexhealth.heartmonitor.lan.ServerPipe
import com.flexhealth.heartmonitor.lan.SessionHost
import com.flexhealth.heartmonitor.protocol.LanMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lado reloj (cliente GATT) independiente de Android: serializa mensajes en fragmentos y los escribe en
 * orden con [writeChunk] (una escritura GATT con respuesta cada vez); reensambla las notificaciones
 * recibidas con [onChunk]. La capa Android solo aporta la E/S GATT.
 */
class FramedClientPipe(
    override val label: String,
    scope: CoroutineScope,
    private val mtu: () -> Int,
    /** Escribe un fragmento y suspende hasta su confirmación. `false` = fallo de escritura (enlace roto). */
    private val writeChunk: suspend (ByteArray) -> Boolean,
    /** Libera el enlace subyacente (desconexión GATT). Se llama una sola vez. */
    private val release: (String) -> Unit,
) : ClientPipe {
    private val incoming = Channel<String>(capacity = 64)
    private val outgoing = Channel<ByteArray>(capacity = 64)
    private val reassembler = BleFraming.Reassembler()
    private val ids = AtomicInteger(0)
    @Volatile var closed = false; private set
    val framingErrors = AtomicInteger(0)

    private val writer = scope.launch {
        for (msg in outgoing) {
            val id = ids.getAndIncrement()
            for (chunk in BleFraming.fragment(msg, mtu(), id)) {
                if (!writeChunk(chunk)) { fail("Error de escritura Bluetooth"); return@launch }
            }
        }
    }

    override fun send(msg: LanMessage): Boolean =
        !closed && outgoing.trySend(LanMessage.encode(msg).encodeToByteArray()).isSuccess

    /** Notificación recibida del servidor GATT. */
    fun onChunk(bytes: ByteArray) {
        val msg = try { reassembler.accept(bytes) } catch (e: BleFramingException) { framingErrors.incrementAndGet(); null } ?: return
        if (incoming.trySend(msg.decodeToString()).isFailure) fail("Demasiados mensajes sin procesar")
    }

    /** El enlace GATT se cerró. */
    fun onDisconnected(reason: String) = fail(reason)

    override suspend fun receive(timeoutMs: Long): LanMessage {
        val text = try {
            withTimeout(timeoutMs) { incoming.receive() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw LanException("Tiempo de espera agotado", cause = e)
        } catch (e: ClosedReceiveChannelException) {
            throw LanException("Conexión Bluetooth cerrada", cause = e)
        }
        val msg = LanMessage.decode(text) ?: throw LanException("Mensaje malformado")
        if (msg is LanMessage.Error) throw LanException(msg.message ?: msg.code, msg.code)
        return msg
    }

    override fun close(reason: String) = fail(reason)
    override fun cancel() = fail("cancelled")

    private fun fail(reason: String) {
        synchronized(this) { if (closed) return; closed = true }
        incoming.close(LanException(reason))
        outgoing.close()
        writer.cancel()
        release(reason)
    }
}

/**
 * Lado tablet (servidor GATT) independiente de Android. Envía por notificaciones con UNA notificación en
 * vuelo cada vez ([transmit] → esperar [onChunkSent]), como exige la pila Bluetooth de Android, y
 * reensambla las escrituras del reloj hacia la [SessionHost.Session].
 */
class FramedServerPipe(
    override val address: String,
    private val mtu: () -> Int,
    /** Envía una notificación. `false` = no se pudo enviar (enlace roto). */
    private val transmit: (ByteArray) -> Boolean,
    /** Corta la conexión GATT de este dispositivo. */
    private val disconnect: (String) -> Unit,
    private val maxQueuedChunks: Int = 256,
) : ServerPipe {
    @Volatile var session: SessionHost.Session? = null
    private val reassembler = BleFraming.Reassembler()
    private val queue = ArrayDeque<ByteArray>()
    private var inFlight = false
    private var closing: String? = null
    private var nextId = 0
    @Volatile private var open = true
    @Volatile var framingErrors = 0; private set

    override val isOpen: Boolean get() = open && closing == null

    @Synchronized
    override fun send(text: String) {
        if (!isOpen) return
        val chunks = BleFraming.fragment(text.encodeToByteArray(), mtu(), nextId++)
        if (queue.size + chunks.size > maxQueuedChunks) { shutdown("cola de salida llena"); return }
        queue.addAll(chunks)
        pump()
    }

    /** Escritura recibida del reloj (característica RX). */
    fun onChunk(bytes: ByteArray) {
        val msg = try { reassembler.accept(bytes) } catch (e: BleFramingException) { framingErrors++; null } ?: return
        session?.onText(msg.decodeToString())
    }

    /** Confirmación de la pila Bluetooth (onNotificationSent). */
    @Synchronized
    fun onChunkSent(success: Boolean) {
        inFlight = false
        if (!success) { shutdown("fallo de notificación"); return }
        pump()
    }

    /** Cierre ordenado: se vacía la cola (p. ej. un mensaje de error) antes de cortar. */
    @Synchronized
    override fun close(reason: String) {
        if (!open || closing != null) return
        closing = reason
        if (queue.isEmpty() && !inFlight) shutdown(reason)
    }

    /** La conexión GATT ya se cerró (desde la pila). */
    fun onDisconnected(reason: String) {
        synchronized(this) { open = false; queue.clear() }
        session?.onClosed(reason)
    }

    private fun pump() {
        if (inFlight || !open) return
        val next = queue.removeFirstOrNull()
        if (next == null) { closing?.let { shutdown(it) }; return }
        inFlight = transmit(next)
        if (!inFlight) shutdown("no se pudo notificar")
    }

    private fun shutdown(reason: String) {
        if (!open) return
        open = false
        queue.clear()
        disconnect(reason)
    }
}

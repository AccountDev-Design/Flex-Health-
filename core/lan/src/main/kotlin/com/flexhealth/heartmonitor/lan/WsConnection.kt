package com.flexhealth.heartmonitor.lan

import com.flexhealth.heartmonitor.protocol.LanMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException

/** Error de protocolo o de red en el canal local. [code] es un LanErrorCodes cuando lo envió el par. */
class LanException(message: String, val code: String? = null, cause: Throwable? = null) : IOException(message, cause)

/** Adaptador de un WebSocket de OkHttp a corrutinas (una conexión). */
internal class WsConnection private constructor() : WebSocketListener() {
    private val incoming = Channel<String>(capacity = 64)
    private val opened = CompletableDeferred<Unit>()
    val closed = CompletableDeferred<String>()
    @Volatile private var socket: WebSocket? = null

    override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (incoming.trySend(text).isFailure) webSocket.close(1008, "flood")
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        incoming.close(); closed.complete("closed $code $reason")
        opened.completeExceptionally(LanException("Cerrado: $code $reason"))
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        opened.completeExceptionally(t)
        incoming.close(t)
        closed.complete("failure: ${t.message}")
    }

    fun send(msg: LanMessage): Boolean = socket?.send(LanMessage.encode(msg)) ?: false

    /** Siguiente mensaje; si el par envía un Error, lanza [LanException] con su código. */
    suspend fun receive(timeoutMs: Long): LanMessage {
        val text = try {
            withTimeout(timeoutMs) { incoming.receive() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw LanException("Tiempo de espera agotado", cause = e)
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            throw LanException("Conexión cerrada por el otro extremo", cause = e)
        }
        val msg = LanMessage.decode(text) ?: throw LanException("Mensaje malformado")
        if (msg is LanMessage.Error) throw LanException(msg.message ?: msg.code, msg.code)
        return msg
    }

    fun close(reason: String = "bye") {
        socket?.close(1000, reason)
        incoming.close()
    }

    fun cancel() { socket?.cancel() }

    companion object {
        suspend fun open(client: OkHttpClient, url: String, timeoutMs: Long): WsConnection {
            val c = WsConnection()
            c.socket = client.newWebSocket(Request.Builder().url(url).build(), c)
            try {
                withTimeout(timeoutMs) { c.opened.await() }
            } catch (e: Throwable) {
                c.cancel()
                throw if (e is LanException) e else LanException("No se pudo conectar a $url: ${e.message}", cause = e)
            }
            return c
        }
    }
}


package com.autovoice.gatewayclient

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** The only production adapter from the generic gateway socket contract to OkHttp WebSocket. */
class OkHttpGatewaySocketTransport(private val client: OkHttpClient) : GatewaySocketTransport {
    override fun open(url: String, listener: GatewaySocketListener): GatewaySocket {
        val handle = OkHttpSocketHandle()
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handle.attach(webSocket)
                listener.onText(handle, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handle.attach(webSocket)
                listener.onBinary(handle, bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handle.attach(webSocket)
                listener.onFailure(handle, t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                handle.attach(webSocket)
                listener.onClosing(handle, code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handle.attach(webSocket)
                listener.onClosed(handle, code, reason)
            }
        })
        handle.attach(socket)
        return handle
    }

    private class OkHttpSocketHandle : GatewaySocket {
        @Volatile private var delegate: WebSocket? = null

        fun attach(socket: WebSocket) {
            delegate = socket
        }

        override fun send(text: String): Boolean = delegate?.send(text) ?: false
        override fun send(bytes: ByteArray): Boolean = delegate?.send(bytes.toByteString()) ?: false
        override fun close(code: Int, reason: String): Boolean = delegate?.close(code, reason) ?: false
        override fun cancel() { delegate?.cancel() }
    }
}

package com.autovoice.gatewayclient

/** Transport-only WebSocket handle. Protocol frames and session policy belong to GatewayClient. */
interface GatewaySocket {
    fun send(text: String): Boolean
    fun send(bytes: ByteArray): Boolean
    fun close(code: Int, reason: String): Boolean
    fun cancel()
}

/** A single connection's events; callbacks receive the handle returned by [GatewaySocketTransport.open]. */
interface GatewaySocketListener {
    fun onText(socket: GatewaySocket, text: String)
    fun onBinary(socket: GatewaySocket, bytes: ByteArray)
    fun onFailure(socket: GatewaySocket, error: Throwable)
    fun onClosing(socket: GatewaySocket, code: Int, reason: String)
    fun onClosed(socket: GatewaySocket, code: Int, reason: String)
}

/** Replaceable WebSocket transport boundary; no REST or speech protocol is exposed. */
fun interface GatewaySocketTransport {
    fun open(url: String, listener: GatewaySocketListener): GatewaySocket
}

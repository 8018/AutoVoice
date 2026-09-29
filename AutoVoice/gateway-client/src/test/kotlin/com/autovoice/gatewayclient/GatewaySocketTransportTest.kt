package com.autovoice.gatewayclient

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GatewaySocketTransportTest {
    private class FakeSocket(private val listener: GatewaySocketListener) : GatewaySocket {
        val texts = mutableListOf<String>()
        val binaries = mutableListOf<ByteArray>()
        var closed = false
        var cancelled = false

        override fun send(text: String): Boolean {
            texts += text
            if (text.contains("\"type\":\"hello\"")) {
                listener.onText(this, """{"type":"ready","payload":{"sessionId":"session-1"}}""")
            }
            return true
        }

        override fun send(bytes: ByteArray): Boolean {
            binaries += bytes.copyOf()
            return true
        }

        override fun close(code: Int, reason: String): Boolean {
            closed = true
            return true
        }

        override fun cancel() { cancelled = true }
    }

    @Test
    fun `gateway client uses socket contract for handshake frames and disconnect`() = runBlocking {
        lateinit var socket: FakeSocket
        val transport = GatewaySocketTransport { _, listener ->
            FakeSocket(listener).also { socket = it }
        }
        val client = GatewayClient("ws://gateway.test/ws", transport, maxRetries = 0)

        client.connect()
        assertEquals(GatewayConnectionState.READY, client.connectionState.value)
        val hello = com.google.gson.Gson().fromJson(socket.texts.single(), JsonObject::class.java)
        assertEquals("hello", hello.get("type").asString)
        assertEquals("session-1", client.currentSessionId())

        client.send("ping", mapOf("value" to 1))
        client.send(byteArrayOf(1, 2, 3))
        assertEquals(2, socket.texts.size)
        assertTrue(socket.binaries.single().contentEquals(byteArrayOf(1, 2, 3)))

        client.disconnect()
        assertTrue(socket.closed)
        assertFalse(socket.cancelled)
        assertEquals(GatewayConnectionState.DISCONNECTED, client.connectionState.value)
    }
}

package com.autovoice.app

import com.autovoice.gatewayclient.GatewayClient
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GatewayProtocolSenderTextTest {
    private val gson = Gson()

    @Test
    fun `text command sends one explicit request without audio frames`() = runBlocking {
        withGateway(advertiseText = true) { client, messages ->
            GatewayProtocolSender(client).textRequest(
                "session-1", "req-1", "req-1", "text-seg-1", "导航到公司", "zh-CN",
            )
            val frame = gson.fromJson(messages.poll(3, TimeUnit.SECONDS), JsonObject::class.java)
            assertEquals("text_request", frame.get("type").asString)
            val payload = frame.getAsJsonObject("payload")
            assertEquals("req-1", payload.get("requestId").asString)
            assertEquals("text-seg-1", payload.get("segmentId").asString)
            assertEquals("导航到公司", payload.get("text").asString)
            assertEquals("text", payload.get("inputSource").asString)
            assertEquals(1, payload.get("contextVersion").asInt)
            assertEquals(0, payload.getAsJsonObject("context").size())
            assertEquals(null, messages.poll(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun `text command is rejected locally when server does not advertise capability`() = runBlocking {
        withGateway(advertiseText = false) { client, messages ->
            val failure = assertThrows(IllegalStateException::class.java) {
                GatewayProtocolSender(client).textRequest(
                    "session-1", "req-1", "req-1", "text-seg-1", "你好", "zh-CN",
                )
            }
            assertTrue(failure.message!!.contains("UNSUPPORTED_CAPABILITY"))
            assertEquals(null, messages.poll(100, TimeUnit.MILLISECONDS))
        }
    }

    private suspend fun withGateway(
        advertiseText: Boolean,
        block: suspend (GatewayClient, LinkedBlockingQueue<String>) -> Unit,
    ) {
        val server = MockWebServer()
        val messages = LinkedBlockingQueue<String>()
        val closed = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = gson.fromJson(text, JsonObject::class.java)
                if (frame.get("type").asString == "hello") {
                    val capability = if (advertiseText) ",\"capabilities\":[\"text_recognition_v1\"]" else ""
                    webSocket.send(
                        """{"type":"ready","payload":{"sessionId":"session-1","language":"zh-CN","protocolVersion":"1.1"$capability}}""",
                    )
                } else messages.add(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, "close reply")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.countDown()
            }
        }))
        server.start()
        val http = OkHttpClient()
        val client = GatewayClient("ws://localhost:${server.port}/", http, gson)
        try {
            client.connect()
            block(client, messages)
        } finally {
            client.disconnect()
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            server.shutdown()
            http.dispatcher.executorService.shutdown()
        }
    }
}

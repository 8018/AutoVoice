package com.autovoice.app

import com.autovoice.gatewayclient.GatewayClient
import com.autovoice.gatewayclient.GatewayPayloadParser
import com.autovoice.voicecore.TextReply
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voiceengine.cloud.CloudNluEngine
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GatewayBusinessTextChannelTest {
    @Test
    fun `explicit text flows through reply slot and cloud NLU without audio`() = runBlocking {
        val gson = Gson()
        val server = MockWebServer()
        val closed = CountDownLatch(1)
        val received = CopyOnWriteArrayList<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = gson.fromJson(text, JsonObject::class.java)
                when (frame.get("type").asString) {
                    "hello" -> webSocket.send(
                        """{"type":"ready","payload":{"sessionId":"session-1","language":"zh-CN","protocolVersion":"1.1","capabilities":["text_recognition_v1"]}}""",
                    )
                    "text_request" -> {
                        val payload = frame.getAsJsonObject("payload")
                        received += payload.get("text").asString
                        val segmentId = payload.get("segmentId").asString
                        val requestId = payload.get("requestId").asString
                        webSocket.send(
                            """{"type":"reply","payload":{"kind":"text","text":"收到","speakText":"收到","segmentId":"$segmentId","requestId":"$requestId"}}""",
                        )
                    }
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, "close reply")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.countDown() }
        }))
        server.start()
        val http = OkHttpClient()
        val client = GatewayClient("ws://localhost:${server.port}/", http, gson)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = GatewayBridge(client, scope)
        val nlu = CloudNluEngine(bridge, GatewayPayloadParser(), DecisionSink {}, Channel(Channel.BUFFERED),
            {}, { _, _, _ -> })
        val registration = nlu.register()
        val channel = GatewayBusinessTextChannel(client, bridge, GatewayProtocolSender(client),
            { client.connect() }, { client.currentSessionId() ?: "" }, {})
        try {
            val reply = channel.run("req-1", "导航到公司")
            assertEquals("收到", (reply as TextReply).text)
            assertEquals(listOf("导航到公司"), received)
        } finally {
            registration.unregister()
            scope.cancel()
            client.disconnect()
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            server.shutdown()
            http.dispatcher.executorService.shutdown()
        }
    }
}

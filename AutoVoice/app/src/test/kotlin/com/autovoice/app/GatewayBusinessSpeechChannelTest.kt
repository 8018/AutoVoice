package com.autovoice.app

import com.autovoice.gatewayclient.GatewayClient
import com.autovoice.voicecore.TextReply
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.session.CloudRequestFailedException
import com.autovoice.voiceengine.RecognitionGate
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GatewayBusinessSpeechChannelTest {
    @Test
    fun `queued streaming audio is not uploaded after recognition stops`() = runBlocking {
        val http = OkHttpClient()
        val client = GatewayClient("ws://127.0.0.1:1/ws", http, Gson())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = GatewayBridge(client, DecisionSink {}, scope)
        val gate = RecognitionGate().apply { startRecognition() }
        val enteredReady = CompletableDeferred<Unit>()
        val releaseReady = CompletableDeferred<Unit>()
        var locationCalls = 0
        val speech = GatewayBusinessSpeechChannel(
            client, bridge, GatewayProtocolSender(client), scope,
            ensureReady = {
                enteredReady.complete(Unit)
                releaseReady.await()
            },
            sessionId = { "session-1" },
            location = { locationCalls++; null },
            navigationContext = { null },
            onTransportFailure = { _, _ -> },
            clearReplyText = {},
            recognitionGate = gate,
        )
        try {
            speech.beginStreamingTurn("turn-1")
            speech.appendStreamingAudio(byteArrayOf(1, 2, 3, 4))
            withTimeout(2_000) { enteredReady.await() }
            gate.stopRecognition()
            releaseReady.complete(Unit)
            val failure = runCatching {
                withTimeout(2_000) { speech.run(byteArrayOf(), "turn-1") }
            }.exceptionOrNull()
            assertTrue(failure is CloudRequestFailedException)
            assertFalse(gate.recognitionEnabled)
            assertEquals(0, locationCalls, "queued upload must be rejected before audio_start")
        } finally {
            speech.close()
            scope.cancel()
            client.disconnect()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `finalized streaming input completes after recognition stops`() = runBlocking {
        val server = MockWebServer()
        val sentTypes = ConcurrentLinkedQueue<String>()
        val payloads = ConcurrentLinkedQueue<JsonObject>()
        val audioChunks = ConcurrentLinkedQueue<ByteArray>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, "close reply")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = Gson().fromJson(text, JsonObject::class.java)
                val type = frame.get("type").asString
                sentTypes.add(type)
                if (type == "hello") {
                    webSocket.send("""{"type":"ready","payload":{"sessionId":"session-1"}}""")
                } else {
                    payloads.add(frame.getAsJsonObject("payload"))
                    if (type == "audio_end") {
                        val segmentId = payloads.first { it.has("segmentId") }.get("segmentId").asString
                        webSocket.send("""{"type":"reply","payload":{"kind":"text","text":"ok","segmentId":"$segmentId"}}""")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                audioChunks.add(bytes.toByteArray())
            }
        }))
        server.start()
        val http = OkHttpClient()
        val client = GatewayClient("ws://localhost:${server.port}/", http, Gson())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = GatewayBridge(client, DecisionSink {}, scope)
        val gate = RecognitionGate().apply { startRecognition() }
        val enteredReady = CompletableDeferred<Unit>()
        val releaseReady = CompletableDeferred<Unit>()
        val speech = GatewayBusinessSpeechChannel(
            client, bridge, GatewayProtocolSender(client), scope,
            ensureReady = {
                enteredReady.complete(Unit)
                releaseReady.await()
                client.connect()
            },
            sessionId = { "session-1" },
            location = { null }, navigationContext = { null },
            onTransportFailure = { _, _ -> }, clearReplyText = {}, recognitionGate = gate,
        )
        try {
            speech.beginStreamingTurn("turn-1")
            speech.appendStreamingAudio(byteArrayOf(1, 2, 3, 4))
            withTimeout(2_000) { enteredReady.await() }
            speech.finishStreamingTurn("turn-1")
            assertTrue(speech.isInputFinalized("turn-1"))
            gate.stopRecognition()
            releaseReady.complete(Unit)
            val reply = withTimeout(3_000) { speech.run(byteArrayOf(), "turn-1") }
            assertEquals("ok", (reply as TextReply).text)
            assertEquals(1, sentTypes.count { it == "audio_start" })
            assertEquals(1, sentTypes.count { it == "audio_end" })
            assertTrue(audioChunks.single().contentEquals(byteArrayOf(1, 2, 3, 4)))
        } finally {
            speech.close()
            scope.cancel()
            client.disconnect()
            server.shutdown()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `ordinary speech sends one audio lifecycle and receives its correlated reply`() = runBlocking {
        val server = MockWebServer()
        val sentTypes = ConcurrentLinkedQueue<String>()
        val payloads = ConcurrentLinkedQueue<JsonObject>()
        val audioChunks = ConcurrentLinkedQueue<ByteArray>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, "close reply")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = Gson().fromJson(text, JsonObject::class.java)
                val type = frame.get("type").asString
                sentTypes.add(type)
                if (type == "hello") {
                    webSocket.send("""{"type":"ready","payload":{"sessionId":"session-1"}}""")
                } else {
                    payloads.add(frame.getAsJsonObject("payload"))
                    if (type == "audio_end") {
                        val segmentId = payloads.first { it.has("segmentId") }.get("segmentId").asString
                        webSocket.send("""{"type":"reply","payload":{"kind":"text","text":"ok","segmentId":"$segmentId"}}""")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                audioChunks.add(bytes.toByteArray())
            }
        }))
        server.start()
        val http = OkHttpClient()
        val client = GatewayClient("ws://localhost:${server.port}/", http, Gson())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = GatewayBridge(client, DecisionSink {}, scope)
        val failures = mutableListOf<Throwable>()
        val speech = GatewayBusinessSpeechChannel(
            client, bridge, GatewayProtocolSender(client), scope,
            ensureReady = { client.connect() }, sessionId = { "session-1" },
            location = { null }, navigationContext = { null },
            onTransportFailure = { error, _ -> failures.add(error) },
            clearReplyText = {},
        )
        try {
            val reply = withTimeout(3_000) { speech.run(byteArrayOf(1, 2, 3, 4), "turn-1") }
            assertEquals("ok", (reply as TextReply).text)
            assertEquals(1, sentTypes.count { it == "audio_start" })
            assertEquals(1, sentTypes.count { it == "audio_end" })
            assertTrue(audioChunks.single().contentEquals(byteArrayOf(1, 2, 3, 4)))
            assertEquals("turn-1", payloads.first { it.has("utteranceId") }.get("utteranceId").asString)
            assertTrue(failures.isEmpty())
        } finally {
            speech.close()
            scope.cancel()
            client.disconnect()
            server.shutdown()
            http.dispatcher.executorService.shutdown()
        }
    }
}

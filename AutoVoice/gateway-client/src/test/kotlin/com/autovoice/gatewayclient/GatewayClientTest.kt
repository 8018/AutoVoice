package com.autovoice.gatewayclient

import com.autovoice.voicecore.ActionReply
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.GatewayMessage
import com.autovoice.voicecore.SlotValue
import com.autovoice.voicecore.TextReply
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * GatewayClient 集成测试：MockWebServer 真实 WS 握手扮演网关
 * （接 hello 回 ready、收 audio_start/二进制/audio_end 后回 decision + reply）。
 * reply 的三种 kind 直接读 shared/fixtures 下的 fixture 文件比对。
 */
class GatewayClientTest {

    private val gson = Gson()

    /** 一句话语段 PCM：1600 字节 @16kHz/S16LE = 50ms，audio_end 应换算 durationMs=50。 */
    private val pcm = ByteArray(1600) { (it % 251).toByte() }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResource(name)) { "shared/fixtures 未接线: $name" }.readText()

    private fun parse(text: String): GatewayMessage {
        val root = gson.fromJson(text, JsonObject::class.java)
        return GatewayMessage(root.get("type").asString, root.getAsJsonObject("payload"))
    }

    private fun readyFrame(sessionId: String) =
        """{"type":"ready","payload":{"sessionId":"$sessionId","language":"zh-CN","protocolVersion":"1.0"}}"""

    private fun decisionFrame() =
        """{"type":"decision","payload":{"arbiter":"cloud","route":"nlu-traditional","reason":"nlu_first","utteranceId":"u-1","timestampMs":1723104000000}}"""

    /**
     * 假扮网关的 MockWebServer：记录服务端收到的帧与 PCM；[upgrade] 供多次连接复用。
     * [closed] 在服务端收到客户端 close 握手完成后 countDown——测试在 [GatewayClient.disconnect]
     * 后必须先等它，再 [MockWebServer.shutdown]（否则 shutdown 会因打开的 WS 连接等待超时）。
     */
    private inner class FakeGateway {
        val server = MockWebServer()
        val frames = CopyOnWriteArrayList<GatewayMessage>()
        val pcm = ByteArrayOutputStream()
        val closed = CountDownLatch(1)

        /** 收到 audio_end 后的服务端行为（先 decision 后 reply，协议 §5 时序）。 */
        var onAudioEnd: (WebSocket, GatewayMessage) -> Unit = { _, _ -> }

        fun upgrade(): MockResponse =
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = parse(text)
                    frames.add(msg)
                    when (msg.type) {
                        "hello" -> webSocket.send(readyFrame("srv-sess-1"))
                        "audio_end" -> onAudioEnd(webSocket, msg)
                        else -> Unit
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    pcm.write(bytes.toByteArray())
                }

                // MockWebServer 服务端不会自动回 close 帧：需在 onClosing 时回一帧，
                // 客户端 close 握手才能完成（否则客户端 onClosed 永不触发、shutdown 超时）。
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, "close reply")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.countDown()
                }
            })

        fun start() = server.start()

        /** 优雅关闭：客户端 disconnect → 等 close 握手完成 → 关服务端与 OkHttp 线程池。 */
        fun closeAll(client: GatewayClient, okHttp: OkHttpClient) {
            client.disconnect()
            assertTrue(closed.await(3, TimeUnit.SECONDS), "等待服务端收到 close 握手超时")
            server.shutdown()
            okHttp.dispatcher.executorService.shutdown()
        }
    }

    /** 轮询等待条件成立（真实时间，runBlocking 内 delay 为真实延时）。 */
    private suspend fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(10)
        }
        return condition()
    }

    @Test
    fun `hello ready audio segment round trip with decision then action reply`() = runBlocking {
        val gateway = FakeGateway()
        gateway.start()
        val actionFixture = fixture("gateway-reply-action.json")
        // 协议 §5 时序：decision（必发）先于 reply
        gateway.onAudioEnd = { ws, _ ->
            ws.send(decisionFrame())
            ws.send(actionFixture)
        }
        gateway.server.enqueue(gateway.upgrade())
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        try {
            client.connect()

            val received = mutableListOf<GatewayMessage>()
            val collector = launch { client.messages.collect { received.add(it) } }

            client.sendAudioStart("srv-sess-1", "seg-1")
            client.sendAudioChunk(pcm)
            client.sendAudioEnd("srv-sess-1")

            // 事件流顺序：ready → decision → reply
            assertTrue(
                awaitTrue { received.map { it.type } == listOf("ready", "decision", "reply") },
                "流事件顺序应为 [ready, decision, reply]，实际: ${received.map { it.type }}",
            )

            // ready：sessionId 以服务端回执为准
            assertEquals("srv-sess-1", received[0].payload.get("sessionId").asString)
            assertEquals("zh-CN", received[0].payload.get("language").asString)
            assertEquals("1.0", received[0].payload.get("protocolVersion").asString)

            // decision 事件原样透传
            assertEquals("cloud", received[1].payload.get("arbiter").asString)
            assertEquals("nlu_first", received[1].payload.get("reason").asString)

            // reply(action)：按 fixture 解析
            val reply = client.parseReply(received[2].payload)
            assertNotNull(reply)
            assertTrue(reply is ActionReply)
            reply as ActionReply
            assertEquals("已为您把空调调到24度", reply.speakText)
            assertEquals("1.0", reply.intent.schemaVersion)
            assertEquals("climate", reply.intent.domain)
            assertEquals("set_temperature", reply.intent.intent)
            assertEquals(0.95, reply.intent.confidence, 1e-9)
            assertEquals(SlotValue.Number(24.0), reply.intent.slots["temperature"])
            assertEquals(SlotValue.EnumValue("driver"), reply.intent.slots["zone"])
            assertEquals("nlu.iflytek.api", reply.intent.source)

            // 服务端实际收到的帧：hello → audio_start → 二进制 → audio_end（payload 字段照 protocol.md）
            assertTrue(awaitTrue { gateway.frames.size >= 3 })
            assertEquals(listOf("hello", "audio_start", "audio_end"), gateway.frames.map { it.type })
            val helloFixture = gson.fromJson(fixture("gateway-hello.json"), JsonObject::class.java)
                .getAsJsonObject("payload")
            val helloPayload = gateway.frames[0].payload
            assertEquals(helloFixture.get("client").asString, helloPayload.get("client").asString)
            assertEquals(helloFixture.get("protocolVersion").asString, helloPayload.get("protocolVersion").asString)
            assertNull(helloPayload.get("sessionId"), "客户端不预生成 sessionId（服务端权威）")
            val startPayload = gateway.frames[1].payload
            assertEquals("srv-sess-1", startPayload.get("sessionId").asString)
            assertEquals(16000, startPayload.get("sampleRate").asInt)
            assertEquals(1, startPayload.get("channels").asInt)
            assertEquals("pcm_s16le", startPayload.get("encoding").asString)
            assertEquals("seg-1", startPayload.get("segmentId").asString, "audio_start 应携带 segmentId")
            val endPayload = gateway.frames[2].payload
            assertEquals("srv-sess-1", endPayload.get("sessionId").asString)
            assertEquals(50L, endPayload.get("durationMs").asLong, "1600 字节 @16kHz/S16LE = 50ms")
            assertArrayEquals(pcm, gateway.pcm.toByteArray())

            collector.cancel()
        } finally {
            gateway.closeAll(client, okHttp)
        }
    }

    @Test
    fun `audio reply decodes fixture dataBase64`() = runBlocking {
        val gateway = FakeGateway()
        gateway.start()
        val audioFixture = fixture("gateway-reply-audio.json")
        gateway.onAudioEnd = { ws, _ -> ws.send(audioFixture) }
        gateway.server.enqueue(gateway.upgrade())
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        try {
            client.connect()
            val received = mutableListOf<GatewayMessage>()
            val collector = launch { client.messages.collect { received.add(it) } }

            client.sendAudioStart("srv-sess-1")
            client.sendAudioChunk(pcm)
            client.sendAudioEnd("srv-sess-1")

            assertTrue(awaitTrue { received.any { it.type == "reply" } }, "应收到 reply")
            val reply = client.parseReply(received.first { it.type == "reply" }.payload)
            assertNotNull(reply)
            assertTrue(reply is AudioReply)
            reply as AudioReply
            assertEquals("audio/wav", reply.mime)
            assertEquals("已为您把空调调到24度", reply.speakText)
            assertNull(reply.intent, "网关下行 audio 的 intent 为 null 时省略字段")

            // base64 往返：解析出的音频字节与 fixture 的 dataBase64 解码结果逐字节一致
            val fixturePayload = gson.fromJson(audioFixture, JsonObject::class.java).getAsJsonObject("payload")
            val fixtureBase64 = fixturePayload.get("dataBase64").asString
            assertArrayEquals(Base64.getDecoder().decode(fixtureBase64), reply.data)
            assertEquals(fixtureBase64, Base64.getEncoder().encodeToString(reply.data))

            collector.cancel()
        } finally {
            gateway.closeAll(client, okHttp)
        }
    }

    @Test
    fun `audio start omits segmentId when not provided`() = runBlocking {
        // segmentId 为可选字段（protocol.md §3.2）：null 时不得出现在 audio_start payload 中
        val gateway = FakeGateway()
        gateway.start()
        gateway.server.enqueue(gateway.upgrade())
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        try {
            client.connect()
            client.sendAudioStart("srv-sess-1")
            assertTrue(awaitTrue { gateway.frames.any { it.type == "audio_start" } }, "应收到 audio_start")
            val startPayload = gateway.frames.first { it.type == "audio_start" }.payload
            assertFalse(startPayload.has("segmentId"), "segmentId 为 null 时不得发送该字段")
        } finally {
            gateway.closeAll(client, okHttp)
        }
    }

    /** parseReply 纯函数单测用的客户端实例（url 不参与解析，不会发起连接）。 */
    private fun parseClient(): GatewayClient = GatewayClient("ws://localhost:1/", OkHttpClient(), gson)

    @Test
    fun `parseReply text kind`() {
        val client = parseClient()
        val payload = gson.fromJson(
            """{"kind":"text","text":"已为您把空调调到24度","speakText":"已为您把空调调到24度"}""",
            JsonObject::class.java,
        )
        assertEquals(TextReply("已为您把空调调到24度"), client.parseReply(payload))
    }

    @Test
    fun `parseReply audio kind from fixture`() {
        val client = parseClient()
        val payload = gson.fromJson(fixture("gateway-reply-audio.json"), JsonObject::class.java)
            .getAsJsonObject("payload")
        val reply = client.parseReply(payload)
        assertNotNull(reply)
        assertTrue(reply is AudioReply)
        reply as AudioReply
        assertEquals("audio/wav", reply.mime)
        assertEquals("已为您把空调调到24度", reply.speakText)
        assertNull(reply.intent)
        val fixtureBase64 = payload.get("dataBase64").asString
        assertArrayEquals(Base64.getDecoder().decode(fixtureBase64), reply.data)
    }

    @Test
    fun `parseReply action kind from fixture`() {
        val client = parseClient()
        val payload = gson.fromJson(fixture("gateway-reply-action.json"), JsonObject::class.java)
            .getAsJsonObject("payload")
        val reply = client.parseReply(payload)
        assertNotNull(reply)
        assertTrue(reply is ActionReply)
        reply as ActionReply
        assertEquals("已为您把空调调到24度", reply.speakText)
        assertEquals("climate", reply.intent.domain)
        assertEquals("set_temperature", reply.intent.intent)
        assertEquals(0.95, reply.intent.confidence, 1e-9)
        assertEquals(SlotValue.Number(24.0), reply.intent.slots["temperature"])
        assertEquals(SlotValue.EnumValue("driver"), reply.intent.slots["zone"])
        assertEquals("nlu.iflytek.api", reply.intent.source)
    }

    @Test
    fun `parseReply invalid payload returns null`() {
        val client = parseClient()
        assertNull(client.parseReply(JsonObject()), "无 kind 应返回 null")
        assertNull(client.parseReply(gson.fromJson("""{"kind":"video","text":"x"}""", JsonObject::class.java)), "未知 kind")
        assertNull(client.parseReply(gson.fromJson("""{"kind":"audio"}""", JsonObject::class.java)), "audio 缺 mime/dataBase64")
        assertNull(
            client.parseReply(gson.fromJson("""{"kind":"audio","mime":"audio/wav","dataBase64":"!!!"}""", JsonObject::class.java)),
            "base64 非法应返回 null",
        )
        assertNull(client.parseReply(gson.fromJson("""{"kind":"text"}""", JsonObject::class.java)), "text 缺 text")
        assertNull(client.parseReply(gson.fromJson("""{"kind":"action","speakText":"x"}""", JsonObject::class.java)), "action 缺 intent")
        assertNull(
            client.parseReply(gson.fromJson("""{"kind":"action","intent":{"domain":"climate"},"speakText":"x"}""", JsonObject::class.java)),
            "intent 字段不全应返回 null",
        )
    }

    @Test
    fun `connect retries then throws GatewayException when server unreachable`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val port = server.port
        server.shutdown() // 端口立即不可达
        val received = mutableListOf<GatewayMessage>()
        val okHttp = OkHttpClient()
        val client = GatewayClient(
            url = "ws://localhost:$port/",
            okHttp = okHttp,
            gson = gson,
            connectTimeoutMs = 300,
            backoffBaseMs = 10,
            maxRetries = 2,
        )
        val collector = launch { client.messages.collect { received.add(it) } }
        try {
            try {
                client.connect()
                fail("服务器不可达时应抛 GatewayException")
            } catch (e: GatewayException) {
                assertTrue(e.message!!.contains("failed"), e.message)
            }
            // 传输失败合成 error 事件进流（供 Task 20 cloud_unreachable 判定）
            assertTrue(
                received.any { it.type == "error" && it.payload.get("code").asString == "CONNECTION_FAILED" },
                "应收到 CONNECTION_FAILED error 事件，实际: ${received.map { it.type }}",
            )
            collector.cancel()
        } finally {
            client.disconnect()
            okHttp.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `connect times out when server never sends ready`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    // 收到 hello 但故意不回 ready：客户端应在 connectTimeoutMs 后失败并重试
                }
            }),
        )
        val okHttp = OkHttpClient()
        val client = GatewayClient(
            url = "ws://localhost:${server.port}/",
            okHttp = okHttp,
            connectTimeoutMs = 200,
            backoffBaseMs = 10,
            maxRetries = 1,
        )
        try {
            try {
                client.connect()
                fail("服务端不回 ready 时应抛 GatewayException")
            } catch (e: GatewayException) {
                assertTrue(e.message!!.contains("timeout"), e.message)
            }
        } finally {
            client.disconnect()
            server.shutdown()
            okHttp.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `disconnect is idempotent and reconnect succeeds`() = runBlocking {
        val gateway = FakeGateway()
        gateway.start()
        repeat(2) {
            gateway.server.enqueue(gateway.upgrade())
        }
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        try {
            client.connect()
            client.disconnect()
            client.disconnect() // 幂等：第二次 no-op
            client.connect()    // 重新连接成功（消费第二条 upgrade 响应）
        } finally {
            gateway.closeAll(client, okHttp)
        }
    }
}

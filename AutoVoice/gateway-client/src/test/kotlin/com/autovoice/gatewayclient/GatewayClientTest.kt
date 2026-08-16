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
import okio.ByteString.Companion.toByteString
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

    private fun readyFrame(sessionId: String, serverTime: Long? = null): String {
        val st = serverTime?.let { ""","serverTime":$it""" } ?: ""
        return """{"type":"ready","payload":{"sessionId":"$sessionId","language":"zh-CN","protocolVersion":"1.1"$st}}"""
    }

    private fun decisionFrame() =
        """{"type":"decision","payload":{"arbiter":"cloud","route":"llm","reason":"llm_reply","utteranceId":"u-1","timestampMs":1723104000000}}"""

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

        /** 时钟同步测试：非 null 时 ready 帧携带该 serverTime（服务器墙钟毫秒）。 */
        var readyServerTime: Long? = null

        fun upgrade(): MockResponse =
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = parse(text)
                    frames.add(msg)
                    when (msg.type) {
                        "hello" -> webSocket.send(readyFrame("srv-sess-1", readyServerTime))
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
            // 先订阅、后 connect：ready 实时入列（若订阅晚于 connect，replay=1 槽可能被
            // 更早到达的 decision 覆盖、ready 丢失——连接/回执快的机器上偶发）
            val received = mutableListOf<GatewayMessage>()
            val collector = launch { client.messages.collect { received.add(it) } }
            client.connect()

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
            assertEquals("1.1", received[0].payload.get("protocolVersion").asString)

            // decision 事件原样透传
            assertEquals("cloud", received[1].payload.get("arbiter").asString)
            assertEquals("llm_reply", received[1].payload.get("reason").asString)

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
            assertNull(helloPayload.get("deviceId"), "未配置凭据时 hello 不带 deviceId（M5 兼容）")
            assertNull(helloPayload.get("authToken"), "未配置凭据时 hello 不带 authToken（M5 兼容）")
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
    fun `s2s binary reply frames preserve ordering and bytes`() = runBlocking {
        val gateway = FakeGateway()
        gateway.start()
        val replyPcm = byteArrayOf(7, 8, 9, 10)
        gateway.onAudioEnd = { ws, _ ->
            ws.send(fixture("gateway-audio-reply-start.json"))
            ws.send(replyPcm.toByteString())
            ws.send(fixture("gateway-audio-reply-end.json"))
        }
        gateway.server.enqueue(gateway.upgrade())
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        try {
            val received = mutableListOf<GatewayMessage>()
            val collector = launch { client.messages.collect { received.add(it) } }
            client.connect()
            client.sendAudioStart("srv-sess-1", "seg-1")
            client.sendAudioChunk(pcm)
            client.sendAudioEnd("srv-sess-1")

            assertTrue(awaitTrue {
                received.map { it.type } == listOf(
                    "ready", "audio_reply_start", "audio_reply_chunk", "audio_reply_end",
                )
            })
            assertEquals(24_000, received[1].payload.get("sampleRate").asInt)
            assertArrayEquals(replyPcm, received[2].binary)
            assertEquals("seg-1", received[3].payload.get("segmentId").asString)
            collector.cancel()
        } finally {
            gateway.closeAll(client, okHttp)
        }
    }

    // ---------- 时钟同步：ready serverTime → 时钟偏移 ----------

    @Test
    fun `clock offset computed from ready serverTime`() = runBlocking {
        val gateway = FakeGateway()
        gateway.start()
        // 服务器墙钟比设备慢 500ms；本地 MockWebServer RTT < 50ms
        gateway.readyServerTime = System.currentTimeMillis() - 500
        gateway.server.enqueue(gateway.upgrade())
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        try {
            client.connect()
            val offset = client.clockOffsetMs()
            // offset = serverTime + RTT/2 - t1 ≈ -500 + RTT/2
            assertTrue(offset < 0, "设备钟快于服务器钟 → offset 应为负，实际: $offset")
            assertTrue(offset in -600..-450, "offset 应 ≈ -500 ± RTT/2，实际: $offset")
        } finally {
            gateway.closeAll(client, okHttp)
        }
    }

    @Test
    fun `clock offset stays zero without serverTime`() = runBlocking {
        val gateway = FakeGateway()
        gateway.start()
        gateway.server.enqueue(gateway.upgrade())
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        try {
            client.connect()
            assertEquals(0L, client.clockOffsetMs(), "旧服务端无 serverTime → offset 恒 0")
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
            assertFalse(startPayload.has("utteranceId"), "utteranceId 为 null 时不得发送该字段（T6 兼容）")
        } finally {
            gateway.closeAll(client, okHttp)
        }
    }

    @Test
    fun `audio start carries utteranceId when provided`() = runBlocking {
        // T6：utteranceId 为可选字段——提供时须出现在 audio_start payload 中（服务端优先采纳端侧值）
        val gateway = FakeGateway()
        gateway.start()
        gateway.server.enqueue(gateway.upgrade())
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        try {
            client.connect()
            client.sendAudioStart("srv-sess-1", "seg-1", "utt-1")
            assertTrue(awaitTrue { gateway.frames.any { it.type == "audio_start" } }, "应收到 audio_start")
            val startPayload = gateway.frames.first { it.type == "audio_start" }.payload
            assertEquals("utt-1", startPayload.get("utteranceId").asString, "audio_start 应携带 utteranceId")
        } finally {
            gateway.closeAll(client, okHttp)
        }
    }

    @Test
    fun `tts request carries utteranceId when provided`() = runBlocking {
        // T6：tts_request 同样可选携带 utteranceId（跨阶段按话语汇合）
        val gateway = FakeGateway()
        gateway.start()
        gateway.server.enqueue(gateway.upgrade())
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        try {
            client.connect()
            client.sendTtsRequest("好的，空调已打开", "tts-1", "utt-1")
            assertTrue(awaitTrue { gateway.frames.any { it.type == "tts_request" } }, "应收到 tts_request")
            val payload = gateway.frames.first { it.type == "tts_request" }.payload
            assertEquals("utt-1", payload.get("utteranceId").asString, "tts_request 应携带 utteranceId")
        } finally {
            gateway.closeAll(client, okHttp)
        }
    }

    /** parseReply 纯函数单测用的客户端实例（url 不参与解析，不会发起连接）。 */
    private fun parseClient(): GatewayClient = GatewayClient("ws://localhost:1/", OkHttpClient(), gson)

    @Test
    fun `sendTtsRequest sends tts_request frame with text and segmentId`() = runBlocking {
        val gateway = FakeGateway()
        gateway.start()
        gateway.server.enqueue(gateway.upgrade())
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        try {
            client.connect()
            client.sendTtsRequest("好的，空调已打开", "tts-1")
            client.sendTtsRequest("再播一条") // segmentId 为 null：不得出现在 payload 中

            assertTrue(awaitTrue { gateway.frames.filter { it.type == "tts_request" }.size == 2 }, "应收到两条 tts_request")
            val withSegment = gateway.frames.first { it.type == "tts_request" }.payload
            assertEquals("好的，空调已打开", withSegment.get("text").asString)
            assertEquals("tts-1", withSegment.get("segmentId").asString)
            val withoutSegment = gateway.frames.last { it.type == "tts_request" }.payload
            assertEquals("再播一条", withoutSegment.get("text").asString)
            assertFalse(withoutSegment.has("segmentId"), "segmentId 为 null 时不得发送该字段")
        } finally {
            gateway.closeAll(client, okHttp)
        }
    }

    @Test
    fun `parseTtsResponse decodes fixture`() {
        val client = parseClient()
        val payload = gson.fromJson(fixture("gateway-tts-response.json"), JsonObject::class.java)
            .getAsJsonObject("payload")
        val reply = client.parseTtsResponse(payload)
        assertNotNull(reply)
        reply as AudioReply
        assertEquals("audio/wav", reply.mime)
        assertEquals("好的，空调已打开", reply.speakText, "tts_response.text 应映射到 speakText")
        val fixtureBase64 = payload.get("dataBase64").asString
        assertArrayEquals(Base64.getDecoder().decode(fixtureBase64), reply.data)
    }

    @Test
    fun `parseTtsResponse invalid payload returns null`() {
        val client = parseClient()
        assertNull(client.parseTtsResponse(JsonObject()), "无 mime/dataBase64 应返回 null")
        assertNull(
            client.parseTtsResponse(gson.fromJson("""{"mime":"audio/wav"}""", JsonObject::class.java)),
            "缺 dataBase64 应返回 null",
        )
        assertNull(
            client.parseTtsResponse(gson.fromJson("""{"mime":"audio/wav","dataBase64":"!!!"}""", JsonObject::class.java)),
            "base64 非法应返回 null",
        )
    }

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
    fun `parseAudioStreamEnd carries user transcript for recognition box`() {
        val client = parseClient()
        val payload = gson.fromJson(
            """{"segmentId":"seg-1","speakText":"Sure","asrText":"Could you open the window?"}""",
            JsonObject::class.java,
        )
        val end = client.parseAudioStreamEnd(payload)
        assertEquals("Sure", end.speakText)
        assertEquals("Could you open the window?", end.asrText)
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

    /** B5：gateway-pending.json fixture（协议 §4.8）→ 信封 type=pending + payload.segmentId/text。 */
    @Test
    fun `pending fixture parses to pending gateway message`() {
        val root = gson.fromJson(fixture("gateway-pending.json"), JsonObject::class.java)
        val type = root.get("type")?.takeIf { it.isJsonPrimitive }?.asString
        assertEquals("pending", type, "fixture 应声明 type=pending（独立占位消息，非 reply）")
        val payload = root.getAsJsonObject("payload")
        assertEquals("seg-1", payload.get("segmentId").asString, "pending 应携带 segmentId 供端侧对账")
        assertTrue(payload.get("text").asString.isNotBlank(), "pending 应携带展示文案")
        // 端侧桥的 pending 分支消费形态：GatewayMessage(type=pending, payload) 原样透传
        // （listener 的 parseFrame 是通用信封解析，任何 type 都原样产出，此处按同构断言）
        val msg = GatewayMessage("pending", payload)
        assertEquals("pending", msg.type)
        assertEquals("seg-1", msg.payload.get("segmentId").asString)
        assertEquals("正在处理，请稍候", msg.payload.get("text").asString)
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
    fun `hello includes device credentials when configured`() = runBlocking {
        val gateway = FakeGateway()
        gateway.start()
        gateway.server.enqueue(gateway.upgrade())
        val okHttp = OkHttpClient()
        val client = GatewayClient(
            url = "ws://localhost:${gateway.server.port}/",
            okHttp = okHttp,
            gson = gson,
            deviceId = "demo-1",
            authToken = "secret-token-1",
        )
        try {
            client.connect()

            val helloPayload = gateway.frames[0].payload
            assertEquals("autovoice-android", helloPayload.get("client").asString)
            assertEquals("1.1", helloPayload.get("protocolVersion").asString)
            assertEquals("demo-1", helloPayload.get("deviceId").asString, "配置了 deviceId 应注入 hello")
            assertEquals("secret-token-1", helloPayload.get("authToken").asString, "配置了 authToken 应注入 hello")
            assertNull(helloPayload.get("sessionId"), "客户端不预生成 sessionId（服务端权威）")
        } finally {
            gateway.closeAll(client, okHttp)
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

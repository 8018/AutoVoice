package com.autovoice.app.telemetry

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * TelemetryClient JVM 单测（T6）：MockWebServer 假扮数据平台，断言
 * POST /api/telemetry/round 的 body（utteranceId/sessionId/deviceId/source/events）、
 * POST /api/telemetry/audio 的 multipart 表单；失败静默（500 不抛）、
 * utteranceId 对不上不收包、enabled=false 全 no-op。
 *
 * 注：AGP mockable android.jar 的 org.json 是桩（put 返回 null），拼 JSON 依赖
 * testImplementation 引入的真实 org.json（见 app/build.gradle.kts）。
 */
class TelemetryClientTest {

    private lateinit var server: MockWebServer
    private lateinit var okHttp: OkHttpClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun client(enabled: Boolean = true, clock: () -> Long = System::currentTimeMillis): TelemetryClient =
        TelemetryClient(
            okHttp = okHttp,
            baseUrl = "http://localhost:${server.port}",
            deviceId = "demo-1",
            scope = scope,
            enabled = enabled,
            clock = clock,
        )

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        okHttp = OkHttpClient()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        okHttp.dispatcher.executorService.shutdown()
    }

    @Test
    fun `end posts round with collected events`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = client()
        client.onSessionId("srv-sess-1")
        client.begin("utt-1")
        client.record(TelemetryStages.VAD, "info", mapOf("bytes" to 1600, "durationMs" to 50))
        client.record(TelemetryStages.DEVICE_ARBITER, "info", mapOf("route" to "local", "reason" to "cloud_won"))
        client.end("utt-1")

        val req = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(req, "end 应 POST /api/telemetry/round")
        assertEquals("POST", req!!.method)
        assertEquals("/api/telemetry/round", req.path)

        val body = JSONObject(req.body.readUtf8())
        assertEquals("utt-1", body.getString("utteranceId"))
        assertEquals("srv-sess-1", body.getString("sessionId"))
        assertEquals("demo-1", body.getString("deviceId"))
        assertEquals("button", body.getString("source"))
        assertTrue(body.getLong("startMs") > 0, "startMs 应为 begin 时刻")
        assertTrue(body.getLong("endMs") >= body.getLong("startMs"), "endMs 不得早于 startMs")

        val events = body.getJSONArray("events")
        assertEquals(2, events.length(), "begin 后 record 的事件应按序入包")
        val vad = events.getJSONObject(0)
        assertEquals(TelemetryStages.VAD, vad.getString("stage"))
        assertEquals("info", vad.getString("level"))
        assertEquals(1600, vad.getJSONObject("payload").getInt("bytes"))
        val arbiter = events.getJSONObject(1)
        assertEquals(TelemetryStages.DEVICE_ARBITER, arbiter.getString("stage"))
        assertEquals("cloud_won", arbiter.getJSONObject("payload").getString("reason"))
    }

    @Test
    fun `clock injection stamps server-synced timestamps`() {
        server.enqueue(MockResponse().setResponseCode(200))
        var now = 1_000_000L
        val client = client(clock = { now })
        client.onSessionId("srv-sess-1")
        client.begin("utt-1")
        now = 1_000_100L
        client.record(TelemetryStages.VAD, "info", mapOf("bytes" to 1600))
        now = 1_000_200L
        client.end("utt-1")

        val req = server.takeRequest(5, TimeUnit.SECONDS)
        val body = JSONObject(req!!.body.readUtf8())
        assertEquals(1_000_000L, body.getLong("startMs"), "startMs 应取 clock 值（时钟同步换算后）")
        assertEquals(1_000_200L, body.getLong("endMs"), "endMs 应取 clock 值")
        val events = body.getJSONArray("events")
        assertEquals(1, events.length())
        assertEquals(1_000_100L, events.getJSONObject(0).getLong("tsMs"), "事件 tsMs 应取 clock 值")
    }

    @Test
    fun `events recorded before begin are dropped`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = client()
        client.record(TelemetryStages.VAD, "info", mapOf("bytes" to 1))
        client.begin("utt-1")
        client.end("utt-1")

        val req = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(req)
        val events = JSONObject(req!!.body.readUtf8()).getJSONArray("events")
        assertEquals(0, events.length(), "begin 之前的事件不得进入本轮")
    }

    /**
     * T7 评审 C1：recordFor 在轮未关闭且 utteranceId 匹配时并入 round events
     * （随 end() 一并 POST，tts_play 落入本轮）。
     */
    @Test
    fun `recordFor merges into open round when utteranceId matches`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = client()
        client.begin("utt-1")
        client.recordFor("utt-1", TelemetryStages.TTS_PLAY_END, "info", mapOf("source" to "network"))
        client.end("utt-1")

        val req = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(req, "end 应 POST /api/telemetry/round")
        assertEquals("/api/telemetry/round", req!!.path)
        val events = JSONObject(req.body.readUtf8()).getJSONArray("events")
        assertEquals(1, events.length(), "匹配轮的 recordFor 事件应进 round 包")
        val e = events.getJSONObject(0)
        assertEquals(TelemetryStages.TTS_PLAY_END, e.getString("stage"))
        assertEquals("info", e.getString("level"))
        assertEquals("network", e.getJSONObject("payload").getString("source"))
        assertTrue(e.getLong("tsMs") > 0)
    }

    @Test
    fun `barge-in keeps old and new telemetry rounds isolated`() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        val client = client()
        client.begin("utt-old")
        client.recordFor("utt-old", "old-event", "info", emptyMap())
        client.begin("utt-new")
        client.record("new-event", "info", emptyMap())
        client.recordFor("utt-old", "old-late", "warn", emptyMap())
        client.end("utt-old")
        client.end("utt-new")

        val bodies = List(2) {
            JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        }.associateBy { it.getString("utteranceId") }
        assertEquals(
            listOf("old-event", "old-late"),
            (0 until bodies.getValue("utt-old").getJSONArray("events").length()).map {
                bodies.getValue("utt-old").getJSONArray("events").getJSONObject(it).getString("stage")
            },
        )
        assertEquals(
            "new-event",
            bodies.getValue("utt-new").getJSONArray("events").getJSONObject(0).getString("stage"),
        )
    }

    /**
     * T7 评审 C1：轮已关闭（current=null）后的迟到事件 → 直接 POST 单事件到
     * /api/telemetry/events（服务端按 utterance_id 汇合到已有 round，不新建轮）。
     */
    @Test
    fun `recordFor posts single event to events endpoint when round closed`() {
        server.enqueue(MockResponse().setResponseCode(200)) // round POST
        server.enqueue(MockResponse().setResponseCode(200)) // /events POST
        val client = client()
        client.begin("utt-1")
        client.end("utt-1")
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS), "round 应先收包")

        client.recordFor("utt-1", TelemetryStages.TTS_PLAY_END, "error", mapOf("source" to "system", "result" to "failed"))
        val req = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(req, "轮关闭后的迟到事件应 POST /api/telemetry/events")
        assertEquals("/api/telemetry/events", req!!.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("utt-1", body.getString("utteranceId"))
        val events = body.getJSONArray("events")
        assertEquals(1, events.length())
        val e = events.getJSONObject(0)
        assertEquals(TelemetryStages.TTS_PLAY_END, e.getString("stage"))
        assertEquals("error", e.getString("level"))
        assertEquals("failed", e.getJSONObject("payload").getString("result"))
        assertTrue(e.getLong("tsMs") > 0)
    }

    /**
     * T7 评审 C1：current 轮存在但 utteranceId 不匹配（跨轮迟到事件）→ 不并入当前轮，
     * 直传 /api/telemetry/events 并按事件自己的 utteranceId 归属。
     */
    @Test
    fun `recordFor with mismatched utteranceId goes to events endpoint not round`() {
        server.enqueue(MockResponse().setResponseCode(200)) // /events POST
        server.enqueue(MockResponse().setResponseCode(200)) // round POST
        val client = client()
        client.begin("utt-1")
        client.recordFor("utt-2", TelemetryStages.TTS_PLAY_END, "info", mapOf("source" to "network"))
        client.end("utt-1")

        // 两个请求先后到达（顺序不依赖：HTTP 异步），按 path 区分
        val req1 = server.takeRequest(5, TimeUnit.SECONDS)
        val req2 = server.takeRequest(5, TimeUnit.SECONDS)
        val requests = listOf(req1, req2)
        assertTrue(requests.all { it != null }, "应发出 round POST 与 /events POST 各一次")
        val roundReq = requests.first { it!!.path == "/api/telemetry/round" }!!
        val eventsReq = requests.first { it!!.path == "/api/telemetry/events" }!!
        val roundEvents = JSONObject(roundReq.body.readUtf8()).getJSONArray("events")
        assertEquals(0, roundEvents.length(), "不匹配的 recordFor 不得进当前轮")
        assertEquals("utt-2", JSONObject(eventsReq.body.readUtf8()).getString("utteranceId"))
    }

    @Test
    fun `end with mismatched utteranceId does not post`() {
        val client = client()
        client.begin("utt-1")
        client.end("utt-2") // 与 begin 的 utteranceId 不一致：不收包

        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS), "utteranceId 不匹配不得 POST")
    }

    @Test
    fun `failed round upload is silent`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val client = client()
        client.begin("utt-1")
        client.record(TelemetryStages.VAD, "info", mapOf("bytes" to 1600))
        client.end("utt-1")

        // 500 响应照常发出请求；失败仅 Log.w，绝不抛（这里能执行到断言即证明静默）
        val req = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(req, "服务端 500 时仍应发出请求且不抛异常")
        assertEquals("/api/telemetry/round", req!!.path)
    }

    @Test
    fun `uploadAudio posts multipart with utteranceId and pcm`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = client()
        client.uploadAudio("utt-1", ByteArray(8) { it.toByte() })

        val req = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(req, "uploadAudio 应 POST /api/telemetry/audio")
        assertEquals("POST", req!!.method)
        assertEquals("/api/telemetry/audio", req.path)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"), "应为 multipart 表单")
        val bodyText = req.body.readUtf8()
        assertTrue(bodyText.contains("name=\"utteranceId\""), "multipart 应含 utteranceId 表单字段")
        assertTrue(bodyText.contains("utt-1"), "表单应携带 utteranceId 值")
        assertTrue(bodyText.contains("filename=\"utt-1.pcm\""), "音频文件名应为 <utteranceId>.pcm")
        assertTrue(bodyText.contains("application/octet-stream"), "PCM 内容类型应为 application/octet-stream")
    }

    @Test
    fun `disabled client is no-op`() {
        val client = client(enabled = false)
        client.onSessionId("srv-sess-1")
        client.begin("utt-1")
        client.record(TelemetryStages.VAD, "info", mapOf("bytes" to 1600))
        client.recordFor("utt-1", TelemetryStages.TTS_PLAY_END, "info", mapOf("source" to "network"))
        client.end("utt-1")
        client.uploadAudio("utt-1", ByteArray(4))

        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS), "enabled=false 时不得发出任何请求")
    }
}

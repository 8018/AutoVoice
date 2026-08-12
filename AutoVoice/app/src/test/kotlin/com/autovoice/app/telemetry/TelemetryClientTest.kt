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
 * 注：app 单测由 AGP mockable android.jar 提供真实 org.json 实现，JSONObject 可直接用。
 */
class TelemetryClientTest {

    private lateinit var server: MockWebServer
    private lateinit var okHttp: OkHttpClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun client(enabled: Boolean = true): TelemetryClient =
        TelemetryClient(
            okHttp = okHttp,
            baseUrl = "http://localhost:${server.port}",
            deviceId = "demo-1",
            scope = scope,
            enabled = enabled,
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
        client.end("utt-1")
        client.uploadAudio("utt-1", ByteArray(4))

        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS), "enabled=false 时不得发出任何请求")
    }
}

package com.autovoice.app

import com.autovoice.gatewayclient.GatewayClient
import com.autovoice.gatewayclient.GatewayException
import com.autovoice.voicecore.TextReply
import com.autovoice.voicecore.arbiter.DecisionSink
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * 网关事件桥对账测试（fix round 1）：真实 GatewayClient + 真实 GatewayBridge，
 * MockWebServer 假扮网关。验证 reply / error 按 payload 的 segmentId 与当前话语的等待槽
 * 关联（protocol.md §3.2）：
 *  - 匹配的 reply 完成当前槽；
 *  - 上一轮迟到的 reply（旧 segmentId）不得完成当前槽；
 *  - 他轮的 error 不得让当前槽异常失败（槽不被污染，随后匹配 reply 仍正常完成）；
 *  - 匹配的 error 让当前槽立即失败；
 *  - 未携带 segmentId 的 error（客户端合成的传输错误）仍按当前话语快速失败。
 */
class GatewayBridgeTest {

    private val gson = Gson()

    /** 假扮网关的 MockWebServer：接 hello 回 ready；测试可经 [sendText] 主动推送下行帧。 */
    private class FakeGatewayServer {
        val server = MockWebServer()
        val closed = CountDownLatch(1)
        val serverLog = java.util.concurrent.ConcurrentLinkedQueue<String>()

        @Volatile
        var ws: WebSocket? = null

        fun upgrade(): MockResponse =
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    ws = webSocket
                    serverLog.add("onOpen")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    serverLog.add("onMessage: $text")
                    val root = Gson().fromJson(text, JsonObject::class.java)
                    if (root.get("type")?.asString == "hello") {
                        webSocket.send(
                            """{"type":"ready","payload":{"sessionId":"srv-sess-1","language":"zh-CN","protocolVersion":"1.0"}}""",
                        )
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    serverLog.add("onFailure: ${t.message}")
                }

                // MockWebServer 服务端不会自动回 close 帧：onClosing 回一帧，close 握手才能完成
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    serverLog.add("onClosing: $code $reason")
                    webSocket.close(1000, "close reply")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    serverLog.add("onClosed: $code $reason")
                    closed.countDown()
                }
            })

        /** 启动并预置一次 WS upgrade 响应（与 GatewayClientTest.FakeGateway 一致，勿漏 enqueue）。 */
        fun start() {
            server.enqueue(upgrade())
            server.start()
        }

        fun sendText(text: String) {
            assertTrue(ws?.send(text) == true, "下行帧应发送成功")
        }

        fun closeAll(client: GatewayClient, okHttp: OkHttpClient) {
            client.disconnect()
            assertTrue(
                closed.await(3, TimeUnit.SECONDS),
                "等待服务端收到 close 握手超时；serverLog=${serverLog.toList()}",
            )
            server.shutdown()
            okHttp.dispatcher.executorService.shutdown()
        }
    }

    private fun replyFrame(segmentId: String, text: String): String =
        """{"type":"reply","payload":{"kind":"text","text":"$text","speakText":"$text","segmentId":"$segmentId"}}"""

    private fun errorFrame(code: String, message: String, segmentId: String? = null): String {
        val seg = if (segmentId != null) ""","segmentId":"$segmentId"""" else ""
        return """{"type":"error","payload":{"code":"$code","message":"$message"$seg}}"""
    }

    /** B5：pending 占位帧（协议 §4.8，LLM 处理中——独立消息，不是 reply）。 */
    private fun pendingFrame(segmentId: String): String =
        """{"type":"pending","payload":{"segmentId":"$segmentId","text":"正在处理，请稍候"}}"""

    // ------------------------------------------------------------------ 用例

    @Test
    fun `matching reply completes the slot`() = runBlocking {
        val gateway = FakeGatewayServer()
        gateway.start()
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        // 桥收集器是无限循环协程：挂在独立 scope 上，finally 里 cancel，避免 runBlocking 等子协程永不返回
        val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = GatewayBridge(client, DecisionSink {}, bridgeScope)
        try {
            client.connect()
            val slot = bridge.newReplySlot("seg-1")
            gateway.sendText(replyFrame("seg-1", "匹配回复"))
            val reply = slot.await()
            assertTrue(reply is TextReply, "匹配的 reply 应完成当前槽")
            assertEquals("匹配回复", (reply as TextReply).text)
        } finally {
            bridgeScope.cancel()
            gateway.closeAll(client, okHttp)
        }
    }

    @Test
    fun `stale reply from previous utterance does not complete current slot`() = runBlocking {
        val gateway = FakeGatewayServer()
        gateway.start()
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        // 桥收集器是无限循环协程：挂在独立 scope 上，finally 里 cancel，避免 runBlocking 等子协程永不返回
        val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = GatewayBridge(client, DecisionSink {}, bridgeScope)
        try {
            client.connect()
            val slot = bridge.newReplySlot("seg-new")
            // 上一轮话语迟到的 reply（旧 segmentId）先到达
            gateway.sendText(replyFrame("seg-old", "旧回复"))
            delay(150)
            assertFalse(slot.isCompleted, "上一轮迟到的 reply 不得完成当前槽")
            // 本轮的 reply 到达 → 正常完成
            gateway.sendText(replyFrame("seg-new", "本轮回复"))
            val reply = slot.await()
            assertTrue(reply is TextReply)
            assertEquals("本轮回复", (reply as TextReply).text)
        } finally {
            bridgeScope.cancel()
            gateway.closeAll(client, okHttp)
        }
    }

    @Test
    fun `mismatched error does not exceptionally complete current slot`() = runBlocking {
        val gateway = FakeGatewayServer()
        gateway.start()
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        // 桥收集器是无限循环协程：挂在独立 scope 上，finally 里 cancel，避免 runBlocking 等子协程永不返回
        val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = GatewayBridge(client, DecisionSink {}, bridgeScope)
        try {
            client.connect()
            val slot = bridge.newReplySlot("seg-new")
            // 上一轮话语的 error（旧 segmentId）先到达
            gateway.sendText(errorFrame("ASR_FAILED", "上轮失败", "seg-old"))
            delay(150)
            assertFalse(slot.isCompleted, "他轮的 error 不得让当前槽异常失败")
            // 槽未被污染：匹配的 reply 随后仍能正常完成
            gateway.sendText(replyFrame("seg-new", "本轮回复"))
            val reply = slot.await()
            assertTrue(reply is TextReply)
            assertEquals("本轮回复", (reply as TextReply).text)
        } finally {
            bridgeScope.cancel()
            gateway.closeAll(client, okHttp)
        }
    }

    @Test
    fun `matching error fails the slot exceptionally`() = runBlocking {
        val gateway = FakeGatewayServer()
        gateway.start()
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        // 桥收集器是无限循环协程：挂在独立 scope 上，finally 里 cancel，避免 runBlocking 等子协程永不返回
        val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = GatewayBridge(client, DecisionSink {}, bridgeScope)
        try {
            client.connect()
            val slot = bridge.newReplySlot("seg-new")
            gateway.sendText(errorFrame("ASR_FAILED", "本轮失败", "seg-new"))
            val thrown = try {
                slot.await()
                fail("匹配的 error 应让当前槽异常失败")
            } catch (e: GatewayException) {
                e
            }
            assertTrue(thrown.message!!.contains("ASR_FAILED"), thrown.message)
        } finally {
            bridgeScope.cancel()
            gateway.closeAll(client, okHttp)
        }
    }

    @Test
    fun `error without segmentId still fails the slot fast`() = runBlocking {
        // 客户端合成的传输错误（CONNECTION_FAILED 等）不携带 segmentId：无从对账，按当前话语快速失败
        val gateway = FakeGatewayServer()
        gateway.start()
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        // 桥收集器是无限循环协程：挂在独立 scope 上，finally 里 cancel，避免 runBlocking 等子协程永不返回
        val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = GatewayBridge(client, DecisionSink {}, bridgeScope)
        try {
            client.connect()
            val slot = bridge.newReplySlot("seg-new")
            gateway.sendText(errorFrame("CONNECTION_FAILED", "websocket failure"))
            val thrown = try {
                slot.await()
                fail("传输错误应让当前槽快速失败")
            } catch (e: GatewayException) {
                e
            }
            assertTrue(thrown.message!!.contains("CONNECTION_FAILED"), thrown.message)
        } finally {
            bridgeScope.cancel()
            gateway.closeAll(client, okHttp)
        }
    }

    // ------------------------------------------------ B5：pending 占位帧（LLM 处理中）

    @Test
    fun `matching pending signals the arbiter channel and UI callback`() = runBlocking {
        // pending 帧对账通过 → 仲裁器扩展窗口信号 + UI"处理中…"回调（不碰 reply 槽）
        val gateway = FakeGatewayServer()
        gateway.start()
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val signals = Channel<Unit>(Channel.BUFFERED)
        var uiCallback = 0
        val bridge = GatewayBridge(client, DecisionSink {}, bridgeScope, signals) { uiCallback++ }
        try {
            client.connect()
            val slot = bridge.newReplySlot("seg-1")
            gateway.sendText(pendingFrame("seg-1"))
            delay(150)
            assertTrue(signals.tryReceive().getOrNull() != null, "匹配的 pending 应发出仲裁器信号")
            assertEquals(1, uiCallback, "匹配的 pending 应触发 UI 回调")
            assertFalse(slot.isCompleted, "pending 是占位消息，不得 complete reply 槽（final 还要来）")
        } finally {
            bridgeScope.cancel()
            gateway.closeAll(client, okHttp)
        }
    }

    @Test
    fun `stale pending from previous utterance is dropped`() = runBlocking {
        // 上一轮迟到的 pending（旧 segmentId）→ 丢弃：不发信号、不触发 UI 回调、槽不受影响
        val gateway = FakeGatewayServer()
        gateway.start()
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val signals = Channel<Unit>(Channel.BUFFERED)
        var uiCallback = 0
        val bridge = GatewayBridge(client, DecisionSink {}, bridgeScope, signals) { uiCallback++ }
        try {
            client.connect()
            val slot = bridge.newReplySlot("seg-new")
            gateway.sendText(pendingFrame("seg-old"))
            delay(150)
            assertTrue(signals.tryReceive().getOrNull() == null, "旧轮的 pending 不得发信号")
            assertEquals(0, uiCallback, "旧轮的 pending 不得触发 UI 回调")
            // 槽未被污染：本轮的 reply 随后仍正常完成
            gateway.sendText(replyFrame("seg-new", "本轮回复"))
            val reply = slot.await()
            assertTrue(reply is TextReply)
            assertEquals("本轮回复", (reply as TextReply).text)
        } finally {
            bridgeScope.cancel()
            gateway.closeAll(client, okHttp)
        }
    }

    @Test
    fun `pending without reply slot is dropped`() = runBlocking {
        // 无话语在途（无槽）→ pending 丢弃：不发信号、不触发 UI 回调
        val gateway = FakeGatewayServer()
        gateway.start()
        val okHttp = OkHttpClient()
        val client = GatewayClient("ws://localhost:${gateway.server.port}/", okHttp, gson)
        val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val signals = Channel<Unit>(Channel.BUFFERED)
        var uiCallback = 0
        val bridge = GatewayBridge(client, DecisionSink {}, bridgeScope, signals) { uiCallback++ }
        try {
            client.connect()
            gateway.sendText(pendingFrame("seg-orphan"))
            delay(150)
            assertTrue(signals.tryReceive().getOrNull() == null, "无槽时 pending 不得发信号")
            assertEquals(0, uiCallback, "无槽时 pending 不得触发 UI 回调")
        } finally {
            bridgeScope.cancel()
            gateway.closeAll(client, okHttp)
        }
    }
}

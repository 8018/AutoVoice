package com.autovoice.gatewayclient

import com.autovoice.voicecore.GatewayMessage
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * OkHttp WebSocketListener → [MutableSharedFlow] 桥接（Task 15）。
 *
 * 职责：
 *  - 文本帧解析为 [GatewayMessage]（统一信封 `{"type":..., "payload":{...}}`）后 tryEmit 进事件流；
 *  - ready 帧同步完成 [connect] 等待的 [CompletableDeferred]；
 *  - 服务端 error 帧（如 BAD_HELLO）在握手阶段出现 = 握手必然失败：立即让 deferred 失败，
 *    不等 connectTimeoutMs（否则端侧把协议错误误判成超时，走降级路径）；
 *  - 传输失败（[onFailure]）/ 连接关闭（[onClosed]）时向事件流合成 error 事件
 *    （code=CONNECTION_FAILED / CONNECTION_CLOSED），并让等待 ready 的 deferred 立即失败
 *    —— 连接失败快速暴露，不必等超时。
 *
 * 每连接一个实例，由 [GatewayClient.connect] 创建；事件流实例在多次连接间共享。
 */
class GatewayListener(
    private val events: MutableSharedFlow<GatewayMessage>,
    private val gson: Gson = Gson(),
    private val ready: CompletableDeferred<GatewayMessage>,
) : WebSocketListener() {

    override fun onMessage(webSocket: WebSocket, text: String) {
        val msg = parseFrame(text)
        val emitted = msg ?: errorFrame("BAD_FRAME", "unparseable frame: $text")
        events.tryEmit(emitted)
        if (emitted.type == "ready") {
            // connect 等待方可能已超时/取消：complete 返回 false 时忽略即可
            ready.complete(emitted)
        } else if (emitted.type == "error") {
            // 握手阶段的 error（BAD_HELLO 等）= 握手已失败，快速失败不等超时
            ready.completeExceptionally(
                GatewayException("handshake rejected: ${emitted.payload["code"]} ${emitted.payload["message"]}"),
            )
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        // 协议二进制帧仅客户端 → 服务端（PCM）；服务端下行二进制帧忽略。
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        events.tryEmit(errorFrame("CONNECTION_FAILED", t.message ?: "websocket failure"))
        ready.completeExceptionally(t)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        events.tryEmit(errorFrame("CONNECTION_CLOSED", "code=$code reason=$reason"))
        ready.completeExceptionally(GatewayException("connection closed before ready: code=$code $reason"))
    }

    /** `{"type":..., "payload":{...}}` 信封解析；JSON 非法或 type 缺失 → null。 */
    private fun parseFrame(text: String): GatewayMessage? =
        try {
            val root = gson.fromJson(text, JsonObject::class.java)
            val type = root.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            val payload = root.getAsJsonObject("payload") ?: JsonObject()
            GatewayMessage(type, payload)
        } catch (e: Exception) {
            null
        }

    private fun errorFrame(code: String, message: String): GatewayMessage {
        val payload = JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        }
        return GatewayMessage("error", payload)
    }
}

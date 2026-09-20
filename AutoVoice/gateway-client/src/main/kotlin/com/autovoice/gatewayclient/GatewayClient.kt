package com.autovoice.gatewayclient

import com.autovoice.voicecore.GatewayMessage
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString

/**
 * 网关连接失败（重试耗尽 / 等待 ready 超时 / 传输错误）时抛出。
 */
open class GatewayException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class GatewayConnectionState {
    DISCONNECTED,
    CONNECTING,
    READY,
    CLOSING,
}

/**
 * AutoVoiceServer 网关 WebSocket 客户端（shared/protocol.md §5 时序）。
 *
 * 会话流程：`connect()` 建立连接并发送 hello（payload 字段照 protocol.md §3.1；
 * sessionId 不预生成——服务端权威，由 ready 回执下发），收到 ready 后返回；
 * 之后服务端文本/二进制帧进入 [messages]。该类不理解 audio、TTS、ASR、NLU 或业务
 * message type；上层协议通过两个 [send] 重载发送帧。
 *
 * 断线重连：仅在 [connect] 内重试（指数退避 backoffBaseMs 翻倍，默认 1s/2s/4s，
 * 最多 [maxRetries] 次重试），仍失败抛 [GatewayException]。`disconnect()` 幂等。
 *
 * 背压决策：事件流 [messages] 为 SharedFlow，replay=1（connect 后订阅可补到 ready）、
 * extraBufferCapacity=64、onBufferOverflow=SUSPEND。WebSocket listener 使用挂起式 emit，
 * 慢消费者会反压 socket 读取而不会静默丢失 PCM 分片。
 */
class GatewayClient(
    private val url: String,
    private val okHttp: OkHttpClient,
    private val gson: Gson = Gson(),
    private val connectTimeoutMs: Long = 5_000,
    private val backoffBaseMs: Long = 1_000,
    private val maxRetries: Int = 3,
    /** 网关鉴权凭据（M5）：非空时注入 hello 帧（服务器 auth-enabled 时必填）。 */
    private val deviceId: String? = null,
    private val authToken: String? = null,
) {
    companion object {
        /** protocol.md §3.1 hello 的客户端标识。 */
        const val CLIENT_NAME = "autovoice-android"

        /** protocol.md §3.1 hello 的协议版本（v1.1：TTS 解耦）。 */
        const val PROTOCOL_VERSION = "1.1"
    }

    private val events = MutableSharedFlow<GatewayMessage>(
        // replay=1：connect() 返回后订阅的消费者能立即补到最近一个事件（ready 在
        // 无订阅者期间被 emit，否则会被丢弃）；active 订阅者不受影响，事件照常下发。
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** 网关事件流：文本协议消息与 S2S 二进制 chunk；传输失败合成 error 事件。 */
    val messages: SharedFlow<GatewayMessage> = events.asSharedFlow()

    private val mutableConnectionState = MutableStateFlow(GatewayConnectionState.DISCONNECTED)
    val connectionState: StateFlow<GatewayConnectionState> = mutableConnectionState.asStateFlow()

    private val connectMutex = Mutex()

    @Volatile
    private var webSocket: WebSocket? = null

    /**
     * 设备端与服务器墙钟偏移（ms）：ready 携带 serverTime 时按
     * `serverTime + RTT/2 − 本地时刻` 估算（RTT ≈ hello→ready 往返，对称假设）；
     * 服务器未携带 serverTime（旧服务端）→ 恒 0（不做换算）。每次成功握手刷新。
     */
    @Volatile
    private var clockOffsetMs = 0L

    /** 首次握手由服务端生成；重连 hello 回带以恢复同一会话上下文。 */
    @Volatile
    private var serverSessionId: String? = null

    /** D02b：会话恢复凭据（ready 签发,独立于 sessionId）；重连 hello 与 sessionId 一同回带。 */
    @Volatile
    private var serverResumeToken: String? = null

    /** 当前存储的会话 ID（服务端签发）；null = 尚未握手或会话已被清除。 */
    fun currentSessionId(): String? = serverSessionId

    /** 清除存储的会话上下文（会话级错误 SESSION_EXPIRED / SESSION_RECOVER_DENIED 后调用）。 */
    fun clearSessionContext() {
        serverSessionId = null
        serverResumeToken = null
    }

    /** 当前时钟偏移（ms）：telemetry 打戳时 `本地时间 + offset` 换算为服务器时钟。 */
    fun clockOffsetMs(): Long = clockOffsetMs

    /**
     * Transport-only text send API. Protocol/business layers own message names and payload shape.
     */
    fun send(type: String, payload: Map<String, Any?> = emptyMap()) {
        require(type.isNotBlank()) { "message type must not be blank" }
        sendFrame(mapOf("type" to type, "payload" to payload))
    }

    /** Transport-only binary send API; framing semantics belong to the protocol layer. */
    fun send(bytes: ByteArray) {
        val ws = webSocket ?: throw GatewayException("not connected")
        if (!ws.send(bytes.toByteString())) throw GatewayException("send binary frame failed: websocket not open")
    }

    /**
     * 建立连接并等待 ready 后返回。
     *
     * 每次尝试：open → 发 hello → 等 ready（connectTimeoutMs 内未到即失败）。
     * 失败按指数退避重试（1s/2s/4s…），超过 [maxRetries] 次重试仍失败抛 [GatewayException]；
     * 协程取消原样传播（不吞）。
     */
    suspend fun connect() = connectMutex.withLock {
        if (mutableConnectionState.value == GatewayConnectionState.READY && webSocket != null) return
        var attempt = 0
        while (true) {
            try {
                mutableConnectionState.value = GatewayConnectionState.CONNECTING
                doConnect()
                return
            } catch (e: CancellationException) {
                mutableConnectionState.value = GatewayConnectionState.DISCONNECTED
                throw e
            } catch (e: Exception) {
                mutableConnectionState.value = GatewayConnectionState.DISCONNECTED
                if (attempt >= maxRetries) {
                    throw GatewayException(
                        "connect to $url failed after ${attempt + 1} attempts: ${e.message}",
                        e,
                    )
                }
                attempt++
                delay(backoffBaseMs * (1L shl (attempt - 1)))
            }
        }
    }

    /** 断开连接（幂等）：发送 1000 正常关闭帧；未连接时为 no-op。 */
    fun disconnect() {
        mutableConnectionState.value = GatewayConnectionState.CLOSING
        val ws = webSocket
        webSocket = null
        ws?.close(1000, "client disconnect")
        mutableConnectionState.value = GatewayConnectionState.DISCONNECTED
    }

    private suspend fun doConnect() {
        val ready = CompletableDeferred<GatewayMessage>()
        val ws = try {
            okHttp.newWebSocket(
                Request.Builder().url(url).build(),
                GatewayListener(events, gson, ready, ::markDisconnected, ::clearSessionContext),
            )
        } catch (e: Exception) {
            throw GatewayException("cannot open websocket to $url: ${e.message}", e)
        }
        webSocket = ws
        try {
            // open 后立即发 hello（OkHttp 排队到握手完成后发出）；ready 回执由 listener 完成
            val t0 = System.currentTimeMillis()
            ws.send(helloFrame())
            val readyMsg = withTimeoutOrNull(connectTimeoutMs) { ready.await() }
                ?: throw GatewayException("timeout waiting for ready within ${connectTimeoutMs}ms")
            // 时钟同步：ready 带 serverTime（服务器墙钟毫秒）→ 估算时钟偏移；
            // RTT ≈ hello→ready 往返（服务器处理微秒级可忽略），对称假设误差 ±RTT/2
            val t1 = System.currentTimeMillis()
            readyMsg.payload["serverTime"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.let { clockOffsetMs = it.asLong + (t1 - t0) / 2 - t1 }
            readyMsg.payload["sessionId"]?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() }?.let { serverSessionId = it }
            readyMsg.payload["resumeToken"]?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() }?.let { serverResumeToken = it }
            if (webSocket !== ws) {
                throw GatewayException("websocket disconnected before ready completed")
            }
            mutableConnectionState.value = GatewayConnectionState.READY
        } catch (e: Exception) {
            if (webSocket === ws) webSocket = null
            ws.cancel()
            throw e
        }
    }

    /** 只允许当前连接改变状态；旧连接迟到的 close/failure 不得击穿新连接。 */
    private fun markDisconnected(socket: WebSocket): Boolean {
        if (webSocket !== socket) return false
        webSocket = null
        mutableConnectionState.value = GatewayConnectionState.DISCONNECTED
        return true
    }

    /** hello 文本帧：payload 字段照 protocol.md §3.1；sessionId 服务端权威，客户端不预生成。 */
    private fun helloFrame(): String =
        gson.toJson(
            mapOf(
                "type" to "hello",
                "payload" to buildMap {
                    put("client", CLIENT_NAME)
                    put("protocolVersion", PROTOCOL_VERSION)
                    // 仅回带服务端此前签发的 ID；首次连接仍不由客户端预生成。
                    serverSessionId?.let { put("sessionId", it) }
                    // D02b：会话恢复凭据随 sessionId 一同回带（凭据绝不进日志/遥测）
                    serverResumeToken?.let { put("resumeToken", it) }
                    // M5 鉴权：配置了凭据才带（auth-disabled 网关保持老 hello 形态）
                    deviceId?.let { put("deviceId", it) }
                    authToken?.let { put("authToken", it) }
                },
            ),
        )

    private fun sendFrame(frame: Map<String, Any>) {
        val ws = webSocket ?: throw GatewayException("not connected")
        if (!ws.send(gson.toJson(frame))) {
            throw GatewayException("send failed: websocket not open")
        }
    }

}

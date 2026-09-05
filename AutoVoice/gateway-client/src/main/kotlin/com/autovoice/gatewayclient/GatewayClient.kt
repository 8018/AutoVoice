package com.autovoice.gatewayclient

import com.autovoice.voicecore.ActionReply
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.GatewayMessage
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.SlotValue
import com.autovoice.voicecore.TextReply
import com.autovoice.voicecore.AudioStreamEnd
import com.autovoice.voicecore.StreamingAudioReply
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel
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
 * 之后 [messages] 事件流（含 reply、S2S start/chunk/end、error 等）才被填充。
 * 一轮话语：`sendAudioStart` → 二进制 `sendAudioChunk`（PCM S16LE/16kHz/单声道）→
 * `sendAudioEnd`（durationMs 由已发送字节数换算）；服务端回 decision + reply 事件。
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
    private val sampleRate: Int = 16_000,
    private val channels: Int = 1,
    private val encoding: String = "pcm_s16le",
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

    /** 当前录音段已发送的 PCM 字节数，audio_end 据此换算 durationMs。 */
    @Volatile
    private var pcmBytesInSegment: Long = 0

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

    /** 当前时钟偏移（ms）：telemetry 打戳时 `本地时间 + offset` 换算为服务器时钟。 */
    fun clockOffsetMs(): Long = clockOffsetMs

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

    /**
     * 声明一段录音流开始（protocol.md §3.2）。
     *
     * @param sessionId 会话 ID（服务端权威，来自 ready 回执）
     * @param segmentId 可选：每轮话语的唯一 ID（客户端生成，如 UUID），服务端在 reply/error
     *                  中原样回显（§3.2 关联语义），端侧据此丢弃上一轮迟到的消息。非空才发送。
     * @param utteranceId 可选（T6）：本轮话语的链路追踪 ID（客户端生成，如 UUID），服务端
     *                    onAudioStart 优先采纳端侧值（遥测按话语汇合）。非空才发送。
     * 此后发送二进制 PCM 帧直到 [sendAudioEnd]。
     */
    fun sendAudioStart(
        sessionId: String,
        segmentId: String? = null,
        utteranceId: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        attempt: Int = 0,
        navigationSelectionId: String? = null,
    ) {
        val payload = linkedMapOf<String, Any>(
            "sessionId" to sessionId,
            "sampleRate" to sampleRate,
            "channels" to channels,
            "encoding" to encoding,
        )
        if (segmentId != null) {
            payload["segmentId"] = segmentId
        }
        if (utteranceId != null) {
            payload["utteranceId"] = utteranceId
        }
        if (latitude != null && longitude != null) {
            payload["latitude"] = latitude
            payload["longitude"] = longitude
        }
        payload["attempt"] = attempt
        if (navigationSelectionId != null) payload["navigationSelectionId"] = navigationSelectionId
        sendFrame(mapOf("type" to "audio_start", "payload" to payload))
        pcmBytesInSegment = 0
    }

    /** 发送一帧二进制 PCM（S16LE/16kHz/单声道），仅允许在 audio_start 与 audio_end 之间。 */
    fun sendAudioChunk(pcm: ByteArray) {
        val ws = webSocket ?: throw GatewayException("not connected")
        if (!ws.send(pcm.toByteString())) {
            throw GatewayException("send audio chunk failed: websocket not open")
        }
        pcmBytesInSegment += pcm.size
    }

    /** 结束录音段（protocol.md §3.3）：durationMs 由已发送字节数换算（bytes / (2·rate) · 1000）。 */
    fun sendAudioEnd(sessionId: String) {
        val durationMs = pcmBytesInSegment * 1000 / (2L * sampleRate)
        sendFrame(
            mapOf(
                "type" to "audio_end",
                "payload" to mapOf(
                    "sessionId" to sessionId,
                    "durationMs" to durationMs,
                ),
            ),
        )
    }

    /** 开启闲聊域的长连接上行；收到 chat_ready 后可连续发送二进制 PCM。 */
    fun sendChatStart(sessionId: String) {
        sendFrame(mapOf("type" to "chat_start", "payload" to mapOf("sessionId" to sessionId)))
    }

    /** 闲聊域连续 PCM；不受模型输出/播放状态影响。 */
    fun sendChatAudioChunk(pcm: ByteArray) {
        val ws = webSocket ?: throw GatewayException("not connected")
        if (!ws.send(pcm.toByteString())) {
            throw GatewayException("send chat audio chunk failed: websocket not open")
        }
    }

    fun sendChatFinish(sessionId: String) {
        sendFrame(mapOf("type" to "chat_finish", "payload" to mapOf("sessionId" to sessionId)))
    }

    /** 端侧车窗候选胜出：取消对应云端轮，服务端据此终止 Qwen Call。 */
    fun sendCancelTurn(segmentId: String, reason: String = "device_local_won") {
        sendFrame(
            mapOf(
                "type" to "cancel_turn",
                "payload" to mapOf("segmentId" to segmentId, "reason" to reason),
            ),
        )
    }

    /**
     * 独立 TTS 播报请求（protocol.md §3.4）：设备执行 intent 后按 speakText 调用。
     * 回复经 [parseTtsResponse] 对账（同一 segmentId）；与录音段流程互不干扰。
     *
     * @param utteranceId 可选（T6）：当前话语的链路追踪 ID，服务端落库时关联 tts 事件。
     *                    非空才发送。
     */
    fun sendTtsRequest(text: String, segmentId: String? = null, utteranceId: String? = null) {
        val payload = linkedMapOf<String, Any>("text" to text)
        if (segmentId != null) {
            payload["segmentId"] = segmentId
        }
        if (utteranceId != null) {
            payload["utteranceId"] = utteranceId
        }
        sendFrame(mapOf("type" to "tts_request", "payload" to payload))
    }

    /**
     * 解析 tts_response payload（protocol.md §4.6）：mime / dataBase64 解码 →
     * [AudioReply]（speakText=text 回显）。字段缺失 / base64 非法 → null（防御，不抛）。
     */
    fun parseTtsResponse(payload: JsonObject): AudioReply? {
        val mime = payload.get("mime")?.stringOrNull() ?: return null
        val dataBase64 = payload.get("dataBase64")?.stringOrNull() ?: return null
        val data = try {
            Base64.getDecoder().decode(dataBase64)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return AudioReply(
            mime = mime,
            data = data,
            speakText = payload.get("text")?.stringOrNull() ?: "",
        )
    }

    /**
     * 解析 reply payload（protocol.md §4.4）：
     *  - kind=audio → [AudioReply]（mime / dataBase64 解码 / speakText / 可选 intent）；
     *  - kind=text → [TextReply]（text + speakText）；
     *  - kind=action → [ActionReply]（intent + speakText）。
     * asrText（Task 61：云端 ASR 识别文本）随各 kind 透传，缺失时为空串。
     * payload 非法 / 字段缺失 / 未知 kind → null（防御，不抛）。
     */
    fun parseReply(payload: JsonObject): Reply? {
        val kind = payload.get("kind")?.stringOrNull() ?: return null
        val asrText = payload.get("asrText")?.stringOrNull() ?: ""
        return when (kind) {
            "text" -> {
                val text = payload.get("text")?.stringOrNull() ?: return null
                TextReply(text = text, asrText = asrText)
            }
            "audio" -> {
                val mime = payload.get("mime")?.stringOrNull() ?: return null
                val dataBase64 = payload.get("dataBase64")?.stringOrNull() ?: return null
                val data = try {
                    Base64.getDecoder().decode(dataBase64)
                } catch (e: IllegalArgumentException) {
                    return null
                }
                AudioReply(
                    mime = mime,
                    data = data,
                    speakText = payload.get("speakText")?.stringOrNull() ?: "",
                    intent = parseIntent(payload.get("intent")),
                    asrText = asrText,
                )
            }
            "action" -> {
                val intent = parseIntent(payload.get("intent")) ?: return null
                ActionReply(
                    intent = intent,
                    speakText = payload.get("speakText")?.stringOrNull() ?: "",
                    asrText = asrText,
                )
            }
            else -> null
        }
    }

    fun parseAudioStreamStart(
        payload: JsonObject,
        chunks: ReceiveChannel<ByteArray>,
        completion: Deferred<AudioStreamEnd>,
    ): StreamingAudioReply? {
        val mime = payload.get("mime")?.stringOrNull() ?: return null
        val sampleRate = payload.get("sampleRate")?.numberOrNull()?.toInt() ?: return null
        val channels = payload.get("channels")?.numberOrNull()?.toInt() ?: return null
        val encoding = payload.get("encoding")?.stringOrNull() ?: return null
        if (sampleRate <= 0 || channels <= 0) return null
        return StreamingAudioReply(mime, sampleRate, channels, encoding, chunks, completion)
    }

    fun parseAudioStreamEnd(payload: JsonObject): AudioStreamEnd =
        AudioStreamEnd(
            speakText = payload.get("speakText")?.stringOrNull() ?: "",
            intent = parseIntent(payload.get("intent")),
            asrText = payload.get("asrText")?.stringOrNull() ?: "",
        )

    private suspend fun doConnect() {
        val ready = CompletableDeferred<GatewayMessage>()
        val ws = try {
            okHttp.newWebSocket(
                Request.Builder().url(url).build(),
                GatewayListener(events, gson, ready, ::markDisconnected),
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
            if (webSocket !== ws) {
                throw GatewayException("websocket disconnected before ready completed")
            }
            mutableConnectionState.value = GatewayConnectionState.READY
            pcmBytesInSegment = 0
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

    /** intent 解析（与 shared/contracts/intent.schema.json 对齐）：字段缺失/类型不符 → null。 */
    internal fun parseIntent(element: JsonElement?): Intent? {
        if (element == null || !element.isJsonObject) return null
        val o = element.asJsonObject
        val schemaVersion = o.get("schemaVersion")?.stringOrNull() ?: return null
        val domain = o.get("domain")?.stringOrNull() ?: return null
        val intent = o.get("intent")?.stringOrNull() ?: return null
        val confidence = o.get("confidence")?.numberOrNull() ?: return null
        val source = o.get("source")?.stringOrNull() ?: return null
        val slots = parseSlots(o.get("slots")) ?: return null
        val rawSemantic = o.get("rawSemantic")?.stringOrNull()
        return Intent(schemaVersion, domain, intent, slots, confidence, source, rawSemantic)
    }

    /** slots 解析：`{"<槽名>": {"type": "number|enum|string|boolean", "value": ...}}`。 */
    private fun parseSlots(element: JsonElement?): Map<String, SlotValue>? {
        if (element == null || !element.isJsonObject) return null
        val result = LinkedHashMap<String, SlotValue>()
        for ((name, slotEl) in element.asJsonObject.entrySet()) {
            if (!slotEl.isJsonObject) return null
            val slot = slotEl.asJsonObject
            val type = slot.get("type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: return null
            val valueEl = slot.get("value") ?: return null
            result[name] = when (type) {
                "number" -> valueEl.numberOrNull()?.let { SlotValue.Number(it) } ?: return null
                "enum" -> valueEl.stringOrNull()?.let { SlotValue.EnumValue(it) } ?: return null
                "string" -> valueEl.stringOrNull()?.let { SlotValue.StringValue(it) } ?: return null
                "boolean" -> valueEl.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                    ?.let { SlotValue.Bool(it) } ?: return null
                else -> return null
            }
        }
        return result
    }

    private fun JsonElement?.stringOrNull(): String? =
        this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonElement?.numberOrNull(): Double? =
        this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
}

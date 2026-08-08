package com.autovoice.gatewayclient

import com.autovoice.voicecore.ActionReply
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.GatewayMessage
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.SlotValue
import com.autovoice.voicecore.TextReply
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString

/**
 * 网关连接失败（重试耗尽 / 等待 ready 超时 / 传输错误）时抛出。
 */
class GatewayException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * AutoVoiceServer 网关 WebSocket 客户端（shared/protocol.md §5 时序）。
 *
 * 会话流程：`connect()` 建立连接并发送 hello（payload 字段照 protocol.md §3.1；
 * sessionId 不预生成——服务端权威，由 ready 回执下发），收到 ready 后返回；
 * 之后 [messages] 事件流（ready/decision/asr_partial/reply/error/bye）才被填充。
 * 一轮话语：`sendAudioStart` → 二进制 `sendAudioChunk`（PCM S16LE/16kHz/单声道）→
 * `sendAudioEnd`（durationMs 由已发送字节数换算）；服务端回 decision + reply 事件。
 *
 * 断线重连：仅在 [connect] 内重试（指数退避 backoffBaseMs 翻倍，默认 1s/2s/4s，
 * 最多 [maxRetries] 次重试），仍失败抛 [GatewayException]。`disconnect()` 幂等。
 *
 * 背压决策：事件流 [messages] 为 SharedFlow，replay=1（connect 后订阅可补到 ready）、
 * extraBufferCapacity=64、onBufferOverflow=DROP_OLDEST——慢消费者丢最旧事件、保留最新事件
 * （reply 优先，单轮会话事件量远小于 64，正常消费不会丢）。
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
) {
    companion object {
        /** protocol.md §3.1 hello 的客户端标识。 */
        const val CLIENT_NAME = "autovoice-android"

        /** protocol.md §3.1 hello 的协议版本。 */
        const val PROTOCOL_VERSION = "1.0"
    }

    private val events = MutableSharedFlow<GatewayMessage>(
        // replay=1：connect() 返回后订阅的消费者能立即补到最近一个事件（ready 在
        // 无订阅者期间被 emit，否则会被丢弃）；active 订阅者不受影响，事件照常下发。
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 网关事件流：ready/decision/asr_partial/reply/error/bye；传输失败合成 error 事件。 */
    val messages: SharedFlow<GatewayMessage> = events.asSharedFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    /** 当前录音段已发送的 PCM 字节数，audio_end 据此换算 durationMs。 */
    @Volatile
    private var pcmBytesInSegment: Long = 0

    /**
     * 建立连接并等待 ready 后返回。
     *
     * 每次尝试：open → 发 hello → 等 ready（connectTimeoutMs 内未到即失败）。
     * 失败按指数退避重试（1s/2s/4s…），超过 [maxRetries] 次重试仍失败抛 [GatewayException]；
     * 协程取消原样传播（不吞）。
     */
    suspend fun connect() {
        var attempt = 0
        while (true) {
            try {
                doConnect()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
        val ws = webSocket
        webSocket = null
        ws?.close(1000, "client disconnect")
    }

    /**
     * 声明一段录音流开始（protocol.md §3.2）。demo 为单段会话，segmentId 即会话内使用的
     * sessionId（来自 ready 回执）。此后发送二进制 PCM 帧直到 [sendAudioEnd]。
     */
    fun sendAudioStart(segmentId: String) {
        sendFrame(
            mapOf(
                "type" to "audio_start",
                "payload" to mapOf(
                    "sessionId" to segmentId,
                    "sampleRate" to sampleRate,
                    "channels" to channels,
                    "encoding" to encoding,
                ),
            ),
        )
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
    fun sendAudioEnd(segmentId: String) {
        val durationMs = pcmBytesInSegment * 1000 / (2L * sampleRate)
        sendFrame(
            mapOf(
                "type" to "audio_end",
                "payload" to mapOf(
                    "sessionId" to segmentId,
                    "durationMs" to durationMs,
                ),
            ),
        )
    }

    /**
     * 解析 reply payload（protocol.md §4.4）：
     *  - kind=audio → [AudioReply]（mime / dataBase64 解码 / speakText / 可选 intent）；
     *  - kind=text → [TextReply]（text + speakText）；
     *  - kind=action → [ActionReply]（intent + speakText）。
     * payload 非法 / 字段缺失 / 未知 kind → null（防御，不抛）。
     */
    fun parseReply(payload: JsonObject): Reply? {
        val kind = payload.get("kind")?.stringOrNull() ?: return null
        return when (kind) {
            "text" -> {
                val text = payload.get("text")?.stringOrNull() ?: return null
                TextReply(text)
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
                )
            }
            "action" -> {
                val intent = parseIntent(payload.get("intent")) ?: return null
                ActionReply(intent = intent, speakText = payload.get("speakText")?.stringOrNull() ?: "")
            }
            else -> null
        }
    }

    private suspend fun doConnect() {
        val ready = CompletableDeferred<GatewayMessage>()
        val ws = try {
            okHttp.newWebSocket(
                Request.Builder().url(url).build(),
                GatewayListener(events, gson, ready),
            )
        } catch (e: Exception) {
            throw GatewayException("cannot open websocket to $url: ${e.message}", e)
        }
        try {
            // open 后立即发 hello（OkHttp 排队到握手完成后发出）；ready 回执由 listener 完成
            ws.send(helloFrame())
            val readyMsg = withTimeoutOrNull(connectTimeoutMs) { ready.await() }
                ?: throw GatewayException("timeout waiting for ready within ${connectTimeoutMs}ms")
            webSocket = ws
            pcmBytesInSegment = 0
        } catch (e: Exception) {
            ws.cancel()
            throw e
        }
    }

    /** hello 文本帧：payload 字段照 protocol.md §3.1；sessionId 服务端权威，客户端不预生成。 */
    private fun helloFrame(): String =
        gson.toJson(
            mapOf(
                "type" to "hello",
                "payload" to mapOf(
                    "client" to CLIENT_NAME,
                    "protocolVersion" to PROTOCOL_VERSION,
                ),
            ),
        )

    private fun sendFrame(frame: Map<String, Any>) {
        val ws = webSocket ?: throw GatewayException("not connected")
        if (!ws.send(gson.toJson(frame))) {
            throw GatewayException("send failed: websocket not open")
        }
    }

    /** intent 解析（与 shared/contracts/intent.schema.json 对齐）：字段缺失/类型不符 → null。 */
    private fun parseIntent(element: JsonElement?): Intent? {
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

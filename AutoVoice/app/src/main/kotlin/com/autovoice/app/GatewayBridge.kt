package com.autovoice.app

import android.util.Log
import com.autovoice.gatewayclient.GatewayClient
import com.autovoice.gatewayclient.GatewayException
import com.autovoice.gatewayclient.GatewayPayloadParser
import com.autovoice.messaging.ListenerRegistration
import com.autovoice.messaging.MessageDispatcher
import com.autovoice.messaging.MessageListener
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.AudioStreamEnd
import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.GatewayMessage
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.voicecore.arbiter.DecisionSink
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val AUDIO_REPLY_QUEUE_CAPACITY = 64
private const val GATEWAY_BRIDGE_TAG = "GatewayBridge"

/** Gateway application error, distinct from a transport disconnect. */
internal class GatewayRemoteException(val code: String, message: String) : GatewayException(message)

private class PendingSlot<T>(
    val segmentId: String,
    val utteranceId: String,
    val deferred: CompletableDeferred<T>,
)

/** Cloud ASR owns transcript and turn-establishment messages, independent of semantic replies. */
private class CloudAsrEngine(
    private val pendingReplies: ConcurrentHashMap<String, PendingSlot<Reply>>,
    private val onAsrResult: (String, Boolean, String) -> Unit,
    private val onAsrTurnEstablished: (String) -> Unit,
) : MessageListener {
    val messageTypes = setOf("asr_turn_started", "asr_partial")

    override fun onMessage(message: GatewayMessage) {
        when (message.type) {
            "asr_turn_started" -> {
                val slot = findSlot(message.payload, pendingReplies) ?: return
                if (!isForSlot(message.payload, slot)) return
                onAsrTurnEstablished(slot.utteranceId)
            }
            "asr_partial" -> {
                if (message.payload.get("chat")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
                    val text = message.payload.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: return
                    val final = message.payload.get("isFinal")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                    onAsrResult(text, final, "")
                    return
                }
                val slot = findSlot(message.payload, pendingReplies) ?: return
                if (!isForSlot(message.payload, slot)) return
                val text = message.payload.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: return
                val final = message.payload.get("isFinal")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                onAsrResult(text, final, slot.utteranceId)
            }
        }
    }
}

/** Cloud NLU owns semantic, pending and decision messages; ASR text never enters this engine. */
private class CloudNluEngine(
    private val pendingReplies: ConcurrentHashMap<String, PendingSlot<Reply>>,
    private val parser: GatewayPayloadParser,
    private val sink: DecisionSink,
    private val pendingSignals: SendChannel<Unit>,
    private val onPendingReceived: (String) -> Unit,
    private val onReplyText: (String, Boolean, String) -> Unit,
    private val onError: (GatewayMessage) -> Unit,
) : MessageListener {
    val messageTypes = setOf("decision", "reply_partial", "reply", "pending", "error")

    override fun onMessage(message: GatewayMessage) {
        when (message.type) {
            "decision" -> parseDecision(message.payload)?.let(sink::onDecision)
            "reply_partial" -> {
                if (message.payload.get("chat")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
                    val text = message.payload.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: return
                    val final = message.payload.get("isFinal")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                    onReplyText(text, final, "")
                    return
                }
                val slot = findSlot(message.payload, pendingReplies) ?: return
                if (!isForSlot(message.payload, slot)) return
                val text = message.payload.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: return
                val final = message.payload.get("isFinal")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                onReplyText(text, final, slot.utteranceId)
            }
            "reply" -> {
                val slot = findSlot(message.payload, pendingReplies) ?: return
                if (!isForSlot(message.payload, slot)) return
                parser.reply(message.payload)?.let { slot.deferred.complete(it) }
            }
            "pending" -> {
                val slot = findSlot(message.payload, pendingReplies) ?: return
                if (!isForSlot(message.payload, slot)) return
                pendingSignals.trySend(Unit)
                onPendingReceived(slot.utteranceId)
            }
            "error" -> onError(message)
        }
    }
}

/** Correlate an inbound message with one request slot; missing id is accepted only if unambiguous. */
private fun <T> findSlot(
    payload: JsonObject,
    slots: ConcurrentHashMap<String, PendingSlot<T>>,
): PendingSlot<T>? {
    val segmentId = payload.get("segmentId")?.takeIf { it.isJsonPrimitive }?.asString
    if (segmentId != null) return slots[segmentId]
    return slots.values.singleOrNull()
}

private fun isForSlot(payload: JsonObject, slot: PendingSlot<*>): Boolean {
    val messageSegmentId = payload.get("segmentId")?.takeIf { it.isJsonPrimitive }?.asString
    if (messageSegmentId == null) return true
    if (messageSegmentId != slot.segmentId) {
        Log.d(
            GATEWAY_BRIDGE_TAG,
            "丢弃不属于当前话语的消息（segmentId=$messageSegmentId, 期望=${slot.segmentId}）",
        )
        return false
    }
    return true
}

private fun parseDecision(payload: JsonObject): DecisionEntry? {
    val arbiter = payload.get("arbiter")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
    val route = payload.get("route")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
    val reason = payload.get("reason")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
    val utteranceId = payload.get("utteranceId")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
    val timestampMs = payload.get("timestampMs")?.takeIf { it.isJsonPrimitive }?.asLong
        ?: System.currentTimeMillis()
    return DecisionEntry(arbiter, route, reason, utteranceId, timestampMs)
}

/**
 * 网关事件桥：构造时一次性订阅 [GatewayClient.messages]（SharedFlow replay=1），
 * 把 decision 事件透传进 sink、reply 事件投递到当前话语的等待槽、
 * error 事件让等待中的回复立即失败（提前暴露故障，不必等仲裁超时）。
 *
 * 消息关联（protocol.md §3.2）：reply / error 按 payload 中的 `segmentId` 与当前话语的等待槽对账——
 * 携带的 segmentId 与本轮不一致（上一轮迟到的消息）→ 丢弃并 Log.d；未携带 segmentId（服务端
 * 合成的传输错误 / 旧版服务端）→ 无法对账，按当前槽处理（保留快速失败语义）。同一时刻至多一个
 * 等待槽，跨轮消息天然按 segmentId 隔离。
 *
 * TTS 槽（A3）：tts_response 走独立的 [pendingTts] 槽，与话语 reply 槽互不干扰
 * （tts 播报与话语回复是两条独立时间线），各自按 segmentId 对账。
 */
internal class GatewayBridge(
    private val client: GatewayClient,
    private val sink: DecisionSink,
    scope: CoroutineScope,
    /** B5：云端 pending 占位信号（LLM 处理中）→ 延后本地普通语义入队。 */
    private val pendingSignals: SendChannel<Unit> = Channel(Channel.BUFFERED),
    /** B5：pending 帧已对账通过的回调（装配方绑定 → UI"处理中…"状态）。 */
    private val onPendingReceived: (String) -> Unit = {},
    /** ASR/PGS partial/final，按 segmentId 对账后立即交 UI。 */
    private val onAsrResult: (String, Boolean, String) -> Unit = { _, _, _ -> },
    /** ASR/AEC 确认新话语，按 segmentId 对账后交给本地状态机。 */
    private val onAsrTurnEstablished: (String) -> Unit = {},
    /** 回答文本 partial/final，按 segmentId 对账后立即交 UI。 */
    private val onReplyText: (String, Boolean, String) -> Unit = { _, _, _ -> },
    /** Realtime 闲聊是长会话，模型回答不依赖普通话语 reply slot。 */
    private val onChatReply: (StreamingAudioReply) -> Unit = {},
    /** 模型语义 VAD 检测到用户开口：只截断播放，连续上行不停止。 */
    private val onChatSpeechStarted: () -> Unit = {},
    /** Realtime 上游断开；调用方在锁域仍有效时重建 chat_start。 */
    private val onChatFailure: () -> Unit = {},
) {
    private val dispatcher = MessageDispatcher { message, failure ->
        Log.e(GATEWAY_BRIDGE_TAG, "gateway listener failed: type=${message.type}", failure)
    }
    private val parser = GatewayPayloadParser()

    private class ActiveStream(
        val segmentId: String,
        val utteranceId: String,
        val chunks: Channel<ByteArray>,
        val completion: CompletableDeferred<AudioStreamEnd>,
    )

    private val pendingReplies = ConcurrentHashMap<String, PendingSlot<Reply>>()
    private val pendingTts = ConcurrentHashMap<String, PendingSlot<AudioReply>>()
    private val activeStream = AtomicReference<ActiveStream?>(null)
    private sealed interface ChatReadyEvent {
        data class Ready(val chatId: String) : ChatReadyEvent
        data class Failed(val chatId: String, val error: GatewayRemoteException) : ChatReadyEvent
    }
    private val chatReady = Channel<ChatReadyEvent>(Channel.CONFLATED)
    private val activeChatId = AtomicReference<String?>(null)

    /** TTS response listener is independent from ASR/NLU; error is intentionally multi-cast. */
    private inner class CloudTtsListener : MessageListener {
        val messageTypes = setOf("tts_response", "error")
        override fun onMessage(message: GatewayMessage) {
            if (message.type == "tts_response") handleTtsResponse(message) else handleTtsError(message)
        }
    }

    private inner class RealtimeChatListener : MessageListener {
        val messageTypes = setOf("chat_ready", "chat_speech_started")
        override fun onMessage(message: GatewayMessage) = handleChat(message)
    }

    /** Audio response stream is shared by ordinary NLU replies and realtime chat. */
    private inner class CloudAudioReplyListener : MessageListener {
        val messageTypes = setOf("audio_reply_start", "audio_reply_chunk", "audio_reply_end")
        override fun onMessage(message: GatewayMessage) = handleAudioReply(message)
    }

    init {
        val asrEngine = CloudAsrEngine(pendingReplies, onAsrResult, onAsrTurnEstablished)
        val nluEngine = CloudNluEngine(
            pendingReplies,
            parser,
            sink,
            pendingSignals,
            onPendingReceived,
            onReplyText,
            ::handleNluError,
        )
        val ttsListener = CloudTtsListener()
        val chatListener = RealtimeChatListener()
        val audioListener = CloudAudioReplyListener()
        dispatcher.register(asrEngine.messageTypes, asrEngine)
        dispatcher.register(nluEngine.messageTypes, nluEngine)
        dispatcher.register(ttsListener.messageTypes, ttsListener)
        dispatcher.register(chatListener.messageTypes, chatListener)
        dispatcher.register(audioListener.messageTypes, audioListener)
        scope.launch {
            client.messages.collect { message ->
                if (!isStaleChatMessage(message)) dispatcher.dispatch(message)
            }
        }
    }

    /** Additional observers may subscribe without becoming part of the gateway transport. */
    fun register(types: Set<String>, listener: MessageListener): ListenerRegistration =
        dispatcher.register(types, listener)

    /** 注册当前话语的回复等待槽（先于发送注册，避免 reply 先到被丢）。 */
    fun newReplySlot(segmentId: String, utteranceId: String = ""): CompletableDeferred<Reply> {
        val deferred = CompletableDeferred<Reply>()
        pendingReplies[segmentId] = PendingSlot(segmentId, utteranceId, deferred)
        return deferred
    }

    fun clearReplySlot(deferred: CompletableDeferred<Reply>) {
        pendingReplies.entries.firstOrNull { it.value.deferred === deferred }?.let {
            pendingReplies.remove(it.key, it.value)
        }
    }

    fun cancelStream(segmentId: String) {
        val stream = activeStream.get() ?: return
        if (stream.segmentId != segmentId || !activeStream.compareAndSet(stream, null)) return
        val error = CancellationException("audio stream cancelled: $segmentId")
        stream.chunks.close(error)
        stream.completion.completeExceptionally(error)
    }

    /** 注册独立 TTS 播报槽（tts_response 对账用，与 reply 槽隔离）。 */
    fun newTtsSlot(segmentId: String, utteranceId: String = ""): CompletableDeferred<AudioReply> {
        val deferred = CompletableDeferred<AudioReply>()
        pendingTts[segmentId] = PendingSlot(segmentId, utteranceId, deferred)
        return deferred
    }

    fun clearTtsSlot(deferred: CompletableDeferred<AudioReply>) {
        pendingTts.entries.firstOrNull { it.value.deferred === deferred }?.let {
            pendingTts.remove(it.key, it.value)
        }
    }

    fun beginChat(chatId: String) {
        require(chatId.isNotBlank())
        finishChat()
        activeChatId.set(chatId)
    }

    suspend fun awaitChatReady(chatId: String) {
        withTimeoutOrNull(8_000) {
            while (true) {
                val received = chatReady.receive()
                when (received) {
                    is ChatReadyEvent.Ready ->
                        if (received.chatId.isBlank() || received.chatId == chatId) break
                    is ChatReadyEvent.Failed ->
                        if (received.chatId.isBlank() || received.chatId == chatId) throw received.error
                }
            }
        }
            ?: throw GatewayException("chat_ready timeout")
    }

    fun finishChat() {
        activeChatId.set(null)
        activeStream.getAndSet(null)?.let { stream ->
            val stopped = CancellationException("realtime chat finished")
            stream.chunks.close(stopped)
            stream.completion.completeExceptionally(stopped)
        }
    }

    private fun handleAudioReply(msg: GatewayMessage) {
        when (msg.type) {
            "audio_reply_start" -> {
                if (msg.payload.get("chat")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
                    val segmentId = msg.payload.get("segmentId")?.asString ?: return
                    val chunks = Channel<ByteArray>(AUDIO_REPLY_QUEUE_CAPACITY)
                    val completion = CompletableDeferred<AudioStreamEnd>()
                    val reply = parser.streamStart(msg.payload, chunks, completion) ?: return
                    val stream = ActiveStream(segmentId, "", chunks, completion)
                    activeStream.getAndSet(stream)?.let { previous ->
                        val replaced = CancellationException("replaced by realtime response")
                        previous.chunks.close(replaced)
                        previous.completion.completeExceptionally(replaced)
                    }
                    onChatReply(reply)
                    return
                }
                val slot = findSlot(msg.payload, pendingReplies) ?: return
                if (!isForSlot(msg.payload, slot)) return
                val chunks = Channel<ByteArray>(AUDIO_REPLY_QUEUE_CAPACITY)
                val completion = CompletableDeferred<AudioStreamEnd>()
                val reply = parser.streamStart(msg.payload, chunks, completion) ?: return
                val stream = ActiveStream(slot.segmentId, slot.utteranceId, chunks, completion)
                activeStream.getAndSet(stream)?.let { previous ->
                    previous.chunks.close(CancellationException("replaced by a newer stream"))
                    previous.completion.completeExceptionally(
                        CancellationException("replaced by a newer stream"),
                    )
                }
                slot.deferred.complete(reply)
            }
            "audio_reply_chunk" -> {
                val stream = activeStream.get() ?: return
                msg.binary?.let { bytes ->
                    val result = stream.chunks.trySend(bytes)
                    if (result.isFailure && !result.isClosed && activeStream.compareAndSet(stream, null)) {
                        val overflow = GatewayException("audio reply queue overflow: ${stream.segmentId}")
                        stream.chunks.close(overflow)
                        stream.completion.completeExceptionally(overflow)
                    }
                }
            }
            "audio_reply_end" -> {
                val stream = activeStream.get() ?: return
                val msgSegmentId = msg.payload.get("segmentId")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: return
                if (msgSegmentId != stream.segmentId) return
                if (activeStream.compareAndSet(stream, null)) {
                    stream.completion.complete(parser.streamEnd(msg.payload))
                    stream.chunks.close()
                }
            }
            else -> Unit
        }
    }

    private fun handleChat(msg: GatewayMessage) {
        when (msg.type) {
            "chat_ready" -> chatReady.trySend(ChatReadyEvent.Ready(
                msg.payload.get("chatId")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
            ))
            "chat_speech_started" -> {
                activeStream.getAndSet(null)?.let { stream ->
                    val interrupted = CancellationException("user speech started")
                    stream.chunks.close(interrupted)
                    stream.completion.completeExceptionally(interrupted)
                }
                onChatSpeechStarted()
            }
            else -> Unit
        }
    }

    /** Old servers omit chatId; tagged frames from a new server must match the current chat. */
    private fun isStaleChatMessage(message: GatewayMessage): Boolean {
        val chat = message.type == "chat_ready" || message.type == "chat_speech_started" ||
            message.payload.get("chat")?.takeIf { it.isJsonPrimitive }?.asBoolean == true ||
            (message.type == "error" && message.payload.get("code")
                ?.takeIf { it.isJsonPrimitive }?.asString?.startsWith("CHAT_") == true)
        if (!chat) return false
        val currentId = activeChatId.get() ?: return true
        val messageId = message.payload.get("chatId")?.takeIf { it.isJsonPrimitive }?.asString
        return !messageId.isNullOrBlank() && messageId != currentId
    }

    private fun handleTtsResponse(msg: GatewayMessage) {
        val slot = findSlot(msg.payload, pendingTts) ?: return
        if (!isForSlot(msg.payload, slot)) return
        parser.tts(msg.payload)?.let { slot.deferred.complete(it) }
    }

    private fun handleNluError(msg: GatewayMessage) {
        val code = msg.payload.get("code")?.takeIf { it.isJsonPrimitive }?.asString ?: "UNKNOWN"
        val message = msg.payload.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "网关错误"
        val error = GatewayRemoteException(code, "$message [$code]")
        if (code.startsWith("CHAT_")) {
            val chatId = msg.payload.get("chatId")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            chatReady.trySend(ChatReadyEvent.Failed(chatId, error))
            if (code == "CHAT_STREAM_FAILED" || code == "CHAT_STREAM_CLOSED") {
                finishChat()
                onChatFailure()
            }
            return
        }
        val messageSegment = msg.payload.get("segmentId")
            ?.takeIf { value -> value.isJsonPrimitive }?.asString
        val affected = if (messageSegment == null) pendingReplies.values.toList()
        else listOfNotNull(pendingReplies[messageSegment])
        affected.forEach { it.deferred.completeExceptionally(error) }
        activeStream.get()?.takeIf {
            messageSegment == null || messageSegment == it.segmentId
        }?.let { stream ->
            if (!activeStream.compareAndSet(stream, null)) return@let
            stream.chunks.close(error)
            stream.completion.completeExceptionally(error)
        }
    }

    private fun handleTtsError(msg: GatewayMessage) {
        val code = msg.payload.get("code")?.takeIf { it.isJsonPrimitive }?.asString ?: "UNKNOWN"
        if (code.startsWith("CHAT_")) return
        val message = msg.payload.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "网关错误"
        val error = GatewayRemoteException(code, "$message [$code]")
        val segmentId = msg.payload.get("segmentId")?.takeIf { it.isJsonPrimitive }?.asString
        val affected = if (segmentId == null) pendingTts.values.toList()
        else listOfNotNull(pendingTts[segmentId])
        affected.forEach { it.deferred.completeExceptionally(error) }
    }

}

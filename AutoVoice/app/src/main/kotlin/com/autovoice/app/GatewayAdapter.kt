package com.autovoice.app

import android.util.Log
import com.autovoice.app.telemetry.TelemetryStages
import com.autovoice.gatewayclient.GatewayClient
import com.autovoice.gatewayclient.GatewayConnectionState
import com.autovoice.gatewayclient.GatewayException
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.CloudConfig
import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.GatewayMessage
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.voicecore.AudioStreamEnd
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.session.CloudRunner
import com.autovoice.voicecore.session.CloudRequestFailedException
import com.autovoice.voicecore.session.CloudUnavailableException
import com.google.gson.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/** 云端音频分块大小（gateway 协议 16KB/帧）。 */
private const val CLOUD_CHUNK_BYTES = 16_384

/** 网关事件桥日志 TAG。 */
private const val GATEWAY_BRIDGE_TAG = "GatewayBridge"

/** 网关已返回的应用层错误；code 用于区分真实断线与 BUSY/provider 等请求错误。 */
private class GatewayRemoteException(val code: String, message: String) : GatewayException(message)

/**
 * 云端链路实现（GatewayClient 装配）：首次/故障后重连 → 分块发送 PCM（16KB/帧）→
 * 收 reply。网关 decision 事件（type=decision）透传进 [DecisionSink]（UI 决策日志）。
 *
 * 故障语义（Task 15 M1 裁定）：ready 前失败（连接/重试耗尽）→ 抛 [CloudUnavailableException]
 * 且不 latch；ready 后失败（发送中断/收包错误）→ 先调 [onCloudUnavailable] latch
 * 后续话语只跑本地，再抛 [CloudUnavailableException]。两种故障本轮都回落到本地链。
 */
internal class GatewayCloudRunner(
    private val cfg: CloudConfig,
    private val sink: DecisionSink,
    private val scope: CoroutineScope,
    /** B5：云端 pending 占位信号（LLM 处理中）→ 透传给桥，桥对账后发出。 */
    private val pendingSignals: SendChannel<Unit> = Channel(Channel.BUFFERED),
    private val locationProvider: () -> Pair<Double, Double>? = { null },
    private val navigationSelectionProvider: () -> String = { "" },
) : CloudRunner, TtsRequester, RealtimeChatRunner, StreamingCloudRunner {

    private val client = GatewayClient(
        url = cfg.gatewayUrl,
        okHttp = OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .build(),
        // M5 鉴权：hello 注入设备凭据（auth-enabled 网关必填；未配置则保持老 hello）
        deviceId = cfg.deviceId,
        authToken = cfg.authToken,
        connectTimeoutMs = 3_000,
        maxRetries = 0, // 当前话语层保留同一 utteranceId 做一次安全重试
    )
    private val bridge = GatewayBridge(
        client,
        sink,
        scope,
        pendingSignals,
        { turnId -> onPendingReceived(turnId) },
        { text, final, turnId -> onAsrResult(text, final, turnId) },
        { turnId -> onAsrTurnEstablished(turnId) },
        { text, final, turnId -> handleReplyText(text, final, turnId) },
        { reply -> onRealtimeReply(reply) },
        { onRealtimeSpeechStarted() },
        { onRealtimeStreamFailed() },
    )

    /** D05b:采用确认上行;ready 前忽略。 */
    fun sendNavigationSelectionStart(selectionId: String) {
        val sid = sessionId
        if (!readyReceived || sid.isBlank()) return
        client.sendNavigationSelectionStart(sid, selectionId)
    }

    private data class ReplyTextSnapshot(val text: String, val isFinal: Boolean)

    private val pendingReplyText = ConcurrentHashMap<String, ReplyTextSnapshot>()
    private val releasedReplyTurns = ConcurrentHashMap.newKeySet<String>()

    /** 是否已收到 ready（含 sessionId）——据此区分 ready 前/后故障。 */
    @Volatile
    private var readyReceived = false

    @Volatile
    private var sessionId = ""

    @Volatile
    private var realtimeChatReady = false

    @Volatile
    private var realtimeChatDesired = false

    private val realtimeReconnectRunning = AtomicBoolean(false)

    private data class LiveUpload(
        val utteranceId: String,
        val navigationSelectionId: String,
        val segmentId: String = UUID.randomUUID().toString(),
        val chunks: Channel<ByteArray> = Channel(Channel.UNLIMITED),
        val reply: CompletableDeferred<Reply> = CompletableDeferred(),
        val admitted: AtomicBoolean = AtomicBoolean(false),
        val audioStarted: AtomicBoolean = AtomicBoolean(false),
        val commitSent: AtomicBoolean = AtomicBoolean(false),
    )

    private val liveUpload = AtomicReference<LiveUpload?>(null)

    /** 由 [VoiceEngineFactory.create] 在 engine 装配完成后绑定到 session.onCloudUnavailable()。 */
    lateinit var onCloudUnavailable: () -> Unit

    /**
     * B5：收到云端 pending 帧的回调（由 [VoiceEngineFactory.create] 装配后绑定 →
     * engine.setCloudPending(true)，UI 显示"处理中…"）。清除由 onTurnResult /
     * onListeningStart 收口。
     */
    @Volatile
    var onPendingReceived: (String) -> Unit = {}

    /** 独立 ASR/PGS 输出，不等待语义仲裁。 */
    @Volatile
    var onAsrResult: (String, Boolean, String) -> Unit = { _, _, _ -> }

    /** ASR/AEC 确认新话语；与识别文本事件独立。 */
    @Volatile
    var onAsrTurnEstablished: (String) -> Unit = {}

    /** 模型回答文本累计快照，用于音频播放期间上屏。 */
    @Volatile
    var onReplyText: (String, Boolean) -> Unit = { _, _ -> }

    @Volatile
    var onRealtimeReply: (StreamingAudioReply) -> Unit = {}

    @Volatile
    var onRealtimeSpeechStarted: () -> Unit = {}

    private fun onRealtimeStreamFailed() {
        realtimeChatReady = false
        if (!realtimeChatDesired || !realtimeReconnectRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                var delayMs = 500L
                repeat(3) {
                    delay(delayMs)
                    if (!realtimeChatDesired) return@launch
                    val recovered = runCatching { connectRealtimeChat() }.isSuccess
                    if (recovered) return@launch
                    delayMs *= 2
                }
                Log.w("GatewayCloudRunner", "realtime chat reconnect exhausted")
            } finally {
                realtimeReconnectRunning.set(false)
            }
        }
    }

    /**
     * 回复字幕属于云端语义输出，必须等端侧仲裁确认云端胜出；确认后立即发布已缓存的
     * 最新累计快照，后续 delta 则边播放边直达 UI。ASR 走独立回调，不经过这里。
     */
    fun releaseReplyText(turnId: String) {
        releasedReplyTurns.add(turnId)
        pendingReplyText[turnId]?.let {
            onReplyText(it.text, it.isFinal)
            if (it.isFinal) {
                pendingReplyText.remove(turnId)
                releasedReplyTurns.remove(turnId)
            }
        }
    }

    private fun handleReplyText(text: String, isFinal: Boolean, turnId: String) {
        val snapshot = ReplyTextSnapshot(text, isFinal)
        pendingReplyText[turnId] = snapshot
        if (turnId in releasedReplyTurns) {
            onReplyText(snapshot.text, snapshot.isFinal)
            if (isFinal) {
                pendingReplyText.remove(turnId)
                releasedReplyTurns.remove(turnId)
            }
        }
    }

    /**
     * 当前话语 utteranceId 读取器（T6）：由 [VoiceEngineFactory.create] 在 engine 装配完成后
     * 绑定到 `engine.currentUtteranceId`；空串时发帧不携带 utteranceId（服务端视为未提供）。
     */
    @Volatile
    var utteranceIdProvider: () -> String = { "" }

    /**
     * ready 回执的 sessionId 回调（T6 评审 C1）：由 [VoiceEngineFactory.create] 绑定到
     * `telemetry::onSessionId`——round body 按会话关联，缺此转发服务端
     * recordDeviceRound 会把 session_id="" 落库，轮次无法按会话查询。
     */
    @Volatile
    var onReadySessionId: (String) -> Unit = {}

    @Volatile
    var onConnectionEvent: (String, String, Map<String, Any?>) -> Unit = { _, _, _ -> }

    /** 时钟同步：委托网关客户端的时钟偏移（ready.serverTime 握手估算，每次握手刷新）。 */
    fun clockOffsetMs(): Long = client.clockOffsetMs()

    /** 释放：断开网关连接（幂等）；引擎 close() 时调用（Task 21 模式切换）。 */
    fun close() {
        liveUpload.getAndSet(null)?.let {
            it.chunks.close(CancellationException("gateway runner closed"))
            it.reply.cancel()
        }
        finishRealtimeChat()
        client.disconnect()
    }

    /** 前台预热不影响当前会话；失败留给真正话语的 ensureReady 重试并降级。 */
    fun warmUp() {
        if (!cfg.enabled || cfg.gatewayUrl.isBlank()) return
        scope.launch {
            runCatching { ensureReady() }
                .onFailure { Log.w("GatewayCloudRunner", "foreground warm-up failed", it) }
        }
    }

    private suspend fun ensureReady() {
        if (client.connectionState.value == GatewayConnectionState.READY && sessionId.isNotBlank()) return
        readyReceived = false
        sessionId = ""
        onConnectionEvent(TelemetryStages.WS_CONNECT_START, "info", emptyMap())
        client.connect()
        val ready = client.messages.first { it.type == "ready" }
        sessionId = ready.payload.get("sessionId")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw GatewayException("ready 事件缺少 sessionId")
        readyReceived = true
        onReadySessionId(sessionId)
        onConnectionEvent(TelemetryStages.WS_READY, "info", mapOf("sessionId" to sessionId))
    }

    override fun beginStreamingTurn(utteranceId: String) {
        if (!cfg.enabled || utteranceId.isBlank()) return
        val upload = LiveUpload(utteranceId, navigationSelectionProvider())
        while (true) {
            val previous = liveUpload.get()
            // 同一按钮轮可能包含多个 VAD 段；它们属于同一条业务音频流。
            if (previous?.utteranceId == utteranceId) return
            if (liveUpload.compareAndSet(previous, upload)) {
                previous?.chunks?.close(CancellationException("superseded by new streaming turn"))
                previous?.reply?.cancel()
                break
            }
        }
        scope.launch { executeLiveUpload(upload) }
    }

    override fun appendStreamingAudio(pcm: ByteArray) {
        if (pcm.isNotEmpty()) liveUpload.get()?.chunks?.trySend(pcm.copyOf())
    }

    override fun finishStreamingTurn(utteranceId: String) {
        liveUpload.get()?.takeIf { it.utteranceId == utteranceId }?.chunks?.close()
    }

    override fun cancelStreamingTurn(utteranceId: String) {
        val upload = liveUpload.get()?.takeIf { it.utteranceId == utteranceId } ?: return
        if (!liveUpload.compareAndSet(upload, null)) return
        upload.chunks.cancel(CancellationException("streaming turn discarded"))
        upload.reply.cancel()
        if (sessionId.isNotBlank() && client.connectionState.value == GatewayConnectionState.READY) {
            runCatching { client.sendCancelTurn(upload.segmentId) }
        }
        bridge.cancelStream(upload.segmentId)
    }

    override fun commitStreamingTurn(utteranceId: String) {
        val upload = liveUpload.get()?.takeIf { it.utteranceId == utteranceId } ?: return
        upload.admitted.set(true)
        sendCommitIfReady(upload)
    }

    /** Admission may beat connect/audio_start; persist it and send exactly once after start. */
    private fun sendCommitIfReady(upload: LiveUpload) {
        if (!upload.admitted.get() || !upload.audioStarted.get()) return
        if (sessionId.isBlank() || client.connectionState.value != GatewayConnectionState.READY) return
        if (!upload.commitSent.compareAndSet(false, true)) return
        runCatching { client.sendTurnCommit(upload.segmentId, upload.utteranceId) }
            .onFailure {
                upload.commitSent.set(false)
                Log.w("GatewayCloudRunner", "turn commit send failed", it)
            }
    }

    private suspend fun executeLiveUpload(upload: LiveUpload) {
        val slot = bridge.newReplySlot(upload.segmentId, upload.utteranceId)
        try {
            ensureReady()
            val location = locationProvider()
            client.sendAudioStart(
                sessionId,
                upload.segmentId,
                upload.utteranceId,
                location?.first,
                location?.second,
                navigationSelectionId = upload.navigationSelectionId,
            )
            upload.audioStarted.set(true)
            sendCommitIfReady(upload)
            for (chunk in upload.chunks) client.sendAudioChunk(chunk)
            client.sendAudioEnd(sessionId)
            upload.reply.complete(slot.await())
        } catch (cancelled: CancellationException) {
            if (sessionId.isNotBlank() && client.connectionState.value == GatewayConnectionState.READY) {
                runCatching { client.sendCancelTurn(upload.segmentId) }
            }
            bridge.cancelStream(upload.segmentId)
            upload.reply.cancel(cancelled)
        } catch (error: GatewayRemoteException) {
            if (error.code == "CONNECTION_FAILED" || error.code == "CONNECTION_CLOSED") {
                readyReceived = false
                sessionId = ""
                client.disconnect()
            }
            upload.reply.completeExceptionally(
                if (error.code == "CONNECTION_FAILED" || error.code == "CONNECTION_CLOSED") {
                    CloudUnavailableException("流式云端链路故障：${error.message}", error)
                } else {
                    CloudRequestFailedException("流式云端请求失败（${error.code}）：${error.message}", error)
                },
            )
        } catch (error: GatewayException) {
            readyReceived = false
            sessionId = ""
            client.disconnect()
            upload.reply.completeExceptionally(
                CloudUnavailableException("流式云端链路故障：${error.message}", error),
            )
        } finally {
            bridge.clearReplySlot(slot)
        }
    }

    override suspend fun startRealtimeChat() {
        realtimeChatDesired = true
        connectRealtimeChat()
    }

    private suspend fun connectRealtimeChat() {
        realtimeChatReady = false
        ensureReady()
        client.sendChatStart(sessionId)
        bridge.awaitChatReady()
        realtimeChatReady = true
    }

    override fun appendRealtimeAudio(pcm: ByteArray) {
        if (realtimeChatReady && pcm.isNotEmpty()) client.sendChatAudioChunk(pcm)
    }

    override fun finishRealtimeChat() {
        realtimeChatDesired = false
        realtimeChatReady = false
        if (sessionId.isNotBlank() && client.connectionState.value == GatewayConnectionState.READY) {
            runCatching { client.sendChatFinish(sessionId) }
        }
        bridge.finishChat()
    }

    override suspend fun run(segment: ByteArray): Reply =
        run(segment, utteranceIdProvider())

    override suspend fun run(segment: ByteArray, utteranceId: String): Reply {
        liveUpload.get()?.takeIf { it.utteranceId == utteranceId }?.let { upload ->
            upload.chunks.close()
            return try {
                upload.reply.await()
            } finally {
                liveUpload.compareAndSet(upload, null)
            }
        }
        pendingReplyText.remove(utteranceId)
        releasedReplyTurns.remove(utteranceId)
        // 每轮话语唯一 ID：先于发送注册，reply/error 凭它关联到本话语（丢弃上一轮迟到的消息）
        val segmentId = UUID.randomUUID().toString()
        val navigationSelectionId = navigationSelectionProvider()
        var lastFailure: GatewayException? = null
        for (attempt in 0..1) {
            val replySlot = bridge.newReplySlot(segmentId, utteranceId)
            try {
                ensureReady()
                if (attempt > 0) {
                    onConnectionEvent(TelemetryStages.WS_RECONNECT_OK, "info", emptyMap())
                }
                val location = locationProvider()
                client.sendAudioStart(
                    sessionId,
                    segmentId,
                    utteranceId.takeIf { it.isNotBlank() },
                    location?.first,
                    location?.second,
                    attempt,
                    navigationSelectionId = navigationSelectionId,
                )
                var offset = 0
                while (offset < segment.size) {
                    val end = minOf(offset + CLOUD_CHUNK_BYTES, segment.size)
                    client.sendAudioChunk(segment.copyOfRange(offset, end))
                    offset = end
                }
                client.sendAudioEnd(sessionId)
                return replySlot.await()
            } catch (e: GatewayRemoteException) {
                pendingReplyText.remove(utteranceId)
                if (e.code == "CONNECTION_FAILED" || e.code == "CONNECTION_CLOSED") {
                    // listener 合成的连接错误仍走原有一次重连；其余服务端错误保留健康 WS。
                    readyReceived = false
                    sessionId = ""
                    client.disconnect()
                    if (attempt == 1) {
                        onConnectionEvent(
                            TelemetryStages.WS_RECONNECT_FAILED,
                            "error",
                            mapOf("code" to e.code, "message" to (e.message ?: "unknown")),
                        )
                        onCloudUnavailable()
                        throw CloudUnavailableException("云端链路重试后仍故障：${e.message}", e)
                    }
                    onConnectionEvent(
                        TelemetryStages.WS_RECONNECT_START,
                        "warn",
                        mapOf("code" to e.code, "message" to (e.message ?: "unknown")),
                    )
                    continue
                }
                throw CloudRequestFailedException("云端请求失败（${e.code}）：${e.message}", e)
            } catch (e: GatewayException) {
                lastFailure = e
                readyReceived = false
                sessionId = ""
                client.disconnect()
                pendingReplyText.remove(utteranceId)
                if (attempt == 1) {
                    onConnectionEvent(
                        TelemetryStages.WS_RECONNECT_FAILED,
                        "error",
                        mapOf("message" to (e.message ?: "unknown")),
                    )
                    onCloudUnavailable()
                    throw CloudUnavailableException("云端链路重试后仍故障：${e.message}", e)
                }
                onConnectionEvent(
                    TelemetryStages.WS_RECONNECT_START,
                    "warn",
                    mapOf("message" to (e.message ?: "unknown")),
                )
            } catch (e: CancellationException) {
                releasedReplyTurns.remove(utteranceId)
                pendingReplyText.remove(utteranceId)
                runCatching { client.sendCancelTurn(segmentId) }
                bridge.cancelStream(segmentId)
                throw e
            } finally {
                bridge.clearReplySlot(replySlot)
            }
        }
        throw CloudUnavailableException("云端链路故障：${lastFailure?.message}", lastFailure)
    }

    /**
     * 独立 TTS 播报（A3，TTS 解耦）：发 tts_request 等 tts_response，5s 超时返回 null
     * （调用方静默处理并记失败事件，2026-08-15 起不再有系统 TTS 兜底），**不重试**。
     * ready 未建立时先在同一个 5s 总预算内尝试连接，失败返回 null。
     * 与 [run] 的 reply 槽互不干扰（bridge 独立 tts 槽，各自按 segmentId 对账）。
     */
    override suspend fun request(text: String): AudioReply? =
        request(text, utteranceIdProvider())

    override suspend fun request(text: String, utteranceId: String): AudioReply? {
        val ttsId = UUID.randomUUID().toString()
        val ttsSlot = bridge.newTtsSlot(ttsId, utteranceId)
        return try {
            withTimeoutOrNull(TTS_TIMEOUT_MS) {
                ensureReady()
                // T6：关联当前话语 utteranceId（空串不发送，保持旧协议形态）
                client.sendTtsRequest(text, ttsId, utteranceId.takeIf { it.isNotBlank() })
                ttsSlot.await()
            }
        } catch (e: GatewayException) {
            null // 连接未就绪等发送失败：本次播报直接兜底
        } finally {
            bridge.clearTtsSlot(ttsSlot)
        }
    }
}

/** 独立 TTS 请求超时（A3）：超过即放弃合成音频，本次播报静默（记失败事件）。 */
private const val TTS_TIMEOUT_MS = 5_000L

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
    /** B5：云端 pending 占位信号（LLM 处理中）→ 端侧仲裁器阶段 1 窗口延长。 */
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

    private class PendingSlot<T>(
        val segmentId: String,
        val utteranceId: String,
        val deferred: CompletableDeferred<T>,
    )
    private class ActiveStream(
        val segmentId: String,
        val utteranceId: String,
        val chunks: Channel<ByteArray>,
        val completion: CompletableDeferred<AudioStreamEnd>,
    )

    private val pendingReplies = ConcurrentHashMap<String, PendingSlot<Reply>>()
    private val pendingTts = ConcurrentHashMap<String, PendingSlot<AudioReply>>()
    private val activeStream = AtomicReference<ActiveStream?>(null)
    private val chatReady = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            client.messages.collect { msg -> handle(msg) }
        }
    }

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

    suspend fun awaitChatReady() {
        withTimeoutOrNull(8_000) { chatReady.receive() }
            ?: throw GatewayException("chat_ready timeout")
    }

    fun finishChat() {
        activeStream.getAndSet(null)?.let { stream ->
            val stopped = CancellationException("realtime chat finished")
            stream.chunks.close(stopped)
            stream.completion.completeExceptionally(stopped)
        }
    }

    private fun handle(msg: GatewayMessage) {
        when (msg.type) {
            "decision" -> parseDecision(msg.payload)?.let(sink::onDecision)
            "asr_turn_started" -> {
                val slot = findSlot(msg.payload, pendingReplies) ?: return
                if (!isForCurrentUtterance(msg.payload, slot)) return
                onAsrTurnEstablished(slot.utteranceId)
            }
            "asr_partial" -> {
                if (msg.payload.get("chat")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
                    val text = msg.payload.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: return
                    val final = msg.payload.get("isFinal")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                    onAsrResult(text, final, "")
                    return
                }
                val slot = findSlot(msg.payload, pendingReplies) ?: return
                if (!isForCurrentUtterance(msg.payload, slot)) return
                val text = msg.payload.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: return
                val final = msg.payload.get("isFinal")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                onAsrResult(text, final, slot.utteranceId)
            }
            "reply_partial" -> {
                if (msg.payload.get("chat")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
                    val text = msg.payload.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: return
                    val final = msg.payload.get("isFinal")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                    onReplyText(text, final, "")
                    return
                }
                val slot = findSlot(msg.payload, pendingReplies) ?: return
                if (!isForCurrentUtterance(msg.payload, slot)) return
                val text = msg.payload.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: return
                val final = msg.payload.get("isFinal")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                onReplyText(text, final, slot.utteranceId)
            }
            "reply" -> {
                val slot = findSlot(msg.payload, pendingReplies) ?: return
                if (!isForCurrentUtterance(msg.payload, slot)) return
                client.parseReply(msg.payload)?.let { slot.deferred.complete(it) }
            }
            "audio_reply_start" -> {
                if (msg.payload.get("chat")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
                    val segmentId = msg.payload.get("segmentId")?.asString ?: return
                    val chunks = Channel<ByteArray>(Channel.UNLIMITED)
                    val completion = CompletableDeferred<AudioStreamEnd>()
                    val reply = client.parseAudioStreamStart(msg.payload, chunks, completion) ?: return
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
                if (!isForCurrentUtterance(msg.payload, slot)) return
                val chunks = Channel<ByteArray>(Channel.UNLIMITED)
                val completion = CompletableDeferred<AudioStreamEnd>()
                val reply = client.parseAudioStreamStart(msg.payload, chunks, completion) ?: return
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
                msg.binary?.let { bytes -> stream.chunks.trySend(bytes) }
            }
            "audio_reply_end" -> {
                val stream = activeStream.get() ?: return
                val msgSegmentId = msg.payload.get("segmentId")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: return
                if (msgSegmentId != stream.segmentId) return
                if (activeStream.compareAndSet(stream, null)) {
                    stream.completion.complete(client.parseAudioStreamEnd(msg.payload))
                    stream.chunks.close()
                }
            }
            "chat_ready" -> chatReady.trySend(Unit)
            "chat_speech_started" -> {
                activeStream.getAndSet(null)?.let { stream ->
                    val interrupted = CancellationException("user speech started")
                    stream.chunks.close(interrupted)
                    stream.completion.completeExceptionally(interrupted)
                }
                onChatSpeechStarted()
            }
            "tts_response" -> {
                val slot = findSlot(msg.payload, pendingTts) ?: return
                if (!isForCurrentUtterance(msg.payload, slot)) return
                client.parseTtsResponse(msg.payload)?.let { slot.deferred.complete(it) }
            }
            "error" -> {
                val code = msg.payload.get("code")?.takeIf { it.isJsonPrimitive }?.asString ?: "UNKNOWN"
                val message = msg.payload.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: "网关错误"
                val error = GatewayRemoteException(code, "$message [$code]")
                if (code == "CHAT_STREAM_FAILED" || code == "CHAT_STREAM_CLOSED") {
                    finishChat()
                    onChatFailure()
                    return
                }
                val messageSegment = msg.payload.get("segmentId")
                    ?.takeIf { value -> value.isJsonPrimitive }?.asString
                val affected = if (messageSegment == null) {
                    pendingReplies.values.toList()
                } else {
                    listOfNotNull(pendingReplies[messageSegment])
                }
                affected.forEach { it.deferred.completeExceptionally(error) }
                activeStream.get()?.takeIf {
                    messageSegment == null || messageSegment == it.segmentId
                }?.let { stream ->
                    if (!activeStream.compareAndSet(stream, null)) return@let
                    stream.chunks.close(error)
                    stream.completion.completeExceptionally(error)
                }
            }
            "pending" -> {
                // B5：pending 占位（LLM 处理中，协议 §4.8）——独立于 reply kind 的 S→C
                // 消息：不能走 reply（会 complete replySlot 吞掉 final），只发信号改 UI
                // 状态 + 延长仲裁等待窗口。对账同 reply：segmentId 不一致 → 他轮迟到丢弃；
                // 无槽（无话语在途）→ 丢弃。trySend 幂等缓冲（BUFFERED 通道不挂起）。
                val slot = findSlot(msg.payload, pendingReplies) ?: return
                if (!isForCurrentUtterance(msg.payload, slot)) return
                pendingSignals.trySend(Unit)
                onPendingReceived(slot.utteranceId)
            }
            else -> Unit // ready / bye 当前不消费
        }
    }

    /**
     * 按 segmentId 对账（protocol.md §3.2）：消息携带的 segmentId 与当前话语不一致 → 他轮迟到的
     * 消息，丢弃（Log.d）；未携带（服务端合成错误 / 旧版服务端）→ 无从对账，按当前话语处理。
     */
    private fun isForCurrentUtterance(payload: JsonObject, slot: PendingSlot<*>): Boolean {
        val msgSegmentId = payload.get("segmentId")?.takeIf { it.isJsonPrimitive }?.asString
        if (msgSegmentId == null) return true
        if (msgSegmentId != slot.segmentId) {
            Log.d(GATEWAY_BRIDGE_TAG, "丢弃不属于当前话语的消息（segmentId=$msgSegmentId, 期望=${slot.segmentId}）")
            return false
        }
        return true
    }

    private fun <T> findSlot(
        payload: JsonObject,
        slots: ConcurrentHashMap<String, PendingSlot<T>>,
    ): PendingSlot<T>? {
        val segmentId = payload.get("segmentId")?.takeIf { it.isJsonPrimitive }?.asString
        if (segmentId != null) return slots[segmentId]
        return slots.values.singleOrNull()
    }

    /** 网关 decision 事件 → DecisionEntry（字段缺失则忽略该条，防御）。 */
    private fun parseDecision(payload: JsonObject): DecisionEntry? {
        val arbiter = payload.get("arbiter")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val route = payload.get("route")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val reason = payload.get("reason")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val utteranceId = payload.get("utteranceId")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val timestampMs = payload.get("timestampMs")?.takeIf { it.isJsonPrimitive }?.asLong
            ?: System.currentTimeMillis()
        return DecisionEntry(arbiter, route, reason, utteranceId, timestampMs)
    }
}

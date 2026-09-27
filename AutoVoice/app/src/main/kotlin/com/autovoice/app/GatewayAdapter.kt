package com.autovoice.app

import android.util.Log
import com.autovoice.app.telemetry.TelemetryStages
import com.autovoice.gatewayclient.GatewayClient
import com.autovoice.gatewayclient.GatewayConnectionState
import com.autovoice.gatewayclient.GatewayClientFactory
import com.autovoice.gatewayclient.GatewayConnectionPolicy
import com.autovoice.gatewayclient.GatewayException
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.CloudConfig
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.tts.RealtimePlaybackToken
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.session.CloudRunner
import com.autovoice.voicecore.session.CloudRequestFailedException
import com.autovoice.voicecore.session.CloudUnavailableException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 云端音频分块大小（gateway 协议 16KB/帧）。 */
private const val CLOUD_CHUNK_BYTES = 16_384

/** About four seconds of 16 kHz PCM; overflow fails the turn instead of growing without limit. */
private const val LIVE_UPLOAD_QUEUE_CAPACITY = 128

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
    private val navigationContextProvider: () -> NavigationTaskContextRef? = { null },
) : CloudRunner, TtsRequester, RealtimeChatRunner, StreamingCloudRunner {

    private val client = GatewayClientFactory.create(
        url = cfg.gatewayUrl,
        // M5 鉴权：hello 注入设备凭据（auth-enabled 网关必填；未配置则保持老 hello）
        deviceId = cfg.deviceId,
        authToken = cfg.authToken,
        policy = GatewayConnectionPolicy(
            connectTimeoutMs = 3_000,
            reconnectAttempts = 0, // 断线结束当前轮，不重放音频
        ),
    )
    private val protocol = GatewayProtocolSender(client)
    private val bridge = GatewayBridge(
        client,
        sink,
        scope,
        pendingSignals,
        { turnId -> onPendingReceived(turnId) },
        { text, final, turnId -> onAsrResult(text, final, turnId) },
        { turnId -> onAsrTurnEstablished(turnId) },
        { text, final, turnId -> handleReplyText(text, final, turnId) },
        { reply ->
            if (realtimeChatDesired && realtimeChatReady) {
                onRealtimeReply(
                    RealtimePlaybackToken(realtimeGeneration.get(), UUID.randomUUID().toString()),
                    reply,
                )
            }
        },
        { onRealtimeSpeechStarted() },
        { onRealtimeStreamFailed() },
    )
    private val ttsTransport = GatewayTtsTransport(bridge, protocol, ::ensureReady)

    /** D05b:采用确认上行;ready 前忽略。 */
    fun sendNavigationSelectionStart(context: NavigationTaskContextRef) {
        val sid = sessionId
        if (!readyReceived || sid.isBlank() || client.connectionState.value != GatewayConnectionState.READY) return
        protocol.navigationSelection(sid, context)
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
    private val realtimeGeneration = AtomicLong(0)

    private data class LiveUpload(
        val utteranceId: String,
        val navigationContext: NavigationTaskContextRef?,
        val segmentId: String = UUID.randomUUID().toString(),
        val chunks: Channel<ByteArray> = Channel(LIVE_UPLOAD_QUEUE_CAPACITY),
        val reply: CompletableDeferred<Reply> = CompletableDeferred(),
        val admitted: AtomicBoolean = AtomicBoolean(false),
        val audioStarted: AtomicBoolean = AtomicBoolean(false),
        val commitSent: AtomicBoolean = AtomicBoolean(false),
    )

    private val liveUpload = AtomicReference<LiveUpload?>(null)

    /** 由 [VoiceEngineFactory.create] 在 engine 装配完成后绑定到候选协调器。 */
    var onCloudUnavailable: () -> Unit = {}
    var onNavigationContextMissing: (NavigationTaskContextRef) -> Unit = {}

    private val navigationListener = bridge.register(setOf("navigation_context_result"),
        NavigationContextListener { onNavigationContextMissing(it) })
    // Observe transport loss even when no recognition request is running (e.g. a displayed list).
    private val connectionObserver = observeConnectionLoss(scope, client.connectionState) {
        readyReceived = false
        if (realtimeChatDesired) {
            bridge.finishChat()
            onRealtimeStreamFailed()
        }
        onCloudUnavailable()
    }

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
    var onRealtimeReply: (RealtimePlaybackToken, StreamingAudioReply) -> Unit = { _, _ -> }

    fun isCurrentRealtimeOutput(token: RealtimePlaybackToken): Boolean =
        realtimeChatDesired && realtimeGeneration.get() == token.generation

    @Volatile
    var onRealtimeSpeechStarted: () -> Unit = {}

    private fun onRealtimeStreamFailed() {
        realtimeGeneration.incrementAndGet() // invalidate in-flight replies from the failed stream
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

    /**
     * D15b:服务端明确的恢复结果与候选有效性。
     * sessionState: new / resumed / reset(缺省视为 new,兼容旧服务端)。
     * 客户端**只在 reset 或候选无效时**清理待选列表——正常恢复保留列表。
     */
    var onSessionRecovery: (state: String, candidatesValid: Boolean) -> Unit = { _, _ -> }

    @Volatile
    var onConnectionEvent: (String, String, Map<String, Any?>) -> Unit = { _, _, _ -> }

    /** 时钟同步：委托网关客户端的时钟偏移（ready.serverTime 握手估算，每次握手刷新）。 */
    fun clockOffsetMs(): Long = client.clockOffsetMs()

    /** 释放：断开网关连接（幂等）；引擎 close() 时调用（Task 21 模式切换）。 */
    fun close() {
        connectionObserver.cancel()
        navigationListener.unregister()
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
        val recovery = SessionRecovery.parse(ready.payload)
        onSessionRecovery(recovery.state, recovery.navigationCandidatesValid)
        onReadySessionId(sessionId)
        onConnectionEvent(TelemetryStages.WS_READY, "info", mapOf("sessionId" to sessionId))
    }

    override fun beginStreamingTurn(utteranceId: String) {
        if (!cfg.enabled || utteranceId.isBlank()) return
        val upload = LiveUpload(utteranceId, navigationContextProvider())
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
        if (pcm.isEmpty()) return
        val upload = liveUpload.get() ?: return
        val result = upload.chunks.trySend(pcm.copyOf())
        if (result.isFailure && !result.isClosed) {
            val overflow = CloudRequestFailedException("streaming audio queue overflow")
            upload.chunks.close(overflow)
            upload.reply.completeExceptionally(overflow)
        }
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
            runCatching { protocol.cancelTurn(upload.segmentId) }
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
        runCatching { protocol.commitTurn(upload.segmentId, upload.utteranceId) }
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
            protocol.audioStart(
                sessionId,
                upload.segmentId,
                upload.utteranceId,
                location?.first,
                location?.second,
                navigationContext = upload.navigationContext,
            )
            upload.audioStarted.set(true)
            sendCommitIfReady(upload)
            for (chunk in upload.chunks) protocol.audioChunk(chunk)
            protocol.audioEnd(sessionId)
            upload.reply.complete(slot.await())
        } catch (cancelled: CancellationException) {
            if (sessionId.isNotBlank() && client.connectionState.value == GatewayConnectionState.READY) {
                runCatching { protocol.cancelTurn(upload.segmentId) }
            }
            bridge.cancelStream(upload.segmentId)
            upload.reply.cancel(cancelled)
        } catch (error: GatewayRemoteException) {
            if (error.code == "CONNECTION_FAILED" || error.code == "CONNECTION_CLOSED") {
                readyReceived = false
                sessionId = ""
                client.disconnect()
                onCloudUnavailable()
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
            onCloudUnavailable()
            upload.reply.completeExceptionally(
                CloudUnavailableException("流式云端链路故障：${error.message}", error),
            )
        } catch (error: CloudRequestFailedException) {
            upload.reply.completeExceptionally(error)
        } finally {
            bridge.clearReplySlot(slot)
        }
    }

    override suspend fun startRealtimeChat() {
        realtimeChatDesired = true
        connectRealtimeChat()
    }

    private suspend fun connectRealtimeChat() {
        realtimeGeneration.incrementAndGet()
        realtimeChatReady = false
        ensureReady()
        protocol.chatStart(sessionId)
        bridge.awaitChatReady()
        realtimeChatReady = true
    }

    override fun appendRealtimeAudio(pcm: ByteArray) {
        if (realtimeChatReady && pcm.isNotEmpty()) protocol.chatAudio(pcm)
    }

    override fun finishRealtimeChat() {
        realtimeGeneration.incrementAndGet()
        realtimeChatDesired = false
        realtimeChatReady = false
        if (sessionId.isNotBlank() && client.connectionState.value == GatewayConnectionState.READY) {
            runCatching { protocol.chatFinish(sessionId) }
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
        val navigationContext = navigationContextProvider()
        val replySlot = bridge.newReplySlot(segmentId, utteranceId)
        try {
            // ensureReady may establish a connection before this turn starts. Once audio_start has
            // been attempted, a transport failure ends the turn; its PCM is never retransmitted.
            ensureReady()
            val location = locationProvider()
            protocol.audioStart(
                sessionId,
                segmentId,
                utteranceId.takeIf { it.isNotBlank() },
                location?.first,
                location?.second,
                0,
                navigationContext = navigationContext,
            )
            var offset = 0
            while (offset < segment.size) {
                val end = minOf(offset + CLOUD_CHUNK_BYTES, segment.size)
                protocol.audioChunk(segment.copyOfRange(offset, end))
                offset = end
            }
            protocol.audioEnd(sessionId)
            return replySlot.await()
        } catch (e: GatewayRemoteException) {
            pendingReplyText.remove(utteranceId)
            if (e.code == "CONNECTION_FAILED" || e.code == "CONNECTION_CLOSED") {
                readyReceived = false
                sessionId = ""
                client.disconnect()
                onConnectionEvent(
                    TelemetryStages.WS_RECONNECT_FAILED,
                    "error",
                    mapOf("code" to e.code, "message" to (e.message ?: "unknown"),
                        "turnRetried" to false),
                )
                onCloudUnavailable()
                throw CloudUnavailableException("云端链路故障，本轮已结束：${e.message}", e)
            }
            throw CloudRequestFailedException("云端请求失败（${e.code}）：${e.message}", e)
        } catch (e: GatewayException) {
            readyReceived = false
            sessionId = ""
            client.disconnect()
            pendingReplyText.remove(utteranceId)
            onConnectionEvent(
                TelemetryStages.WS_RECONNECT_FAILED,
                "error",
                mapOf("message" to (e.message ?: "unknown"), "turnRetried" to false),
            )
            onCloudUnavailable()
            throw CloudUnavailableException("云端链路故障，本轮已结束：${e.message}", e)
        } catch (e: CancellationException) {
            releasedReplyTurns.remove(utteranceId)
            pendingReplyText.remove(utteranceId)
            runCatching { protocol.cancelTurn(segmentId) }
            bridge.cancelStream(segmentId)
            throw e
        } finally {
            bridge.clearReplySlot(replySlot)
        }
    }

    /**
     * 独立 TTS 播报（A3，TTS 解耦）：发 tts_request 等 tts_response，5s 超时返回 null
     * （调用方静默处理并记失败事件，2026-08-15 起不再有系统 TTS 兜底），**不重试**。
     * ready 未建立时先在同一个 5s 总预算内尝试连接，失败返回 null。
     * 与 [run] 的 reply 槽互不干扰（bridge 独立 tts 槽，各自按 segmentId 对账）。
     */
    override suspend fun request(text: String): AudioReply? =
        request(text, utteranceIdProvider())

    override suspend fun request(text: String, utteranceId: String): AudioReply? =
        ttsTransport.request(text, utteranceId)
}

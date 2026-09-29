package com.autovoice.app
import com.autovoice.voiceengine.cloud.CloudAsrEngine
import com.autovoice.voiceengine.cloud.CloudNluEngine
import com.autovoice.voiceengine.RecognitionGate
import com.autovoice.voiceengine.AsrModule
import com.autovoice.voiceengine.NluModule
import com.autovoice.voicebusiness.navigation.NavigationTaskContextRef

import android.util.Log
import com.autovoice.app.telemetry.TelemetryStages
import com.autovoice.gatewayclient.GatewayClient
import com.autovoice.gatewayclient.GatewayConnectionState
import com.autovoice.gatewayclient.GatewayClientFactory
import com.autovoice.gatewayclient.GatewayConnectionPolicy
import com.autovoice.gatewayclient.GatewayException
import com.autovoice.gatewayclient.GatewayPayloadParser
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.AsrResult
import com.autovoice.voicecore.AsrSink
import com.autovoice.voicecore.CloudConfig
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.tts.RealtimePlaybackToken
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.session.CloudRunner
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * VoiceEngine's cloud speech route backed by a shared gateway transport. It owns the cloud
 * ASR/NLU modules; GatewayBridge only dispatches typed frames and correlates request slots.
 * Ordinary PCM upload, realtime chat, navigation context and TTS have independent owners.
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
    private val recognitionGate: RecognitionGate,
    private val asrModule: AsrModule,
    private val nluModule: NluModule,
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
    private val bridge: GatewayBridge = GatewayBridge(
        client,
        scope,
        { reply -> realtimeChatChannel.onReply(reply) },
        { onRealtimeSpeechStarted() },
        { realtimeChatChannel.onStreamFailed() },
    )
    /** Voice-owned ASR/NLU modules subscribe to the gateway's typed message dispatcher. */
    private val cloudAsr = CloudAsrEngine(
        bridge,
        { text, final, turnId -> onAsrResult(text, final, turnId) },
        { turnId -> onAsrTurnEstablished(turnId) },
    )
    private val cloudNlu = CloudNluEngine(
        bridge,
        GatewayPayloadParser(),
        sink,
        pendingSignals,
        { turnId -> onPendingReceived(turnId) },
        { text, final, turnId -> handleReplyText(text, final, turnId) },
        { segment, turnId -> businessSpeech.run(segment, turnId) },
    )
    private val speechRegistrations = listOf(cloudAsr.register(), cloudNlu.register())
    init {
        asrModule.bindCloud(cloudAsr, cloudAsr::release, cloudAsr::close)
        nluModule.bindCloud(cloudNlu)
    }
    private val realtimeChatChannel: GatewayRealtimeChatChannel by lazy {
        GatewayRealtimeChatChannel(client, bridge, protocol, scope, ::ensureReady, { sessionId }) {
                token, reply -> onRealtimeReply(token, reply)
        }
    }
    private val ttsTransport = GatewayTtsTransport(bridge, protocol, ::ensureReady)
    private val navigationChannel = GatewayNavigationContextChannel(
        bridge, protocol, client, { readyReceived }, { sessionId }, { onNavigationContextMissing(it) },
    )
    private val businessSpeech = GatewayBusinessSpeechChannel(
        client, bridge, protocol, scope, ::ensureReady, { sessionId },
        locationProvider, navigationContextProvider, ::onSpeechTransportFailure,
        { turnId ->
            pendingReplyText.remove(turnId)
            releasedReplyTurns.remove(turnId)
        },
        recognitionGate,
    )

    /** D05b:采用确认上行;ready 前忽略。 */
    fun sendNavigationSelectionStart(context: NavigationTaskContextRef) = navigationChannel.publish(context)

    private data class ReplyTextSnapshot(val text: String, val isFinal: Boolean)

    private val pendingReplyText = ConcurrentHashMap<String, ReplyTextSnapshot>()
    private val releasedReplyTurns = ConcurrentHashMap.newKeySet<String>()

    /** 是否已收到 ready（含 sessionId）——据此区分 ready 前/后故障。 */
    @Volatile
    private var readyReceived = false

    @Volatile
    private var sessionId = ""

    /** 由 [VoiceEngineFactory.create] 在 engine 装配完成后绑定到候选协调器。 */
    var onCloudUnavailable: () -> Unit = {}
    var onNavigationContextMissing: (NavigationTaskContextRef) -> Unit = {}

    // Observe transport loss even when no recognition request is running (e.g. a displayed list).
    private val connectionObserver = observeConnectionLoss(scope, client.connectionState) {
        readyReceived = false
        realtimeChatChannel.onTransportLost()
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
        realtimeChatChannel.isCurrentOutput(token)

    @Volatile
    var onRealtimeSpeechStarted: () -> Unit = {}

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
    var utteranceIdProvider: () -> String
        get() = businessSpeech.utteranceIdProvider
        set(value) { businessSpeech.utteranceIdProvider = value }

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
        speechRegistrations.forEach { it.unregister() }
        asrModule.closeCloud()
        navigationChannel.close()
        businessSpeech.close()
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
        if (cfg.enabled) {
            asrModule.recognizeCloud(utteranceId, byteArrayOf(), asrSink(utteranceId))
            businessSpeech.beginStreamingTurn(utteranceId)
        }
    }

    override fun appendStreamingAudio(pcm: ByteArray) = businessSpeech.appendStreamingAudio(pcm)
    override fun finishStreamingTurn(utteranceId: String) = businessSpeech.finishStreamingTurn(utteranceId)
    override fun cancelStreamingTurn(utteranceId: String) {
        businessSpeech.cancelStreamingTurn(utteranceId)
        asrModule.releaseCloud(utteranceId)
    }
    override fun stopUnfinalizedStreamingTurn(utteranceId: String) {
        if (!businessSpeech.isInputFinalized(utteranceId)) cancelStreamingTurn(utteranceId)
    }
    override fun commitStreamingTurn(utteranceId: String) = businessSpeech.commitStreamingTurn(utteranceId)

    private fun onSpeechTransportFailure(error: Throwable, streaming: Boolean) {
        readyReceived = false
        sessionId = ""
        client.disconnect()
        if (!streaming) {
            val details = mutableMapOf<String, Any?>(
                "message" to (error.message ?: "unknown"), "turnRetried" to false,
            )
            if (error is GatewayRemoteException) details["code"] = error.code
            onConnectionEvent(TelemetryStages.WS_RECONNECT_FAILED, "error", details)
        }
        onCloudUnavailable()
    }

    override suspend fun startRealtimeChat() = realtimeChatChannel.startRealtimeChat()

    override fun appendRealtimeAudio(pcm: ByteArray) = realtimeChatChannel.appendRealtimeAudio(pcm)

    override fun finishRealtimeChat() = realtimeChatChannel.finishRealtimeChat()

    override suspend fun run(segment: ByteArray): Reply = run(segment, utteranceIdProvider())

    override suspend fun run(segment: ByteArray, utteranceId: String): Reply {
        asrModule.recognizeCloud(utteranceId, segment, asrSink(utteranceId))
        return try {
            nluModule.understandCloud(utteranceId, segment, null)
        } finally {
            asrModule.releaseCloud(utteranceId)
        }
    }

    private fun asrSink(turnId: String): AsrSink = object : AsrSink {
        override fun onTranscript(result: AsrResult) {
            onAsrResult(result.text, result.isFinal, turnId)
        }

        override fun onTurnEstablished() {
            onAsrTurnEstablished(turnId)
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

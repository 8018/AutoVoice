package com.autovoice.app

import android.util.Log
import com.autovoice.app.audio.TtsCache
import com.autovoice.app.telemetry.TelemetryClient
import com.autovoice.app.telemetry.TelemetryStages
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.arbiter.OnDeviceRaceArbiter
import com.autovoice.voicecore.arbiter.RaceWinner
import com.autovoice.voicecore.dialog.AdmissionEvidence
import com.autovoice.voicecore.dialog.ConversationController
import com.autovoice.voicecore.dialog.DialogueSnapshot
import com.autovoice.voicecore.session.CloudRunner
import com.autovoice.voicecore.session.LocalChainRunner
import com.autovoice.voicecore.session.ResultListener
import com.autovoice.voicecore.session.SessionState
import com.autovoice.voicecore.session.VoiceSession
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** 云端音频回复播放出口（应用层实现：TtsPlayer）。JVM 测试可注入 fake。 */
fun interface AudioPlayer {
    fun play(reply: AudioReply)
    fun play(reply: AudioReply, identity: PlaybackIdentity) = play(reply)
    suspend fun playStream(reply: StreamingAudioReply, identity: PlaybackIdentity) = playStream(reply)

    /** 停止当前播放；只中断输出，不取消端侧/云端候选计算。 */
    fun stop() = Unit

    /** 默认实现累积后复用完整音频播放器；生产 TtsPlayer 覆盖为 AudioTrack 边收边播。 */
    suspend fun playStream(reply: StreamingAudioReply) {
        val pcm = ByteArrayOutputStream()
        for (chunk in reply.chunks) pcm.write(chunk)
        val end = reply.completion.await()
        play(AudioReply("audio/pcm", pcm.toByteArray(), end.speakText, end.intent, end.asrText))
    }
}

/**
 * 端侧全局装配点（Task 20）：双链路竞速引擎 + 播报/执行路由。
 *
 * 持有装配好的 [VoiceSession]（本地链 + 云端链 + [OnDeviceRaceArbiter]，见 voice-core
 * §5.1 编排语义）与两个出口：[player]（音频播放）、[vehicle]（车控执行）。
 * 播报统一走网络 TTS（2026-08-15：不用系统 TTS）。结果路由与播放生命周期
 * 分别由 [ResponseDispatcher] 和 [SpeechOutputService] 管理：
 *  - [RaceWinner.Cloud]：AudioReply → 播放 + 附 intent 执行；TextReply → 播报；
 *    ActionReply → 执行 intent + 播报自带 speakText；
 *  - [RaceWinner.Local]：`vehicle.apply(intent)` 成功 → 播报其返回文本（未知意图不播报）；
 *  - [RaceWinner.Failed]：播报统一兜底话术（按钮录音模式下全败需要明确反馈）。
 *
 * 弱网调试 hook（仅 debug 构建暴露）：[weakNetwork] 为 true 时云端链启动前人为 delay 3000ms，
 * 云端赶不上 cloudWaitMs → 仲裁回落到本地（reason `cloud_timeout_use_local`）。
 *
 * 生产装配走 [VoiceEngineFactory.create]（真实本地链 + GatewayClient 云端链 + ConnectivityManager 网络检查）；
 * 构造器直接注入链/仲裁器/出口（JVM 测试用 fake）。
 */
class VoiceEngine(
    cfg: DemoConfig,
    arbiter: OnDeviceRaceArbiter,
    sink: DecisionSink,
    /** D05b:导航候选采用确认上行(由工厂装配到云端连接)。 */
    var navigationAdoptionSender: (String) -> Unit = {},
    /** D07b 客户端执行网关(最终准入/原子抢占/幂等);默认直通,生产由工厂注入真实账本。 */
    private val actionGateway: com.autovoice.app.action.ActionExecutionGateway =
        com.autovoice.app.action.ActionExecutionGateway(
            object : com.autovoice.app.action.ActionLedgerStore {
                override fun claim(actionId: String, summary: String) = true
                override fun markTerminal(actionId: String, state: String) {}
                override fun stateOf(actionId: String): String? = null
                override fun recoverUnknowns() {}
            }),
    /**
     * 链路数据上报客户端（T6）：生产装配由 [VoiceEngineFactory.create] 注入（telemetry 未配置 → enabled=false
     * 的全 no-op 实例）；JVM 测试不传时用默认 disabled 实例，行为不变。
     */
    private val telemetry: TelemetryClient = TelemetryClient(
        okHttp = OkHttpClient(),
        baseUrl = "",
        deviceId = null,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        enabled = false,
    ),
    private val networkAvailable: () -> Boolean,
    local: LocalChainRunner,
    cloud: CloudRunner,
    private val tts: TtsRequester,
    private val player: AudioPlayer,
    /**
     * 端侧 TTS 缓存（架构变更：缓存从服务器移回端侧）：播报服务先查缓存，
     * 命中直接播（不请求服务器）；未命中走 [tts] 网络合成，回传写缓存再播。
     * 默认仅内存（JVM 测试注入预置缓存/fake）；生产装配由 [VoiceEngineFactory.create] 注入。
     */
    private val ttsCache: TtsCache = TtsCache(null),
    val vehicle: MockVehicleState,
    /**
     * 导航执行器（spec §4.2）：navigation/navigate 意图不走 vehicle，转高德 URI 拉起高德 App。
     * null（测试/未装配）时导航意图记 skipped。
     */
    private val navigation: NavigationExecutor? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onVehicleApplied: () -> Unit = {},
    /** 本地 ASR 识别文本回调（Task 34：UI 显示识别结果，未检出时为 null）。 */
    private val onLocalRecognized: (String?) -> Unit = {},
    /** 模型回答文本（流式 partial 的最终兜底）。 */
    private val onReplyText: (String) -> Unit = {},
    private val debugBuild: Boolean = BuildConfig.DEBUG,
    /** 释放钩子（生产装配注册网关断开；Task 21 模式切换）。 */
    private val onClose: () -> Unit = {},
    /** 应用回到前台时预热云端连接；真正发送前仍会再次 ensureReady。 */
    private val onForeground: () -> Unit = {},
    /** 新轮一经开始录音就通知云端桥，便于拦截旧轮迟到字幕/流。 */
    private val onTurnStarted: (String) -> Unit = {},
    /**
     * B5：云端 pending 占位回调（LLM 处理中，协议 §4.8）：收到 pending 帧 → true，
     * 最终语义到达 / 新一轮开始 → false。UI 据此显示"处理中…"徽标，无执行无播报。
     */
    private val onCloudPending: (Boolean) -> Unit = {},
    /** 云端语义通过端侧仲裁后释放其回复字幕；ASR 文本不受此门控。 */
    private val onCloudWon: (String) -> Unit = {},
    /** 服务端混合后端下发的闲聊锁域控制。 */
    private val onConversationMode: (Boolean) -> Unit = {},
    /** 本地交互状态；只由 ConversationController 产生，ASR/NLU/仲裁器不直接修改 UI 状态。 */
    private val onDialogueState: (DialogueSnapshot) -> Unit = {},
    /** 已通过播放身份校验的生命周期事件；驱动层迟到回调不会触发此钩子。 */
    private val onPlaybackStage: (PlaybackStage) -> Unit = {},
    private val streamingCloud: StreamingCloudRunner? = null,
) {
    private val realtimeChat = cloud as? RealtimeChatRunner

    /** 弱网调试 hook（调试构建的 UI 开关）：true 且 [debugBuild] 时云端链人为延迟 [WEAK_NETWORK_DELAY_MS]。 */
    @Volatile
    var weakNetwork: Boolean = false

    /**
     * 当前话语的链路追踪 ID（T6）：由录音开始（[onListeningStart]）建立 captureId，
     * VAD/ASR/NLU 与网关共用该 id，单一 id 贯穿本轮全部插桩
     * （audio_start/tts_request 上行 + telemetry 事件）；云端链在 IO 线程读取，
     * volatile 可见。无 VAD 场景（vadUnavailable）由 [onTurnSegment] 兜底产生。
     */
    private val currentUtteranceId: String get() = conversation.captureId

    /**
     * TTS 网络播放事件入口（T7）：TtsPlayer（MainViewModel 装配）的 onPlayEvent 接到这里，
     * 构造时（init）已绑定 telemetry.recordFor（T7 评审 C1，见下）——播放线程/主线程回调
     * 内部 @Synchronized 串行；enabled=false 时 recordFor no-op，零影响。
     */
    val onTtsPlayEvent: (stage: String, level: String, payload: Map<String, Any?>) -> Unit

    /** 仅用于合成缓存遥测和准入停播判断；真实播放事件归属由 PlaybackIdentity 固定。 */
    @Volatile
    private var playUtteranceId = ""

    /** 本轮云端 VAD 段计数（T7 vad 聚合统计；onListeningStart 清零，onCloudSegment 累加）。 */
    private var turnSegmentCount = 0

    /** 本轮云端 VAD 段总时长 ms（T7 vad 聚合统计；16k 单声道 16bit，bytes/32 = ms）。 */
    private var turnSegmentsTotalMs = 0L

    /** 新轮 SpeechStart 到达前忽略上一轮迟到的 SpeechEnd。 */
    @Volatile
    private var awaitingVadStart = true

    /** 装配好的会话：状态机 + 双路由竞速编排。 */
    val session: VoiceSession

    /** Capture 准入、pending 可见性和当前 turn 的唯一拥有者。 */
    val conversation: ConversationController = ConversationController(
        onState = onDialogueState,
        onTurnAdmitted = { admitted ->
            if (playUtteranceId.isNotBlank() && playUtteranceId != admitted.turnId) {
                playbackCoordinator.stop()
            }
            session.currentUtteranceId = admitted.turnId
            streamingCloud?.commitStreamingTurn(admitted.turnId)
        },
        onPendingVisible = onCloudPending,
    )

    private val playbackCoordinator: PlaybackCoordinator = PlaybackCoordinator(player) { identity, kind, level, payload ->
        val stage = when (kind) {
            PlaybackStage.STARTED -> TelemetryStages.TTS_PLAY_START
            PlaybackStage.INTERRUPTED -> TelemetryStages.TTS_PLAY_INTERRUPTED
            else -> TelemetryStages.TTS_PLAY_END
        }
        telemetry.recordFor(identity.turnId, stage, level,
            payload + mapOf("source" to "network", "event" to kind.wire))
        onPlaybackStage(kind)
        // Realtime playback has its own identity but never advances the ordinary dialogue.
        if (identity.turnId.isNotBlank()) when (kind) {
            PlaybackStage.STARTED -> conversation.onPlaybackStarted(identity.turnId)
            PlaybackStage.COMPLETED, PlaybackStage.FAILED -> conversation.onPlaybackEnded(identity.turnId)
            PlaybackStage.INTERRUPTED -> Unit
        }
    }

    private val speechOutput = SpeechOutputService(
        tts = tts,
        cache = ttsCache,
        playback = playbackCoordinator,
        telemetry = telemetry,
        scope = scope,
        isCurrentTurn = ::isLatestTurn,
        onEmptyOutput = conversation::onPlaybackEnded,
    )

    private val responses = ResponseDispatcher(
        output = speechOutput,
        vehicle = vehicle,
        navigation = navigation,
        telemetry = telemetry,
        isCurrentTurn = ::isLatestTurn,
        onVehicleApplied = onVehicleApplied,
        onRecognized = onLocalRecognized,
        onReplyText = onReplyText,
        onConversationMode = onConversationMode,
        actionGateway = actionGateway,
    )

    init {
        onTtsPlayEvent = playbackCoordinator::accept
        session = VoiceSession(
            cfg = cfg,
            arbiter = arbiter,
            sink = sink,
            local = local,
            cloud = object : CloudRunner {
                override suspend fun run(segment: ByteArray): Reply = run(segment, currentUtteranceId)

                override suspend fun run(segment: ByteArray, utteranceId: String): Reply {
                    // 弱网调试（仅 debug 构建）：云端链启动前人为延迟，让云端错过 cloudWaitMs
                    if (weakNetwork && debugBuild) delay(WEAK_NETWORK_DELAY_MS)
                    return cloud.run(segment, utteranceId)
                }
            },
            scope = scope,
            resultListener = ResultListener { utteranceId, winner -> onTurnResult(utteranceId, winner) },
        )
    }

    /**
     * 释放引擎（Task 21 模式切换 / ViewModel 销毁）：断开网关连接（[onClose] 钩子）
     * + 取消引擎协程作用域（在途竞速、网关事件桥收集全部终止）。幂等；关闭后
     * [session] 不再产生状态回调/结果路由，装配时传入的 [scope] 不可复用
     * （生产装配每次重建引擎时新建专属 scope，不复用 viewModelScope）。
     */
    fun close() {
        runCatching { onClose() }.onFailure { Log.w(TAG, "引擎释放钩子失败", it) }
        playbackCoordinator.stop()
        session.close()
        scope.cancel()
    }

    /** 统一停止当前输出；用于 realtime 对端确认用户开始说话等非普通新轮入口。 */
    fun stopPlayback() = playbackCoordinator.stop()

    fun onForeground() = onForeground.invoke()

    fun onWake() { conversation.onWake() }

    fun onFollowUpExpired(interactionId: String) { conversation.onFollowUpExpired(interactionId) }

    fun resetDialogue() { conversation.reset() }

    // ------------------------------------------------------------------ 话语入口（MainViewModel 接线）

    /**
     * 录音开始：建立新的 captureId，但不抢占状态机的当前 turn；网络可用则重新启用云端路由（断网恢复
     * 场景），否则立即挂起云端（本轮起只跑本地链，reason `cloud_unreachable`），
     * 再进入 LISTENING。
     */
    fun onListeningStart(interruptPlayback: Boolean = true) {
        // 先建立 captureId 用于链路关联；只有 ASR/有效语义证据才能把它晋升为当前 turn。
        val captureId = conversation.beginCapture()
        // captureId 先用于链路关联；尚未得到语音证据时不替换状态机当前 turnId。
        onTurnStarted(captureId)
        telemetry.begin(captureId)
        telemetry.record(TelemetryStages.UTTERANCE_START, "info", mapOf("source" to "recording_start"))
        // 明确的新轮立即停播；开放式 VAD 候选由 ASR/NLU 在 confirmTurn 中停播。
        if (interruptPlayback) playbackCoordinator.stop()
        // T7 vad 聚合统计：本轮从零开始
        turnSegmentCount = 0
        turnSegmentsTotalMs = 0L
        awaitingVadStart = true
        // activeNetwork 只能作诊断提示，不能作为硬门禁：网络切换期间它可能短暂为 null，
        // WebSocket 的真实 connect/send 结果才是云端是否可用的权威信号。
        if (!networkAvailable()) Log.w(TAG, "activeNetwork unavailable; probing gateway directly")
        session.onCloudAvailable()
        session.onListeningStart(captureId)
    }

    /**
     * B5：云端 pending 占位状态（true = LLM 处理中，仅 UI 状态；false = 已清除）。
     * 由 [GatewayCloudRunner.onPendingReceived]（收到 pending 帧）与
     * [onTurnResult]（最终语义到达）调用。
     */
    fun setCloudPending(v: Boolean) = setCloudPending(currentUtteranceId, v)

    fun setCloudPending(turnId: String, v: Boolean) = conversation.setPending(turnId, v)

    /**
     * VAD 语音段开始（录音实时，SpeechStart 触发，需求 2）：
     *  - 打开由录音开始预先建立的 capture，但不改变对话状态；
     *  - 会话与云端链共用该 id；
     *  - 同轮后续段：不重复产生，只记 vad_start。
     * 守卫：非录音中（LISTENING）的杂散 SpeechStart 忽略。
     */
    fun onVadStart() {
        if (session.state.value != SessionState.LISTENING) return
        awaitingVadStart = false
        conversation.openCapture(currentUtteranceId)
        telemetry.record(TelemetryStages.VAD_START, "info", emptyMap())
        streamingCloud?.beginStreamingTurn(currentUtteranceId)
    }

    /**
     * VAD 语音段结束（录音实时，SpeechEnd 触发，需求 2）：记 vad_end 事件（与
     * [onVadStart] 配对；无话语时静默跳过——防杂散事件）。
     */
    fun onVadEnd() {
        if (currentUtteranceId.isBlank() || awaitingVadStart) return
        telemetry.record(TelemetryStages.VAD_END, "info", emptyMap())
    }

    fun appendStreamingCloudAudio(pcm: ByteArray) {
        if (pcm.isNotEmpty()) streamingCloud?.appendStreamingAudio(pcm)
    }

    /** 按钮抬手时 VAD 可能尚未产生 SpeechEnd，显式收口，幂等。 */
    fun finishStreamingCloudAudio() {
        streamingCloud?.finishStreamingTurn(currentUtteranceId)
    }

    /** VAD 误报/过短录音不形成业务轮时，撤销已经预先打开的实时上行。 */
    fun cancelStreamingCloudAudio() {
        streamingCloud?.cancelStreamingTurn(currentUtteranceId)
    }

    /**
     * 云端路段（Task 49 双路：VAD 切出的语音段，按住期间每切出一段喂一段，
     * 0..n 个）：入队串行上云，回复挂在会话的云端收敛点。
     * 必须在 [onTurnSegment] 之前全部喂完（会话防御丢弃倒序段）。
     * T7：累加本轮 VAD 聚合统计（段数/总时长，随 onTurnSegment 的 vad 事件上报）。
     */
    fun onCloudSegment(segment: ByteArray) {
        if (session.state.value == SessionState.LISTENING) {
            turnSegmentCount += 1
            turnSegmentsTotalMs += durationMs(segment.size)
        }
        session.onCloudSegment(segment)
    }

    /**
     * 本地路整段音频（Task 49 双路：抬手后完整降噪段）：无录音开始事件的直接输入场景
     * 兜底产生 captureId 并开启 telemetry 轮（首个 SpeechStart 缺席，
     * 否则本轮事件全丢）；再记录 VAD 事件（含本轮聚合统计：段数/总时长；maxProb 在
     * VadSegmenter 内、AudioRecorder 持有，此处不可得）+ 上传 VAD 后 PCM 到数据平台
     * （T6），最后启动双路竞速收敛。
    */
    fun onTurnSegment(segment: ByteArray) {
        val hadCapture = currentUtteranceId.isNotBlank()
        val captureId = conversation.ensureOpenCapture()
        if (!hadCapture) {
            telemetry.begin(captureId)
            telemetry.record(TelemetryStages.UTTERANCE_START, "info", mapOf("source" to "button"))
        }
        telemetry.record(
            TelemetryStages.VAD,
            "info",
            mapOf(
                "bytes" to segment.size,
                "durationMs" to durationMs(segment.size),
                // 聚合：云端段数 + 本地整段 = 本轮 VAD 切段总数；总时长为云端段 + 本地段之和
                "segmentCount" to turnSegmentCount + 1,
                "totalMs" to turnSegmentsTotalMs + durationMs(segment.size),
            ),
        )
        telemetry.uploadAudio(captureId, segment)
        session.onTurnSegment(segment, captureId)
    }

    /** 进入闲聊域后建立 Realtime 会话；麦克风数据由 [appendRealtimeChatAudio] 连续上送。 */
    fun startRealtimeChat() {
        scope.launch {
            runCatching { realtimeChat?.startRealtimeChat() }
                .onFailure { Log.w("VoiceEngine", "start realtime chat failed", it) }
        }
    }

    fun appendRealtimeChatAudio(pcm: ByteArray) {
        runCatching { realtimeChat?.appendRealtimeAudio(pcm) }
            .onFailure { Log.w("VoiceEngine", "append realtime audio failed", it) }
    }

    fun finishRealtimeChat() {
        realtimeChat?.finishRealtimeChat()
    }

    internal fun playRealtimeChatReply(reply: StreamingAudioReply) {
        responses.dispatchRealtime(reply)
    }

    /** 16k 单声道 16bit PCM 字节数 → 毫秒（与 AudioRecorder/TtsPlayer 同口径：32000B/s）。 */
    private fun durationMs(bytes: Int): Long = bytes * 1000L / 32_000

    /** 录音中止（用户抬手/放弃）：回 IDLE；进行中的竞速不受影响（会话防御）。 */
    fun onListeningStop() {
        conversation.rejectCapture(currentUtteranceId)
        session.onListeningStop()
    }

    // ------------------------------------------------------------------ 结果路由

    /** ASR 文本只更新识别框；不得从文本内容、partial/final 推断新轮。 */
    internal fun onRecognized(turnId: String, text: String) {
        if (text.isBlank()) return
        if (turnId.isBlank()) {
            onLocalRecognized(text)
            return
        }
        if (conversation.isVisible(turnId)) onLocalRecognized(text)
    }

    /** ASR/AEC 独立确认新话语；状态机只消费该事件，不检查识别文本。 */
    internal fun onAsrTurnEstablished(turnId: String, evidence: AdmissionEvidence) {
        if (turnId.isNotBlank()) conversation.confirmTurn(turnId, evidence)
    }

    private fun onTurnResult(utteranceId: String, winner: RaceWinner) {
        // 仲裁器只保证“该 turn 尚未输出过语义”。这里才判断是否为状态机当前轮。
        when (winner) {
            is RaceWinner.Cloud -> conversation.confirmTurn(
                utteranceId,
                AdmissionEvidence.CLOUD_FINAL_SEMANTIC,
            )
            is RaceWinner.Local -> conversation.confirmTurn(
                utteranceId,
                AdmissionEvidence.LOCAL_SEMANTIC,
            )
            is RaceWinner.Failed -> conversation.rejectCapture(utteranceId)
            is RaceWinner.Intercepted -> Unit
        }
        if (!conversation.isCurrentTurn(utteranceId)) {
            telemetry.end(utteranceId)
            return
        }
        conversation.onFinalSemantic(utteranceId)
        // T7 评审 C1：本轮所有播报由此发起，
        // 先快照 utteranceId——播放的异步结果回调在 end() 收包之后才到，凭快照归属本轮
        playUtteranceId = utteranceId
        when (winner) {
            is RaceWinner.Cloud -> {
                onCloudWon(utteranceId)
                responses.dispatchCloud(utteranceId, winner.reply)
            }
            is RaceWinner.Local -> responses.dispatchLocal(utteranceId, winner.nlu)
            // 全败：播报兜底话术（按钮录音模式下需要明确反馈；2026-08-15 起同样走网络 TTS，
            // 不再用系统 TTS；决策日志已记录失败原因 cloud_timeout_use_local / both_failed 等）
            is RaceWinner.Failed -> responses.dispatchFailure(utteranceId)
            // 该 turn 已在仲裁层输出过语义：不重复播报或执行。
            is RaceWinner.Intercepted -> Unit
        }
        // B5：最终语义到达（任一收敛结果）→ 清除"处理中"占位状态
        setCloudPending(utteranceId, false)
        // T6：每轮结束收包（事件已按当前 utterance 聚合完毕）
        telemetry.end(utteranceId)
    }

    private fun isLatestTurn(utteranceId: String): Boolean =
        utteranceId.isBlank() || conversation.isCurrentTurn(utteranceId)

    companion object {
        private const val TAG = "VoiceEngine"

        /** 弱网调试 hook 的云端人为延迟（晚于 cloudWaitMs 即本地赢）。 */
        private const val WEAK_NETWORK_DELAY_MS = 3_000L


    }
}

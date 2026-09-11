package com.autovoice.app

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.autovoice.adapteriflytek.FakeCommandAsrProvider
import com.autovoice.adapteriflytek.IflytekOfflineCommandAsrStage
import com.autovoice.adapteriflytek.RuleNluProvider
import com.autovoice.app.audio.TtsCache
import com.autovoice.app.telemetry.TelemetryClient
import com.autovoice.app.telemetry.TelemetryStages
import com.autovoice.voicecore.AsrResult
import com.autovoice.voicecore.AsrSink
import com.autovoice.voicecore.AsrStage
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.NluResult
import com.autovoice.voicecore.NluStage
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.arbiter.OnDeviceArbiterEvent
import com.autovoice.voicecore.arbiter.OnDeviceRaceArbiter
import com.autovoice.voicecore.arbiter.PendingSignalRegistry
import com.autovoice.voicecore.dialog.AdmissionEvidence
import com.autovoice.voicecore.dialog.DialogueSnapshot
import com.autovoice.voicecore.session.LocalChainRunner
import com.autovoice.voicecore.validateForRuntime
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

private const val FACTORY_TAG = "VoiceEngineFactory"

/** 本地兜底超时：云端超时后等待本地链的最长时间。 */
private const val LOCAL_FALLBACK_MS = 10_000L

/**
 * Android 生产环境的组合根。只负责构造依赖与绑定回调，不处理录音、轮次、仲裁结果或播放状态。
 */
internal object VoiceEngineFactory {
    /**
     * 生产装配：
 *  - 本地链：`local.asr=iflytek.offline` → [IflytekOfflineCommandAsrStage]；SDK 未配置或
 *    授权未就绪时本地候选不可用，不产生模拟语义。只有显式配置
 *    `iflytek.fake-cmd` 才启用 [FakeCommandAsrProvider]。
     *    之后 [RuleNluProvider.understand]；任何 SDK 异常 → [Intent.unknown]("vehicle")，
     *    本地链绝不抛出。
     *  - 云端链：[GatewayCloudRunner]（GatewayClient + 事件桥，决策事件透传 sink）。
     *  - 网络检查：ConnectivityManager active network != null，可注入。
     */
    fun create(
        cfg: DemoConfig,
        context: Context,
        networkAvailable: () -> Boolean = { context.hasActiveNetwork() },
        sink: DecisionSink,
        player: AudioPlayer,
        vehicle: MockVehicleState,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        onVehicleApplied: () -> Unit = {},
        onLocalRecognized: (String?) -> Unit = {},
        /** 模型回答文本增量回调；S2S 播放期间即可更新回复框。 */
        onReplyText: (String) -> Unit = {},
        /** 导航执行器（spec §4.2）：null → 导航意图记 skipped。 */
        navigation: NavigationExecutor? = null,
        /** B5：云端 LLM 处理中占位回调（收到 pending 帧 → true；最终语义/新一轮 → false）。 */
        onCloudPending: (Boolean) -> Unit = {},
        /** S2S 闲聊锁域进入/退出。 */
        onConversationMode: (Boolean) -> Unit = {},
        onDialogueState: (DialogueSnapshot) -> Unit = {},
        onPlaybackStage: (PlaybackStage) -> Unit = {},
        vehicleContext: VehicleContextProvider = PhoneVehicleContextProvider(context),
    ): VoiceEngine {
        cfg.validateForRuntime()
        // 时钟同步：telemetry 先于 cloudRunner 创建，offset 提供者延迟绑定（仿
        // engineRef 模式；AtomicReference 保证跨线程可见性——握手在线程池，打戳在 IO）
        val clockOffsetProvider = AtomicReference<() -> Long>({ 0L })
        // T6 遥测装配：telemetry 段未配置（enabled 缺省 false）→ enabled=false 全 no-op 实例；
        // clock 注入偏移（ready.serverTime 握手估算），设备端事件统一换算服务器时钟
        val telemetry = TelemetryClient(
            okHttp = OkHttpClient(),
            baseUrl = cfg.cloud.telemetry?.url ?: telemetryBaseUrl(cfg.cloud.gatewayUrl),
            deviceId = cfg.cloud.deviceId,
            scope = scope,
            enabled = cfg.cloud.telemetry?.enabled ?: false,
            clock = { System.currentTimeMillis() + clockOffsetProvider.get().invoke() },
        )
        // T6 决策插桩：sink 收到端云两端的决策事件——on-device → device_arbiter，cloud → cloud_arbiter。
        // 在装配点包裹，仲裁器 / 会话 / 网关桥三条来源的决策都经此记录
        val telemetrySink = DecisionSink { entry ->
            telemetry.recordFor(
                entry.utteranceId,
                if (entry.arbiter == "on-device") {
                    TelemetryStages.DEVICE_ARBITER
                } else {
                    TelemetryStages.CLOUD_ARBITER
                },
                "info",
                mapOf("route" to entry.route, "reason" to entry.reason),
            )
            sink.onDecision(entry)
        }
        // B5：云端 pending 占位信号（LLM 处理中）——桥收到 pending 帧 → 此通道 →
        // 端侧仲裁器阶段 1 窗口延长（pendingWaitMs）。BUFFERED：pending 帧到达时若
        // 仲裁不在等待（如阶段 2 / 轮已结束），信号进缓冲区无接收方也绝不挂起发送方。
        val pendingSignals = Channel<Unit>(Channel.BUFFERED)
        val pendingByTurn = PendingSignalRegistry()
        // ASR 文本与话语成立回调分别绑定：前者上屏，后者才做 capture→turn 准入。
        var engineRef: VoiceEngine? = null
        val cloudRunner = GatewayCloudRunner(
            cfg.cloud, telemetrySink, scope, pendingSignals,
            locationProvider = { vehicleContext.snapshot().position?.let { it.latitude to it.longitude } },
            navigationSelectionProvider = { navigation?.session?.snapshot?.selectionId ?: "" },
        )
        cloudRunner.onAsrResult = { text, _, turnId ->
            if (text.isNotBlank()) {
                engineRef?.onRecognized(turnId, text) ?: onLocalRecognized(text)
            }
        }
        cloudRunner.onAsrTurnEstablished = { turnId ->
            engineRef?.onAsrTurnEstablished(turnId, AdmissionEvidence.CLOUD_ASR)
        }
        cloudRunner.onReplyText = { text, _ ->
            if (text.isNotBlank()) onReplyText(text)
        }
        cloudRunner.onConnectionEvent = { stage, level, payload ->
            telemetry.record(stage, level, payload)
        }
        // 时钟同步：握手估算的时钟偏移（ready.serverTime）注入 telemetry 打戳
        clockOffsetProvider.set(cloudRunner::clockOffsetMs)
        // TTS 缓存（架构变更：缓存从服务器移回端侧）：filesDir 持久目录（重启后仍命中）。
        // 缓存事件由 SpeechOutputService 携带固定 turnId 上报，不读可变的当前轮。
        val ttsCache = TtsCache(File(context.filesDir, "tts_cache"))
        // Task 34：模式切换/销毁时释放离线 stage（unLoadData + engineUnInit）——
        // AiHelper 同能力 ID 单例，旧实例 FSA 残留会导致新实例 loadData 报 15114
        val offlineStageRef = AtomicReference<IflytekOfflineCommandAsrStage?>(null)
        // T7：仲裁器 utteranceId provider 延迟读装配后 engine 的会话成员（session 在
        // VoiceEngine init 里由本 arbiter 装配，构造时序上后者先于前者，用可空引用桥接）
        val engine = VoiceEngine(
            cfg = cfg,
            arbiter = OnDeviceRaceArbiter(
                cloudWaitMs = cfg.cloud.waitMs,
                localFallbackMs = LOCAL_FALLBACK_MS,
                clock = System::currentTimeMillis,
                sink = telemetrySink,
                // T7：on-device 决策日志携带本轮真实 utteranceId（vad start 写入会话）
                utteranceId = { engineRef?.session?.currentUtteranceId ?: "" },
                // B2：仲裁过程事件（收到/胜出/失败）→ device_arbiter_received/won/lost 插桩
                // B5：pending 占位 → device_arbiter_pending 插桩
                onEvent = { event ->
                    when (event) {
                        is OnDeviceArbiterEvent.Received -> telemetry.record(
                            TelemetryStages.DEVICE_ARBITER_RECEIVED,
                            "info",
                            mapOf("route" to event.route),
                        )
                        is OnDeviceArbiterEvent.Won -> telemetry.record(
                            TelemetryStages.DEVICE_ARBITER_WON,
                            "info",
                            mapOf("route" to event.route, "reason" to event.reason),
                        )
                        is OnDeviceArbiterEvent.Lost -> telemetry.record(
                            TelemetryStages.DEVICE_ARBITER_LOST,
                            "warn",
                            mapOf("route" to event.route, "reason" to event.reason),
                        )
                        is OnDeviceArbiterEvent.Pending -> telemetry.record(
                            TelemetryStages.DEVICE_ARBITER_PENDING,
                            "info",
                            mapOf("route" to event.route, "reason" to "llm_pending"),
                        )
                    }
                },
                // B5：pending 信号 → 阶段 1 窗口延长（默认 50s，覆盖 Omni 45s safety）
                pending = pendingSignals,
                pendingByTurn = pendingByTurn,
            ),
            sink = telemetrySink,
            telemetry = telemetry,
            networkAvailable = networkAvailable,
            local = buildLocalChain(
                cfg,
                context,
                scope,
                { turnId, text ->
                    if (!text.isNullOrBlank()) {
                        engineRef?.onRecognized(turnId, text) ?: onLocalRecognized(text)
                    }
                },
                { turnId -> engineRef?.onAsrTurnEstablished(turnId, AdmissionEvidence.LOCAL_ASR) },
                offlineStageRef,
                telemetry,
            ),
            cloud = cloudRunner,
            tts = cloudRunner, // TTS 解耦：播报走独立 tts_request/tts_response（同一网关连接）
            ttsCache = ttsCache, // 缓存移回端侧：查缓存命中直接播，未命中才走网络
            player = player,
            vehicle = vehicle,
            navigation = navigation,
            scope = scope,
            onVehicleApplied = onVehicleApplied,
            onLocalRecognized = onLocalRecognized,
            onReplyText = onReplyText,
            onClose = {
                cloudRunner.close() // Task 21：模式切换时断开网关
                offlineStageRef.get()?.release() // Task 34：释放离线 stage，防新实例 FSA 残留（15114）
            },
            onForeground = cloudRunner::warmUp,
            onCloudPending = onCloudPending,
            onConversationMode = onConversationMode,
            onCloudWon = cloudRunner::releaseReplyText,
            onDialogueState = onDialogueState,
            onPlaybackStage = onPlaybackStage,
            streamingCloud = cloudRunner,
        )
        engineRef = engine
        cloudRunner.onRealtimeReply = engine::playRealtimeChatReply
        cloudRunner.onRealtimeSpeechStarted = engine::stopPlayback
        // T7 评审 C1 注：onTtsPlayEvent 的网络事件绑定已在 VoiceEngine init 完成
        // （telemetry 为构造参数，构造即绑定），此处无需再装配
        // ready 后故障才 latch（连接前故障不 latch，Task 15 M1 裁定）
        cloudRunner.onCloudUnavailable = { engine.session.onCloudUnavailable() }
        // B5：收到 pending 帧 → 端侧"处理中…"UI 状态（清除由 onTurnResult / onListeningStart 收口）
        cloudRunner.onPendingReceived = { turnId ->
            pendingByTurn.signal(turnId)
            engine.setCloudPending(turnId, true)
        }
        // T6：云端链发帧时读取引擎当前话语的 utteranceId
        cloudRunner.utteranceIdProvider = { engine.conversation.captureId }
        // T6 评审 C1：ready 的 sessionId 转发给遥测（与 utteranceIdProvider 同款绑定时机）
        cloudRunner.onReadySessionId = telemetry::onSessionId
        return engine
    }

    /**
     * 遥测 HTTP 基址推导（T6）：显式 telemetry.url 未配时由网关地址推导——
     * `ws://h:p/ws` → `http://h:p`；已是 http 前缀时仅去掉尾部 `/ws` 路径。
     */
    private fun telemetryBaseUrl(gatewayUrl: String): String =
        when {
            gatewayUrl.startsWith("ws://") ->
                "http://" + gatewayUrl.removePrefix("ws://").removeSuffix("/ws")
            else -> gatewayUrl.removeSuffix("/ws")
        }

    /**
     * 本地链装配：显式选择真实或 fake 命令词 ASR → 规则 NLU；处理过程绝不抛出。
     *
     * Task 34 接线：`local.asr=iflytek.offline` 时构造真实 [IflytekOfflineCommandAsrStage]
     * 并在引擎后台协程里 [IflytekOfflineCommandAsrStage.init]（首次联网授权 + 引擎初始化 +
     * 命令词加载）。init 阻塞最长 20s（授权超时），放后台不卡装配；就绪前 recognize 抛
     * NOT_CONFIGURED → 本次按本地候选未命中处理，授权完成后自动
     * 切换真实引擎，无需重启。
     */
    private fun buildLocalChain(
        cfg: DemoConfig,
        context: Context,
        scope: CoroutineScope,
        onLocalRecognized: (String, String?) -> Unit,
        onLocalTurnEstablished: (String) -> Unit,
        /** 装载离线 stage 引用，供 [VoiceEngine.close] 释放（模式切换防 15114 残留）。 */
        offlineStageRef: AtomicReference<IflytekOfflineCommandAsrStage?>,
        /** T7 插桩：local_asr 事件（识别文本/意图/耗时；enabled=false 时 no-op）。 */
        telemetry: TelemetryClient,
    ): LocalChainRunner {
        val offlineStage = if (cfg.local.asr == "iflytek.offline") {
            // 凭据来自 local.properties（BuildConfig 注入，不入库）
            IflytekOfflineCommandAsrStage(
                appId = BuildConfig.XFYUN_APPID,
                apiKey = BuildConfig.XFYUN_API_KEY,
                apiSecret = BuildConfig.XFYUN_API_SECRET,
            ).also { offlineStageRef.set(it) }
        } else {
            null
        }
        if (offlineStage != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    offlineStage.init(context)
                    Log.i(FACTORY_TAG, "讯飞离线命令词初始化完成（授权通过）")
                } catch (t: Throwable) {
                    // 授权失败/资源缺失等：真实候选保持不可用，不得暗中产生 fake 命令。
                    Log.w(FACTORY_TAG, "讯飞离线命令词初始化失败，本地真实候选保持不可用", t)
                }
            }
        }
        // 当前端侧 SDK 是 2C 命令词（文本+语义同源），不是通用 ASR；因此不能冒充
        // ASR 提前上屏。demo-full 的独立 ASR 来自云端 asr_partial；后续接入本地 PGS
        // 时只需替换本 stage，仲裁与 NLU 均无需改动。
        val asr = AsrStage { _, _ -> null }
        val nlu = when (cfg.local.nlu) {
            DemoConfig.LOCAL_NLU_RULE -> NluStage { segment, _ ->
                val command = try {
                    recognizeLocalCommand(cfg.local.asr, segment) { offlineStage?.recognize(segment) }
                } catch (t: Throwable) {
                    Log.w(FACTORY_TAG, "本地 2C 命令词异常，按未命中继续", t)
                    null
                }
                val intent = RuleNluProvider.understand(command.orEmpty())
                // 2C 的文本属于 NLU 候选：不提前显示，只有该候选胜出时才覆盖识别框。
                NluResult(intent = intent, recognizedText = command)
            }
            else -> error("unsupported local.nlu '${cfg.local.nlu}'")
        }
        return object : LocalChainRunner {
            override suspend fun run(segment: ByteArray): NluResult = run(segment, "")

            override suspend fun run(segment: ByteArray, utteranceId: String): NluResult {
                val startMs = System.currentTimeMillis()
                return try {
                    val asrResult = asr.recognize(segment, object : AsrSink {
                        override fun onTurnEstablished() {
                            onLocalTurnEstablished(utteranceId)
                        }

                        override fun onTranscript(result: AsrResult) {
                            // 不等待 NLU、更不等待仲裁；PGS partial/final 都即时更新识别框。
                            if (result.text.isNotBlank()) {
                                onLocalRecognized(utteranceId, result.text)
                                telemetry.record(
                                    TelemetryStages.LOCAL_ASR,
                                    "info",
                                    mapOf("text" to result.text, "isFinal" to result.isFinal),
                                )
                            }
                        }
                    })
                    val nluResult = nlu.understand(segment, asrResult)
                    val intent = nluResult.intent
                    Log.i(FACTORY_TAG, "本地 NLU 意图: ${intent.domain}/${intent.intent} (${intent.slots})")
                    // ASR 与 NLU 分阶段落库；2C 自带文本归 NLU，不伪装成 ASR。
                    telemetry.record(
                        TelemetryStages.LOCAL_NLU,
                        "info",
                        mapOf(
                            "text" to (nluResult.recognizedText ?: ""),
                            "intent" to "${intent.domain}/${intent.intent}",
                            "durationMs" to (System.currentTimeMillis() - startMs),
                        ),
                    )
                    nluResult
                } catch (t: Throwable) {
                    // 本地链绝不抛出：任何 SDK 异常 → unknown 意图（不执行、不播报）
                    Log.w(FACTORY_TAG, "本地链路异常，降级 unknown 意图", t)
                    telemetry.record(
                        TelemetryStages.LOCAL_NLU,
                        "warn",
                        mapOf(
                            "intent" to "unknown/vehicle",
                            "durationMs" to (System.currentTimeMillis() - startMs),
                        ),
                    )
                    NluResult(Intent.unknown("vehicle"))
                }
            }
        }
    }

    /** ConnectivityManager 网络检查：active network 非空即认为网络可用。 */
    private fun Context.hasActiveNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.w(FACTORY_TAG, "hasActiveNetwork: ConnectivityManager 服务不可用")
            return false
        }
        val active = cm.activeNetwork
        Log.d(FACTORY_TAG, "hasActiveNetwork: active=$active all=${cm.allNetworks.toList()}")
        return active != null
    }

}

/** Production failures remain failures; the deterministic fake is an explicit demo provider only. */
internal fun recognizeLocalCommand(
    configuredAsr: String,
    segment: ByteArray,
    offline: () -> String?,
): String? = when (configuredAsr) {
    DemoConfig.LOCAL_ASR_IFLYTEK -> try {
        offline()
    } catch (error: IllegalStateException) {
        if (error.message?.contains(IflytekOfflineCommandAsrStage.NOT_CONFIGURED_MSG) == true) null else throw error
    }
    DemoConfig.LOCAL_ASR_FAKE -> FakeCommandAsrProvider.recognize(segment)
    else -> throw IllegalArgumentException("unsupported local.asr '$configuredAsr'")
}

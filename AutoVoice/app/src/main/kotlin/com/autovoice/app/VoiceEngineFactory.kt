package com.autovoice.app
import com.autovoice.gatewayclient.NavigationRouteCoordinate
import com.autovoice.gatewayclient.NavigationRoutePreviewResult
import com.autovoice.voicebusiness.navigation.NavigationExecutor
import com.autovoice.voicebusiness.navigation.NavigationTaskContextRef

import android.content.Context
import com.autovoice.app.channel.SpeechChannel
import com.autovoice.app.channel.LocalEngineAssembly
import com.autovoice.app.channel.PlaybackDriverPorts
import com.autovoice.audiofrontend.RenderReferenceSink
import com.autovoice.app.health.ChannelComponentReporting
import com.autovoice.app.health.CloudConnectionReporting
import com.autovoice.voicecore.health.*
import android.net.ConnectivityManager
import android.util.Log
import com.autovoice.voiceengine.local.LocalSpeechRoute
import com.autovoice.voiceengine.RecognitionGate
import com.autovoice.voiceengine.AsrModule
import com.autovoice.voiceengine.NluModule
import com.autovoice.app.business.AppBusinessHandler
import com.autovoice.app.telemetry.TelemetryClient
import com.autovoice.app.telemetry.TelemetryStages
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.arbiter.OnDeviceArbiterEvent
import com.autovoice.voicecore.arbiter.OnDeviceRaceArbiter
import com.autovoice.voicebusiness.dialog.AdmissionEvidence
import com.autovoice.voicebusiness.dialog.DialogueSnapshot
import com.autovoice.voicecore.session.LocalChainRunner
import com.autovoice.tts.TtsEventSink
import com.autovoice.tts.TtsSynthesizer
import com.autovoice.tts.createTtsOutput
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient

private const val FACTORY_TAG = "VoiceEngineFactory"

/**
 * Android 生产环境的组合根。只负责构造依赖与绑定回调，不处理录音、轮次、仲裁结果或播放状态。
 */
internal object VoiceEngineFactory {
    /** Selected channel supplies local engines and platform audio ports; shared routing stays here. */
    fun create(
        cfg: DemoConfig,
        context: Context,
        channel: SpeechChannel,
        reporting: ChannelComponentReporting,
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
        onTextInputError: (String) -> Unit = {},
        onTravelGuideStarted: (String) -> Unit = {},
        onTravelDocument: (String, String, String) -> Unit = { _, _, _ -> },
        onPlaybackStage: (PlaybackStage) -> Unit = {},
        vehicleContext: VehicleContextProvider = PhoneVehicleContextProvider(context),
        /** Business-side navigation selection acknowledgement binding; VoiceEngine never sees it. */
        bindNavigationAdoptionSender: ((NavigationTaskContextRef) -> Unit) -> Unit = {},
        bindNavigationRoutePreview: (
            suspend (String, NavigationRouteCoordinate?, NavigationRouteCoordinate, List<NavigationRouteCoordinate>, String) ->
                NavigationRoutePreviewResult
        ) -> Unit = {},
        onNavigationTransportReset: () -> Unit = { navigation?.abortPendingTask() },
        renderSink: RenderReferenceSink? = null,
        onSavedNavigationCommand: (String, String) -> String? = { _, _ -> null },
    ): VoiceEngine {
        channel.validateConfig(cfg)
        // 时钟同步：telemetry 先于 cloudRunner 创建，offset 提供者延迟绑定（仿
        // engineRef 模式；AtomicReference 保证跨线程可见性——握手在线程池，打戳在 IO）
        val clockOffsetProvider = AtomicReference<() -> Long>({ 0L })
        // T6 遥测装配：telemetry 段未配置（enabled 缺省 false）→ enabled=false 全 no-op 实例；
        // clock 注入偏移（ready.serverTime 握手估算），设备端事件统一换算服务器时钟
        val telemetry = TelemetryClient(
            okHttp = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build(),
            baseUrl = cfg.cloud.telemetry?.url ?: telemetryBaseUrl(cfg.cloud.gatewayUrl),
            deviceId = cfg.cloud.deviceId,
            // Diagnostic close-out must survive VoiceEngine.close() cancelling its own scope.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
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
        // 端侧仲裁流水线延后本地普通语义的入队时间（pendingWaitMs）。BUFFERED 保证
        // 接收桥不被业务处理反压；信号只影响对应 turnId，不延迟云端语义入队。
        val pendingSignals = Channel<Unit>(Channel.BUFFERED)
        // ASR 文本与话语成立回调分别绑定：前者上屏，后者才做 capture→turn 准入。
        val engineRef = AtomicReference<VoiceEngine?>(null)
        val recognitionGate = RecognitionGate()
        val asrModule = AsrModule()
        val nluModule = NluModule()
        val cloudRunner = GatewayCloudRunner(
            cfg.cloud, telemetrySink, scope, pendingSignals,
            locationProvider = { vehicleContext.snapshot().position?.let { it.latitude to it.longitude } },
            navigationContextProvider = {
                navigation?.session?.snapshot?.let { snapshot ->
                    val taskId = snapshot.taskId
                    val interactionId = snapshot.interactionId
                    val selectionId = snapshot.selectionId
                    if (taskId != null && interactionId != null && !selectionId.isNullOrBlank()) {
                        NavigationTaskContextRef(
                            taskId,
                            snapshot.candidateVersion,
                            interactionId,
                            selectionId,
                        )
                    } else null
                }
            },
            recognitionGate = recognitionGate,
            asrModule = asrModule,
            nluModule = nluModule,
        )
        cloudRunner.onAsrResult = { text, final, turnId ->
            if (text.isNotBlank()) {
                engineRef.get()?.onRecognized(turnId, text) ?: onLocalRecognized(text)
                if (final && engineRef.get()?.conversation?.isVisible(turnId) == true) {
                    cloudRunner.retireTravelIfDifferent(turnId)
                }
            }
        }
        cloudRunner.onAsrTurnEstablished = { turnId ->
            engineRef.get()?.onAsrTurnEstablished(turnId, AdmissionEvidence.CLOUD_ASR)
        }
        cloudRunner.onReplyText = { text, _ ->
            if (text.isNotBlank()) onReplyText(text)
        }
        cloudRunner.onTravelDocument = onTravelDocument
        val cloudReporter = reporting.reporter("cloud")
        val cloudHealth = bindCloudHealth(cloudRunner,cloudReporter,cfg.cloud.enabled)
        cloudRunner.onConnectionEvent = { stage, level, payload ->
            telemetry.record(stage, level, payload)
        }
        // 时钟同步：握手估算的时钟偏移（ready.serverTime）注入 telemetry 打戳
        clockOffsetProvider.set(cloudRunner::clockOffsetMs)
        // TTS 缓存（架构变更：缓存从服务器移回端侧）：filesDir 持久目录（重启后仍命中）。
        // 缓存事件由 :tts 携带固定 turnId 上报，不读可变的当前轮。
        val localEnginesOwner = lazy { channel.createLocalEngines(context, cfg, scope) }
        val localEngines by localEnginesOwner
        var selectedPlaybackToClose: com.autovoice.tts.TtsPlaybackDriver? = null
        var arbiterToClose: OnDeviceRaceArbiter? = null
        var engineToClose: VoiceEngine? = null
        try {
            val selectedPlayback = channel.createPlaybackDriver(player,PlaybackDriverPorts(context,scope,renderSink,
                { stage,level,payload -> engineRef.get()?.onTtsPlayEvent(stage,level,payload) },
                reporting.reporter("playback"),reporting.reporter("render_reference")))
            selectedPlaybackToClose = selectedPlayback
            val ttsOutput = createTtsOutput(
                synthesizer = TtsSynthesizer { text, turnId -> cloudRunner.request(text, turnId) },
                cacheDir = File(context.filesDir, "tts_cache"),
                driver = selectedPlayback,
                scope = scope,
                isCurrentTurn = { turnId -> engineRef.get()?.conversation?.isCurrentTurn(turnId) ?: true },
                isCurrentRealtime = cloudRunner::isCurrentRealtimeOutput,
                events = TtsEventSink { stage, level, payload ->
                    val turnId = payload["turnId"] as? String ?: ""
                    telemetry.recordFor(turnId, stage, level, payload - "turnId")
                },
                onPlaybackStage = { identity, stage ->
                    engineRef.get()?.onPlaybackLifecycle(identity.turnId, stage)
                    onPlaybackStage(stage)
                },
                onEmptyOutput = { turnId -> engineRef.get()?.conversation?.onPlaybackEnded(turnId) },
            )
            // T7：仲裁器 utteranceId provider 延迟读装配后 engine 的会话成员（session 在
            // VoiceEngine init 里由本 arbiter 装配，构造时序上后者先于前者，用可空引用桥接）
            // 业务路由位于引擎之外。只有状态机与仲裁通过后的语义才会抵达此边界。
            val business = AppBusinessHandler(
                vehicle = vehicle,
                navigation = navigation,
                onVehicleApplied = onVehicleApplied,
                onConversationMode = onConversationMode,
                onExitDialogue = { engineRef.get()?.exitCurrentDialogue() },
                onTravelGuideStarted = { turnId ->
                    onTravelGuideStarted(turnId)
                    cloudRunner.startTravelGuide(turnId)
                },
            )
            val onDeviceArbiter = OnDeviceRaceArbiter(
                cloudWaitMs = cfg.cloud.waitMs,
                clock = System::currentTimeMillis,
                sink = telemetrySink,
                onTurnEvent = { turnId, event ->
                    when (event) {
                        is OnDeviceArbiterEvent.Received -> telemetry.recordFor(turnId,
                            TelemetryStages.DEVICE_ARBITER_RECEIVED,
                            "info",
                            mapOf("route" to event.route),
                        )
                        is OnDeviceArbiterEvent.Won -> telemetry.recordFor(turnId,
                            TelemetryStages.DEVICE_ARBITER_WON,
                            "info",
                            mapOf("route" to event.route, "reason" to event.reason),
                        )
                        is OnDeviceArbiterEvent.Lost -> telemetry.recordFor(turnId,
                            TelemetryStages.DEVICE_ARBITER_LOST,
                            "warn",
                            mapOf("route" to event.route, "reason" to event.reason),
                        )
                        is OnDeviceArbiterEvent.Pending -> telemetry.recordFor(turnId,
                            TelemetryStages.DEVICE_ARBITER_PENDING,
                            "info",
                            mapOf("route" to event.route, "reason" to "llm_pending"),
                        )
                    }
                },
            )
            arbiterToClose = onDeviceArbiter
            val engine = VoiceEngine(
                cfg = cfg,
                arbiter = onDeviceArbiter,
                telemetry = telemetry,
                networkAvailable = networkAvailable,
                local = buildLocalChain(
                    localEngines,
                    { turnId, text, final ->
                        if (!text.isNullOrBlank()) {
                            engineRef.get()?.onRecognized(turnId, text) ?: onLocalRecognized(text)
                            if (final && engineRef.get()?.conversation?.isVisible(turnId) == true) {
                                cloudRunner.retireTravelIfDifferent(turnId)
                            }
                        }
                    },
                    { turnId ->
                        engineRef.get()?.onAsrTurnEstablished(turnId, AdmissionEvidence.LOCAL_ASR)
                    },
                    telemetry,
                    asrModule,
                    nluModule,
                ),
                cloud = cloudRunner,
                tts = ttsOutput,
                business = business,
                scope = scope,
                recognitionGate = recognitionGate,
                onLocalRecognized = onLocalRecognized,
                onReplyText = onReplyText,
                onClose = {
                    selectedPlayback.close()
                    cloudHealth.close()
                    cloudRunner.close() // Task 21：模式切换时断开网关
                    localEngines.close() // Selected channel releases its own local resources.
                },
                onForeground = cloudRunner::warmUp,
                onCloudPending = onCloudPending,
                onCloudWon = cloudRunner::releaseReplyText,
                onDialogueState = onDialogueState,
                onTextInputError = onTextInputError,
                streamingCloud = cloudRunner,
                onSavedNavigationCommand = onSavedNavigationCommand,
            )
            engineToClose = engine
            engineRef.set(engine)
            localEngines.bindAdmission(com.autovoice.voicecore.LocalRequestAdmissionProvider(engine.candidates::localAdmission))
            cloudRunner.onRealtimeReply = engine::playRealtimeChatReply
            cloudRunner.onRealtimeSpeechStarted = engine::stopPlayback
            // T7 评审 C1 注：onTtsPlayEvent 的网络事件绑定已在 VoiceEngine init 完成
            // （telemetry 为构造参数，构造即绑定），此处无需再装配
            // ready 后故障才 latch（连接前故障不 latch，Task 15 M1 裁定）
            cloudRunner.onCloudUnavailable = {
                engine.candidates.onCloudUnavailable()
                // A transport break ends the local task. We intentionally do not resume or replay a
                // navigation selection after reconnect; the user must make a fresh request.
                onNavigationTransportReset()
            }
            cloudRunner.onNavigationContextMissing = { ref ->
                if (navigation?.session?.contextMissing(ref) == true) onReplyText("地点选择已失效，请重新搜索")
            }
            // B5：收到 pending 帧 → 端侧"处理中…"UI 状态（清除由 onTurnResult / onListeningStart 收口）
            cloudRunner.onPendingReceived = { turnId ->
                onDeviceArbiter.submitPending(turnId)
                engine.setCloudPending(turnId, true)
            }
            // T6：云端链发帧时读取引擎当前话语的 utteranceId
            cloudRunner.utteranceIdProvider = { engine.conversation.captureId }
            // T6 评审 C1：ready 的 sessionId 转发给遥测（与 utteranceIdProvider 同款绑定时机）
            cloudRunner.onReadySessionId = telemetry::onSessionId
            // A ready frame establishes a new transport generation. Task-dialog state is deliberately
            // not resumed across it, even when the logical server session itself was resumed.
            cloudRunner.onSessionRecovery = { _, _ ->
                onNavigationTransportReset()
            }
            // D05b:采用确认属于导航业务与云端协议，不经过 VoiceEngine。
            bindNavigationAdoptionSender { context ->
                cloudRunner.sendNavigationSelectionStart(context)
            }
            bindNavigationRoutePreview(cloudRunner::previewNavigationRoutes)
            return engine
        } catch (error: Throwable) {
            if (engineToClose != null) runCatching { engineToClose.close() }
            else {
                runCatching { selectedPlaybackToClose?.close() }
                if (localEnginesOwner.isInitialized()) runCatching { localEngines.close() }
                runCatching { arbiterToClose?.close() }
                runCatching { cloudHealth.close() }
                runCatching { cloudRunner.close() }
                runCatching { telemetry.close() }
                scope.cancel()
            }
            throw error
        }
    }

    internal fun bindCloudHealth(runner: GatewayCloudRunner, reporter: ComponentReporter, enabled: Boolean): CloudConnectionReporting =
        CloudConnectionReporting(reporter).also { binding ->
            runner.onConnectionDiagnostic = { event -> if (enabled) binding.accept(event) }
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

    /** Provider-neutral local route and telemetry; selected channel owns initialization/release. */
    private fun buildLocalChain(
        engines: LocalEngineAssembly,
        onLocalRecognized: (String, String?, Boolean) -> Unit,
        onLocalTurnEstablished: (String) -> Unit,
        telemetry: TelemetryClient,
        asrModule: AsrModule,
        nluModule: NluModule,
    ): LocalChainRunner {
        asrModule.bindLocal(engines.asr)
        nluModule.bindLocal(engines.nlu)
        return LocalSpeechRoute(
            asr = asrModule,
            nlu = nluModule,
            onRecognized = { turnId, result ->
                onLocalRecognized(turnId, result.text, result.isFinal)
                telemetry.record(
                    TelemetryStages.LOCAL_ASR,
                    "info",
                    mapOf("text" to result.text, "isFinal" to result.isFinal),
                )
            },
            onTurnEstablished = onLocalTurnEstablished,
            onResult = { _, result, durationMs ->
                val intent = result.intent
                Log.i(FACTORY_TAG, "本地 NLU 意图: ${intent.domain}/${intent.intent} (${intent.slots})")
                telemetry.record(
                    TelemetryStages.LOCAL_NLU,
                    "info",
                    mapOf(
                        "text" to (result.recognizedText ?: ""),
                        "intent" to "${intent.domain}/${intent.intent}",
                        "durationMs" to durationMs,
                    ),
                )
            },
            onFailure = { _, error, durationMs ->
                Log.w(FACTORY_TAG, "本地链路异常，降级 unknown 意图", error)
                telemetry.record(
                    TelemetryStages.LOCAL_NLU,
                    "warn",
                    mapOf("intent" to "unknown/vehicle", "durationMs" to durationMs),
                )
            },
        )
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

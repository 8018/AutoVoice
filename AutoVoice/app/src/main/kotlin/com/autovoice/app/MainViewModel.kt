package com.autovoice.app

import android.app.Application
import android.content.Context
import android.content.Intent as AndroidIntent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autovoice.app.BuildConfig
import com.autovoice.adapteriflytek.IflytekWakeWordObserver
import com.autovoice.app.audio.AudioRecorder
import com.autovoice.app.audio.TtsPlayer
import com.autovoice.voicecore.CloudConfig
import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.LocalConfig
import com.autovoice.voicecore.MockConfig
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.voicecore.VadConfig
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.dialog.DialogueSnapshot
import com.autovoice.voicecore.dialog.DialogueState
import com.autovoice.voicecore.session.SessionState
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** demo 模式（设置区切换）：demo-full / demo-offline。Task 19 纯 UI 状态，配置装配在 Task 21。 */
enum class DemoMode(val label: String) {
    DEMO_FULL("demo-full"),
    DEMO_OFFLINE("demo-offline"),
}

/** 车辆状态快照（StateFlow 携带不可变快照，避免直接暴露可变执行器）。 */
data class VehicleUiState(
    val acOn: Boolean = false,
    val acTemperature: Double = 24.0,
    val windowsOpen: Boolean = false,
) {
    companion object {
        fun from(state: MockVehicleState): VehicleUiState =
            VehicleUiState(
                acOn = state.isAcOn,
                acTemperature = state.acTemperature,
                windowsOpen = state.isWindowsOpen,
            )
    }
}

/**
 * UI 状态（单一 StateFlow 来源）。
 *
 * - [sessionState]：会话阶段（Task 19 只在 IDLE ⇄ LISTENING；Task 20 接入 VoiceSession 后
 *   补 UNDERSTANDING/EXECUTING/SPEAKING；Task 50 按钮模式：按下 → LISTENING，抬手 → 竞速）；
 * - [vehicle]：模拟车控面板快照；
 * - [mode] / [weakNetwork]：设置区纯 UI 状态；
 * - [recording]：按住录音中（按钮视觉，Task 50）；
 * - [vadUnavailable]：Silero VAD 模型加载失败（云端路段切分不可用，仅提示不阻断，Task 44）；
 * - [lastRecognizedText]：最近一次识别文本（Task 34）；[lastReplyText]：最近一次回复播报
 *   文本（Task 53：仲裁结果不再上屏，logcat 打印，界面留给识别/回复对话区）。
 */
data class UiState(
    val locationHint: String? = null,
    val navigation: NavigationSnapshot = NavigationSnapshot(),
    val sessionState: SessionState = SessionState.IDLE,
    val vehicle: VehicleUiState = VehicleUiState(),
    val mode: DemoMode = DemoMode.DEMO_OFFLINE,
    val weakNetwork: Boolean = false,
    val permissionHint: Boolean = false,
    /** 按住录音中（Task 50 按钮模式；按钮视觉状态）。 */
    val recording: Boolean = false,
    /** Silero VAD 加载失败（云端路段切分不可用，仅提示不阻断）。 */
    val vadUnavailable: Boolean = false,
    /** 最近一次本地 ASR 识别文本（Task 34 接线后可见识别结果，null = 尚未识别）。 */
    val lastRecognizedText: String? = null,
    /** 最近一次回复播报文本（Task 53：云端 AudioReply.speakText 或本地文本播报）。 */
    val lastReplyText: String? = null,
    /**
     * 最近一次竞速胜出方（Task 61：UI 标志「端侧胜出 / 云端胜出」；端侧仲裁决策
     * arbiter=on-device 时按 route 更新，null = 尚无结果）。
     */
    val lastWinner: String? = null,
    /**
     * B5：云端 LLM 处理中占位（协议 §4.8）——收到 pending 帧 → true，最终语义到达 /
     * 新一轮开始 → false。仅 UI 状态（Header 显示"处理中…"徽标），无执行无播报。
     */
    val cloudPending: Boolean = false,
    /** 待机唤醒观察者已启用；底层与语音链共享同一个 AudioRecord。 */
    val wakeListening: Boolean = false,
    /** 设备采集会话已启用 AEC，播报期可用普通话术直接打断。 */
    val openMicBargeInAvailable: Boolean = false,
    /** 唤醒初始化/资源错误；null 表示无错误。 */
    val wakeError: String? = null,
    /** 导航 POI 候选；非空时在本应用内显示语音选择弹窗，尚未拉起地图。 */
    /** 已进入 S2S 闲聊锁域；麦克风常开且绕过端侧 ASR/NLU。 */
    val chatMode: Boolean = false,
) {
    val navigationCandidates: List<NavigationExecutor.NavigationCandidate> get() = navigation.candidates
}

/**
 * 主 ViewModel 只装配 [RecordingCoordinator]、[VoiceEngine]、[MockVehicleState] 与 UI 状态。
 * 麦克风所有权、音频扇出及录音定时器均由 RecordingCoordinator 管理。
 *
 * Task 50 按钮双路接线（按下录音，抬手双路送识别；VAD 保留用于云端路段切分）：
 *  - 按下 → [startRecording]：清段缓冲 → 启动录音 → [VoiceEngine.onListeningStart]
 *    （网络可用则恢复云端路由，否则挂起云端）；
 *  - 按住期间：[AudioRecorder] 产生 PCM/VAD，RecordingCoordinator 组装云端流与本地整段；
 *  - 抬手 → [stopRecording]：停止录音 → 先逐段 [VoiceEngine.onCloudSegment]（云端路），
 *    再把整段降噪 PCM 送 [VoiceEngine.onTurnSegment]（本地路，启动竞速）；
 *    整段 < 300ms（误触）丢弃不送识别，直接回 IDLE；
 *  - 录音中切模式 → [cancelRecording]：停录音、不送识别（引擎随后释放/重建）；
 *  - 会话状态 → [UiState.sessionState]；决策 sink → [MainViewModel.addDecision]；
 *  - 弱网开关 → engine.weakNetwork（云端人为延迟 3s，debug 构建）；
 *  - [onCleared] → RecordingCoordinator 统一释放录音与唤醒资源。
 *
 * 所有 UI 状态一律走 [uiState] StateFlow，回调线程不触碰 Android 视图。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    /** 录音器（Task 19 由 UI 驱动；Task 50 按住录音：VAD 云端段 + RNNoise 降噪整段）。 */
    private val recorder = AudioRecorder(getApplication())

    /** 模拟车控执行器（Task 20 的 executor 经 [applyVehicleIntent] 路由到这里）。 */
    val vehicleState = MockVehicleState()

    /** 音频播放出口（生产实现：MediaPlayer + wav 临时文件）。 */
    /**
     * 音频播放（Task 18）。T7：播放事件（start/completed/failed/interrupted）转发到
     * 引擎的 [VoiceEngine.onTtsPlayEvent]（create() 已绑定 telemetry.record tts_play）；
     * engine 在 init 完成装配，播放必然发生在引擎就绪之后。
     * 2026-08-15：全部播报统一走网络 TTS（TtsPlayer 播放服务端合成音频），不再用系统 TTS。
     */
    private val ttsPlayer = TtsPlayer(application) { stage, level, payload ->
        // 引擎先校验固定 playbackId，再通过 onPlaybackStage 改变监听状态。
        engine.onTtsPlayEvent(stage, level, payload)
    }

    /** 端侧引擎：VoiceSession + 双链路竞速 + 播报/执行路由（Task 20）。 */
    private lateinit var engine: VoiceEngine

    /** 只观察共享 PCM，不自行创建 AudioRecord。 */
    private val wakeObserver = IflytekWakeWordObserver(
        appId = BuildConfig.XFYUN_APPID,
        apiKey = BuildConfig.XFYUN_API_KEY,
        apiSecret = BuildConfig.XFYUN_API_SECRET,
        onWake = { keyword -> viewModelScope.launch { recordingCoordinator.deliverWake(keyword) } },
        onError = { error -> viewModelScope.launch { recordingCoordinator.deliverWakeError(error) } },
    )

    /** 唯一麦克风使用权与音频流路由；不参与 ASR/NLU、仲裁或对话状态转换。 */
    private lateinit var recordingCoordinator: RecordingCoordinator

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private val vehicleContext = PhoneVehicleContextProvider(getApplication()) { hint ->
        _uiState.update { it.copy(locationHint = hint) }
    }
    private val navigationSession = NavigationSession { snapshot ->
        _uiState.update { it.copy(navigation = snapshot) }
    }
    private val navigationExecutor by lazy {
        NavigationExecutor(session = navigationSession, onCandidates = { candidates ->
            navigationDialogTimeoutJob?.cancel()
            if (candidates.isNotEmpty()) {
                val version = navigationSession.snapshot.candidateVersion
                navigationDialogTimeoutJob = viewModelScope.launch {
                    delay(NAVIGATION_DIALOG_TTL_MS)
                    navigationSession.expire(version)
                }
            }
        }) { uri ->
            runCatching {
                getApplication<Application>().startActivity(
                    AndroidIntent(AndroidIntent.ACTION_VIEW, Uri.parse(uri))
                        .addFlags(AndroidIntent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            }.getOrDefault(false)
        }
    }

    private var navigationDialogTimeoutJob: Job? = null

    init {
        // 默认装配与设置区默认模式一致（Task 19/21）：DEMO_OFFLINE → demo-offline 资产。
        // Task 58：模式持久化（SharedPreferences）——重启/安装后保持用户上次选择；
        // 否则每次重启回 DEMO_OFFLINE（cloud.enabled=false），云端链关闭，表现为
        // "重启后云端失联"（每轮 cloud_unreachable、服务器零流量）。
        val mode = restoreMode()
        _uiState.update { it.copy(mode = mode) }
        engine = buildEngine(loadConfig(mode))
        recordingCoordinator = buildRecordingCoordinator()
    }

    // ------------------------------------------------------------------ 录音生命周期

    fun startRecording() {
        vehicleContext.refresh()
        recordingCoordinator.startManualTurn()
    }

    fun stopRecording() = recordingCoordinator.stopTurn()

    private fun cancelRecording() = recordingCoordinator.cancelTurn()

    /** Activity 回到前台时提前恢复 WebSocket 与共享麦克风。 */
    fun onForeground() {
        vehicleContext.refresh()
        engine.onForeground()
        recordingCoordinator.onForeground()
        Log.i(TAG, "App 回到前台：重建共享麦克风与 IVW 会话")
    }

    fun onAudioPermissionGranted() {
        vehicleContext.refresh()
        recordingCoordinator.onAudioPermissionGranted()
    }

    fun onBackground() {
        vehicleContext.stopRefresh()
        recordingCoordinator.onBackground()
        Log.i(TAG, "App 进入后台：共享麦克风已停止，IVW 会话已暂停")
    }

    fun onPermissionDenied() = recordingCoordinator.onPermissionDenied()

    fun clearPermissionHint() = onAudioPermissionGranted()

    private fun handleDialogueState(snapshot: DialogueSnapshot) {
        val state = when (snapshot.state) {
            DialogueState.DORMANT -> SessionState.IDLE
            DialogueState.AWAKE,
            DialogueState.FOLLOW_UP_LISTENING,
            -> SessionState.LISTENING
            DialogueState.THINKING,
            DialogueState.SEMANTIC_PROCESSING,
            DialogueState.RESPONDING,
            -> SessionState.UNDERSTANDING
            DialogueState.SPEAKING -> SessionState.SPEAKING
        }
        _uiState.update { it.copy(sessionState = state) }
        recordingCoordinator.onDialogueState(snapshot, _uiState.value.navigationCandidates.isNotEmpty())
    }

    private fun setChatMode(enabled: Boolean) {
        if (!recordingCoordinator.setChatMode(enabled)) return
        _uiState.update {
            it.copy(
                chatMode = enabled,
                sessionState = if (enabled) SessionState.LISTENING else engine.session.state.value,
            )
        }
    }

    // ------------------------------------------------------------------ 设置

    /**
     * demo-full / demo-offline 切换（Task 21）：加载对应配置资产并重建引擎。
     * 切换安全策略：录音/竞速进行中先中止（松开按钮语义），再释放旧引擎
     * （断开网关 + 取消其协程作用域）、装配新引擎；弱网开关状态跨引擎保持。
     * 重复点击当前模式为 no-op。
     */
    fun setMode(mode: DemoMode) {
        if (_uiState.value.mode == mode) return
        if (recordingCoordinator.isChatLocked) setChatMode(false)
        else cancelRecording() // 录音中切模式：停录音不送识别（引擎随后释放/重建，幂等）
        engine.close()
        engine = buildEngine(loadConfig(mode))
        recordingCoordinator.onDialogueState(
            engine.dialogue.snapshot.value,
            _uiState.value.navigationCandidates.isNotEmpty(),
        )
        engine.weakNetwork = _uiState.value.weakNetwork // 弱网开关跨引擎保持（Task 20）
        persistMode(mode) // Task 58：模式持久化，重启后保持
        navigationDialogTimeoutJob?.cancel()
        navigationSession.cancelSelection()
        _uiState.update { it.copy(mode = mode) }
    }

    /** 模拟弱网（云端延迟 3s）开关；绑定到引擎的云端人为延迟 hook（Task 20，debug 构建）。 */
    fun setWeakNetwork(enabled: Boolean) {
        _uiState.update { it.copy(weakNetwork = enabled) }
        engine.weakNetwork = enabled
    }

    // ------------------------------------------------------------------ Task 20 接线点

    /**
     * 仲裁器 sink：仲裁结果只打 logcat（Task 53：不再上屏，界面留给识别/回复）；
     * Task 61 补「胜出方」UI 标志：仅端侧仲裁决策（arbiter=on-device）映射 route
     * （local → 端侧 / cloud → 云端），云端决策（arbiter=cloud）与兜底不覆盖。
     * 日志格式对齐原 UI 行：HH:mm:ss.SSS · 仲裁 arbiter → route: reason。
     */
    internal fun addDecision(entry: DecisionEntry) {
        if (entry.arbiter == "on-device") {
            _uiState.update {
                it.copy(lastWinner = when (entry.route) {
                    "cloud" -> WINNER_CLOUD
                    else -> WINNER_LOCAL
                })
            }
        }
        val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .format(Instant.ofEpochMilli(entry.timestampMs)
                .atZone(ZoneId.systemDefault()).toLocalTime())
        Log.i(TAG, "仲裁 ${entry.arbiter} → ${entry.route}: ${entry.reason} [$time utt=${entry.utteranceId}]")
    }

    /** 车控意图执行：apply 后把执行器状态快照进 UiState；返回播报文本（未知 → null）。 */
    internal fun applyVehicleIntent(intent: Intent): String? =
        vehicleState.apply(intent)?.also {
            _uiState.update { s -> s.copy(vehicle = VehicleUiState.from(vehicleState)) }
        }

    // ------------------------------------------------------------------ 内部

    /**
     * 诊断落盘（Task 58 联调）：debug 构建把本地链路整段降噪 PCM（16k 单声道 PCM16）
     * 写 app 私有目录（免存储权限），adb pull 分析真实麦克风信号电平/内容。
     * release 构建零开销（常量折叠）。失败静默（仅日志），不阻断识别。
     */
    private fun dumpLocalSegment(pcm: ByteArray) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val f = File(getApplication<Application>().filesDir, "local-${System.currentTimeMillis()}.pcm")
            f.writeBytes(pcm)
            Log.i(TAG, "本地整段落盘: ${f.absolutePath} (${pcm.size}B ≈ ${pcm.size / 32}ms)")
        }.onFailure { Log.w(TAG, "本地整段落盘失败（静默）", it) }
    }

    private fun buildRecordingCoordinator() = RecordingCoordinator(
        capture = recorder,
        wakeWord = object : WakeWordPort {
            override val keyword: String get() = wakeObserver.keyword
            override fun initialize() = wakeObserver.init(getApplication())
            override fun arm() = wakeObserver.arm()
            override fun accept(pcm: ByteArray) = wakeObserver.accept(pcm)
            override fun pause() = wakeObserver.pause()
            override fun disarm() = wakeObserver.disarm()
            override fun close() = wakeObserver.close()
        },
        pipeline = object : RecordingPipeline {
            override val dialogueSnapshot: DialogueSnapshot get() = engine.dialogue.snapshot.value
            override fun onWake() = engine.onWake()
            override fun onListeningStart(interruptPlayback: Boolean) =
                engine.onListeningStart(interruptPlayback)
            override fun onListeningStop() = engine.onListeningStop()
            override fun onVadStart() = engine.onVadStart()
            override fun onVadEnd() = engine.onVadEnd()
            override fun appendStreamingCloudAudio(block: ByteArray) =
                engine.appendStreamingCloudAudio(block)
            override fun finishStreamingCloudAudio() = engine.finishStreamingCloudAudio()
            override fun cancelStreamingCloudAudio() = engine.cancelStreamingCloudAudio()
            override fun onCloudSegment(segment: ByteArray) = engine.onCloudSegment(segment)
            override fun onTurnSegment(segment: ByteArray) = engine.onTurnSegment(segment)
            override fun appendRealtimeChatAudio(block: ByteArray) = engine.appendRealtimeChatAudio(block)
            override fun startRealtimeChat() = engine.startRealtimeChat()
            override fun finishRealtimeChat() = engine.finishRealtimeChat()
            override fun onFollowUpExpired(interactionId: String) = engine.onFollowUpExpired(interactionId)
            override fun resetDialogue() = engine.resetDialogue()
        },
        scope = viewModelScope,
        isPlaybackSpeaking = ttsPlayer::isSpeaking,
        onState = { snapshot ->
            _uiState.update {
                it.copy(
                    recording = snapshot.recording,
                    wakeListening = snapshot.wakeListening,
                    permissionHint = snapshot.permissionRequired,
                    vadUnavailable = snapshot.vadUnavailable,
                    openMicBargeInAvailable = snapshot.openMicBargeInAvailable,
                    wakeError = snapshot.wakeError,
                    chatMode = snapshot.chatMode,
                )
            }
        },
        onLocalSegment = ::dumpLocalSegment,
        onLog = { Log.i(TAG, it) },
        onWakeError = { Log.w(TAG, "离线唤醒不可用", it) },
        elapsedRealtimeMs = SystemClock::elapsedRealtime,
    )

    /**
     * 单一引擎装配点（Task 21）：init 与 [setMode] 共用。引擎使用专属协程作用域
     * （不复用 viewModelScope），由 [VoiceEngine.close] 在切换/销毁时取消——旧引擎的
     * 在途竞速与网关桥接收集随作用域一并终止，不殃及 ViewModel 自己的收集器。
     */
    private fun buildEngine(cfg: DemoConfig): VoiceEngine {
        val engine = VoiceEngine.create(
            cfg = cfg,
            context = getApplication(),
            sink = DecisionSink { addDecision(it) },
            player = object : AudioPlayer {
                override fun play(reply: com.autovoice.voicecore.AudioReply) {
                    if (reply.speakText.isNotBlank()) {
                        _uiState.update { s -> s.copy(lastReplyText = reply.speakText) }
                    }
                    ttsPlayer.play(reply)
                }
                override fun stop() {
                    ttsPlayer.stop()
                }
                override suspend fun playStream(reply: StreamingAudioReply) {
                    ttsPlayer.playStream(reply)
                }
                override fun play(reply: com.autovoice.voicecore.AudioReply, identity: PlaybackIdentity) {
                    ttsPlayer.play(reply, identity)
                }
                override suspend fun playStream(reply: StreamingAudioReply, identity: PlaybackIdentity) {
                    ttsPlayer.playStream(reply, identity)
                }
            },
            vehicle = vehicleState,
            vehicleContext = vehicleContext,
            // 导航执行（spec §4.2）：applicationContext + NEW_TASK 拉起高德 App；
            // 未安装/无处理 Activity 时 runCatching 吞掉异常返回 false（记 skipped）
            navigation = navigationExecutor,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onVehicleApplied = { _uiState.update { it.copy(vehicle = VehicleUiState.from(vehicleState)) } },
            onLocalRecognized = { text -> _uiState.update { it.copy(lastRecognizedText = text) } },
            onReplyText = { text -> _uiState.update { it.copy(lastReplyText = text) } },
            // B5：云端 LLM 处理中占位 → Header"处理中…"徽标（清除由引擎收口）
            onCloudPending = { v -> _uiState.update { it.copy(cloudPending = v) } },
            onConversationMode = ::setChatMode,
            onDialogueState = ::handleDialogueState,
            // 只在身份有效的真实播放期打开普通话术 VAD；迟到回调不会改变录音状态。
            onPlaybackStage = { stage -> recordingCoordinator.onPlaybackStage(stage) },
        )
        return engine
    }

    /** 恢复上次选择的模式（Task 58 持久化）：prefs 缺失/损坏回退 DEMO_OFFLINE（默认语义）。 */
    private fun restoreMode(): DemoMode {
        val name = runCatching {
            getApplication<Application>()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MODE, null)
        }.getOrNull()
        return runCatching { DemoMode.valueOf(name ?: "") }.getOrDefault(DemoMode.DEMO_OFFLINE)
    }

    /** 持久化当前模式（Task 58：重启/安装后保持选择，防云端链静默失联）。 */
    private fun persistMode(mode: DemoMode) {
        runCatching {
            getApplication<Application>()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, mode.name)
                .apply()
        }
    }

    /** 配置：按模式加载 assets 资产（demo-full.json / demo-offline.json），缺失或解析失败用内置默认。 */
    private fun loadConfig(mode: DemoMode): DemoConfig {
        val asset = if (mode == DemoMode.DEMO_FULL) ASSET_DEMO_FULL else ASSET_DEMO_OFFLINE
        val json = runCatching {
            getApplication<Application>().assets.open(asset).bufferedReader().use { it.readText() }
        }.getOrNull()
        if (json != null) {
            runCatching { DemoConfig.fromJson(json) }.onSuccess { return it }.onFailure {
                Log.w(TAG, "$asset 解析失败，使用内置默认配置", it)
            }
        }
        return defaultConfig(mode)
    }

    /**
     * 内置默认配置（防御兜底，Task 20 明文 + Task 21 模式化）：demo-full 云端优先；
     * demo-offline 仅本地（cloud 关闭、无网关地址）——资产缺失时模式语义仍正确。
     */
    private fun defaultConfig(mode: DemoMode): DemoConfig {
        val full =
            DemoConfig(
                mode = "full",
                vad = VadConfig(),
                ecnr = "rnnoise",
                local = LocalConfig(asr = "iflytek.fake-cmd", nlu = "rule.nlu"),
                cloud = CloudConfig(
                    enabled = true,
                    gatewayUrl = "ws://10.0.2.2:8080/ws",
                    waitMs = 2000,
                ),
                mock = MockConfig(),
            )
        return if (mode == DemoMode.DEMO_FULL) full
        else full.copy(mode = "offline", cloud = full.cloud.copy(enabled = false, gatewayUrl = ""))
    }

    override fun onCleared() {
        vehicleContext.stopRefresh()
        navigationDialogTimeoutJob?.cancel()
        recordingCoordinator.close()
        engine.close() // 断开网关 + 取消引擎作用域（Task 21）
        ttsPlayer.release()
        super.onCleared()
    }

    private companion object {
        const val TAG = "MainViewModel"

        /** 双模式配置资产（Task 21 落地；缺失时用 [defaultConfig] 兜底）。 */
        const val ASSET_DEMO_FULL = "demo-full.json"
        const val ASSET_DEMO_OFFLINE = "demo-offline.json"

        /** 模式持久化存储（Task 58：重启保持用户选择，防云端链静默失联）。 */
        const val PREFS_NAME = "autovoice_settings"
        const val KEY_MODE = "demo_mode"

        /** 竞速胜出方 UI 标志文本（Task 61）。 */
        const val WINNER_LOCAL = "端侧"
        const val WINNER_CLOUD = "云端"

        /** 与服务端候选状态一致：两分钟无选择自动收起应用内弹窗。 */
        const val NAVIGATION_DIALOG_TTL_MS = 120_000L
    }
}

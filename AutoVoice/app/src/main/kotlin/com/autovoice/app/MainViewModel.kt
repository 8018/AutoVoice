package com.autovoice.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autovoice.app.BuildConfig
import com.autovoice.adapterlocal.vad.VadEvent
import com.autovoice.app.audio.AudioRecorder
import com.autovoice.app.audio.TtsPlayer
import com.autovoice.voicecore.CloudConfig
import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.LocalConfig
import com.autovoice.voicecore.MockConfig
import com.autovoice.voicecore.VadConfig
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.session.SessionState
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
)

/**
 * 主 ViewModel（Task 19 + Task 20 接线；Task 50 按钮录音双路）：持有 [AudioRecorder]
 * （按住录音：VAD 切段 + 降噪整段）+ 装配好的 [VoiceEngine]（双链路竞速 + 播报/执行路由）
 * + [MockVehicleState] + UI 状态。
 *
 * Task 50 按钮双路接线（按下录音，抬手双路送识别；VAD 保留用于云端路段切分）：
 *  - 按下 → [startRecording]：清段缓冲 → 启动录音 → [VoiceEngine.onListeningStart]
 *    （网络可用则恢复云端路由，否则挂起云端）；
 *  - 按住期间：recorder 内部 [AudioRecorder.finishSegments] 实时切云端段（Silero VAD，
 *    Task 49），pcmBlocks 收集器把降噪 960B/块攒进 [denoisedBlocks]（本地整段）；
 *  - 抬手 → [stopRecording]：停止录音 → 先逐段 [VoiceEngine.onCloudSegment]（云端路），
 *    再把整段降噪 PCM 送 [VoiceEngine.onTurnSegment]（本地路，启动竞速）；
 *    整段 < 300ms（误触）丢弃不送识别，直接回 IDLE；
 *  - 录音中切模式 → [cancelRecording]：停录音、不送识别（引擎随后释放/重建）；
 *  - 会话状态 → [UiState.sessionState]；决策 sink → [MainViewModel.addDecision]；
 *  - 弱网开关 → engine.weakNetwork（云端人为延迟 3s，debug 构建）；
 *  - [onCleared] → recorder.close() + 播报/播放资源释放。
 *
 * PCM 块在 [viewModelScope] 收集，所有 UI 状态一律走 [uiState] StateFlow——
 * 回调协程/线程里不碰任何 Android 视图。
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
        engine.onTtsPlayEvent(stage, level, payload)
    }

    /** 端侧引擎：VoiceSession + 双链路竞速 + 播报/执行路由（Task 20）。 */
    private lateinit var engine: VoiceEngine

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 本地整段装配缓冲：按住期间收集的 960B 降噪块（Task 18 块格式；抬手后 concat）。 */
    private val denoisedBlocks = mutableListOf<ByteArray>()

    /** 当前是否按住录音中（按下置 true，抬手/中止置 false；pcmBlocks 收集器据此攒块）。 */
    @Volatile
    private var recording = false

    init {
        // 默认装配与设置区默认模式一致（Task 19/21）：DEMO_OFFLINE → demo-offline 资产。
        // Task 58：模式持久化（SharedPreferences）——重启/安装后保持用户上次选择；
        // 否则每次重启回 DEMO_OFFLINE（cloud.enabled=false），云端链关闭，表现为
        // "重启后云端失联"（每轮 cloud_unreachable、服务器零流量）。
        val mode = restoreMode()
        _uiState.update { it.copy(mode = mode) }
        engine = buildEngine(loadConfig(mode))
        viewModelScope.launch {
            recorder.pcmBlocks.collect { block ->
                if (recording) denoisedBlocks.add(block)
            }
        }
        // B1/B2：VAD 段事件 → vad_start/vad_end 插桩（SpeechStart 由 engine 产生本轮
        // utteranceId——vad start 的 uuid 就是 utteranceId，单一 id 贯穿全轮并同步仲裁器）
        viewModelScope.launch {
            recorder.vadEvents.collect { event ->
                when (event) {
                    VadEvent.SpeechStart -> engine.onVadStart()
                    VadEvent.SpeechEnd -> engine.onVadEnd()
                }
            }
        }
    }

    // ------------------------------------------------------------------ 按钮录音双路（Task 50）

    /**
     * 按住录音开始（RecordButton 按下；幂等）：清段缓冲 → 启动录音 → 会话进 LISTENING
     * （engine 按网络状态恢复/挂起云端路由）。权限缺失时 recorder 静默失败 → 提示授权。
     */
    fun startRecording() {
        if (recording) return
        if (!recorder.start()) {
            // 缺权限/创建失败时 recorder 静默降级（Log.w），这里提示用户授权
            _uiState.update { it.copy(permissionHint = true) }
            return
        }
        recording = true
        denoisedBlocks.clear()
        engine.onListeningStart()
        _uiState.update {
            it.copy(permissionHint = false, vadUnavailable = !recorder.vadAvailable)
        }
    }

    /**
     * 抬手结束录音（RecordButton 松开；幂等）：停止录音 → 先逐段喂云端路
     * （[AudioRecorder.finishSegments]：VAD 切段，时间顺序，必须先于本地路喂完）
     * → 本地整段（concat 全部降噪块）送 [VoiceEngine.onTurnSegment] 启动双路竞速。
     * 整段 < 300ms（瞬时噪声/误触）不送识别，直接回 IDLE。
     *
     * 回声抑制（Task 62）：云端/本地播报中（或刚播完 [ECHO_GUARD_MS] 内）按住录音，
     * 麦克风必然混入扬声器回声——整轮丢弃不送识别（回声会被云端 ASR 当成新指令，
     * 触发新一轮播报，形成"一直播报"循环）。
     */
    fun stopRecording() {
        if (!recording) return
        recording = false
        recorder.stop()
        if (ttsPlayer.isSpeaking(ECHO_GUARD_MS)) {
            val dropped = denoisedBlocks.sumOf { it.size }
            denoisedBlocks.clear()
            Log.i(TAG, "播报中/刚播完，丢弃本轮录音（回声抑制，${dropped}B）")
            engine.onListeningStop()
            return
        }
        val denoised = concatBlocks(denoisedBlocks)
        denoisedBlocks.clear()
        val cloudSegments = recorder.finishSegments()
        for (seg in cloudSegments) engine.onCloudSegment(seg)
        if (denoised.size >= MIN_SEGMENT_BYTES) {
            dumpLocalSegment(denoised) // Task 58 诊断：本地整段落盘，验证真实麦克风音频进了链路
            engine.onTurnSegment(denoised)
        } else {
            // 瞬时噪声/误触：不送识别不打扰
            Log.d(TAG, "录音过短（${denoised.size}B < ${MIN_SEGMENT_BYTES}B），丢弃不送识别")
            engine.onListeningStop()
        }
    }

    /** 中止录音（模式切换）：停止录音、清缓冲，不送识别（引擎随后释放/重建，幂等）。 */
    private fun cancelRecording() {
        if (!recording) return
        recording = false
        recorder.stop()
        denoisedBlocks.clear()
        engine.onListeningStop()
    }

    /** RECORD_AUDIO 权限被拒（Activity 回调）：显示提示，不开始录音。 */
    fun onPermissionDenied() {
        _uiState.update { it.copy(permissionHint = true) }
    }

    /** 清除权限提示（授权后、下次按下按钮时）。 */
    fun clearPermissionHint() {
        _uiState.update { it.copy(permissionHint = false) }
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
        cancelRecording() // 录音中切模式：停录音不送识别（引擎随后释放/重建，幂等）
        engine.close()
        engine = buildEngine(loadConfig(mode))
        engine.weakNetwork = _uiState.value.weakNetwork // 弱网开关跨引擎保持（Task 20）
        persistMode(mode) // Task 58：模式持久化，重启后保持
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

    /** 拼接段内 960B 降噪块为一段 PCM（16k 单声道 PCM16，云端/本地链的输入边界）。 */
    private fun concatBlocks(blocks: List<ByteArray>): ByteArray {
        var total = 0
        for (b in blocks) total += b.size
        val out = ByteArray(total)
        var offset = 0
        for (b in blocks) {
            b.copyInto(out, offset)
            offset += b.size
        }
        return out
    }

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
            player = AudioPlayer { reply ->
                if (reply.speakText.isNotBlank()) {
                    _uiState.update { s -> s.copy(lastReplyText = reply.speakText) }
                }
                ttsPlayer.play(reply)
            },
            vehicle = vehicleState,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onVehicleApplied = { _uiState.update { it.copy(vehicle = VehicleUiState.from(vehicleState)) } },
            onLocalRecognized = { text -> _uiState.update { it.copy(lastRecognizedText = text) } },
        )
        engine.session.onState { state ->
            _uiState.update { it.copy(sessionState = state) }
        }
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
        engine.close() // 断开网关 + 取消引擎作用域（Task 21）
        recorder.close()
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

        /** 最小语音段字节数（300ms @16k 16bit = 9600B；瞬时误触发过滤）。 */
        const val MIN_SEGMENT_BYTES = 9_600

        /** 竞速胜出方 UI 标志文本（Task 61）。 */
        const val WINNER_LOCAL = "端侧"
        const val WINNER_CLOUD = "云端"

        /** 回声抑制窗口（Task 62）：播报刚结束后此毫秒数内按录音也丢弃（残留回声）。 */
        const val ECHO_GUARD_MS = 500L
    }
}

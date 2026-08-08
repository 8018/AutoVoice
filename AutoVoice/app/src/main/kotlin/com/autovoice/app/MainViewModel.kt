package com.autovoice.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autovoice.adapterlocal.vad.VadEvent
import com.autovoice.app.audio.AudioRecorder
import com.autovoice.app.audio.SystemTtsFallback
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
 *   补 UNDERSTANDING/EXECUTING/SPEAKING）；
 * - [recording]：录音按钮态（按住录音 / 松开或 VAD 自动结束 → false）；
 * - [decisionLog]：决策日志（Task 19 种子为空，Task 20 仲裁器经 [MainViewModel.addDecision] 追加）；
 * - [vehicle]：模拟车控面板快照；
 * - [mode] / [weakNetwork]：设置区纯 UI 状态。
 */
data class UiState(
    val sessionState: SessionState = SessionState.IDLE,
    val recording: Boolean = false,
    val decisionLog: List<DecisionEntry> = emptyList(),
    val vehicle: VehicleUiState = VehicleUiState(),
    val mode: DemoMode = DemoMode.DEMO_OFFLINE,
    val weakNetwork: Boolean = false,
    val permissionHint: Boolean = false,
)

/**
 * 主 ViewModel（Task 19 + Task 20 接线）：持有 [AudioRecorder]（由 UI 驱动）+ 装配好的
 * [VoiceEngine]（双链路竞速 + 播报/执行路由）+ [MockVehicleState] + UI 状态。
 *
 * Task 20 接线：
 *  - 录音开始 → [VoiceEngine.onListeningStart]（网络可用则恢复云端路由）；
 *  - VAD SpeechEnd → 拼接 SpeechStart 至 SpeechEnd 的降噪 pcmBlocks（960B/块，Task 18）
 *    为段 PCM → [VoiceEngine.onVadSegment]；
 *  - 用户抬手/中止 → [VoiceEngine.onListeningStop] + recorder.stop()；
 *  - 会话状态 → [UiState.sessionState]；决策 sink → [MainViewModel.addDecision]；
 *  - 弱网开关 → engine.weakNetwork（云端人为延迟 3s，debug 构建）；
 *  - [onCleared] → recorder.close() + 播报/播放资源释放。
 *
 * VAD 事件与 PCM 块在 [viewModelScope] 收集，所有 UI 状态一律走 [uiState] StateFlow——
 * 回调协程/线程里不碰任何 Android 视图。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    /** 录音器（Task 19 由 UI 驱动；Task 20 的 VoiceSession 消费其 [AudioRecorder.pcmBlocks]）。 */
    private val recorder = AudioRecorder(application)

    /** 模拟车控执行器（Task 20 的 executor 经 [applyVehicleIntent] 路由到这里）。 */
    val vehicleState = MockVehicleState()

    /** 云端音频播放出口（生产实现：MediaPlayer + wav 临时文件）。 */
    private val ttsPlayer = TtsPlayer(application)

    /** 本地播报出口（生产实现：系统 TextToSpeech，离线兜底）。 */
    private val ttsFallback = SystemTtsFallback(application)

    /** 端侧引擎：VoiceSession + 双链路竞速 + 播报/执行路由（Task 20）。 */
    private lateinit var engine: VoiceEngine

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** VAD 段装配缓冲：SpeechStart 到 SpeechEnd 之间收集的 960B 降噪块（Task 18 块格式）。 */
    private val segmentBlocks = mutableListOf<ByteArray>()

    /** 当前是否在收集段 PCM（SpeechStart 置 true，SpeechEnd/中止置 false）。 */
    private var speechActive = false

    init {
        engine = VoiceEngine.create(
            cfg = loadConfig(),
            context = getApplication(),
            sink = DecisionSink { addDecision(it) },
            player = AudioPlayer { ttsPlayer.play(it) },
            speaker = TextSpeaker { text -> ttsFallback.speak(text) {} },
            vehicle = vehicleState,
            scope = viewModelScope,
            onVehicleApplied = { _uiState.update { it.copy(vehicle = VehicleUiState.from(vehicleState)) } },
        )
        engine.session.onState { state ->
            _uiState.update { it.copy(sessionState = state) }
        }
        viewModelScope.launch {
            recorder.vadEvents.collect { onVadEvent(it) }
        }
        viewModelScope.launch {
            recorder.pcmBlocks.collect { block ->
                if (speechActive) segmentBlocks.add(block)
            }
        }
    }

    // ------------------------------------------------------------------ 录音

    /** 开始录音（Activity 已确保 RECORD_AUDIO 已授权；按住说话触发）。 */
    fun startRecording() {
        if (_uiState.value.recording) return
        if (!recorder.start()) {
            // 缺权限/创建失败时 recorder 静默降级（Log.w），这里提示用户授权
            _uiState.update { it.copy(permissionHint = true) }
            return
        }
        setRecording(true)
        engine.onListeningStart()
    }

    /** 停止录音（松开按钮触发；幂等）。 */
    fun stopRecording() {
        recorder.stop()
        setRecording(false)
        speechActive = false
        segmentBlocks.clear()
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

    // ------------------------------------------------------------------ 设置（Task 19 纯 UI 状态）

    /** demo-full / demo-offline 切换；配置加载是 Task 21，这里只存 UI 状态。 */
    fun setMode(mode: DemoMode) {
        _uiState.update { it.copy(mode = mode) }
    }

    /** 模拟弱网（云端延迟 3s）开关；绑定到引擎的云端人为延迟 hook（Task 20，debug 构建）。 */
    fun setWeakNetwork(enabled: Boolean) {
        _uiState.update { it.copy(weakNetwork = enabled) }
        engine.weakNetwork = enabled
    }

    // ------------------------------------------------------------------ Task 20 接线点

    /** 仲裁器 sink：追加一条决策日志（端侧 on-device 仲裁 + 网关 decision 透传）。 */
    internal fun addDecision(entry: DecisionEntry) {
        _uiState.update { it.copy(decisionLog = it.decisionLog + entry) }
    }

    /** 车控意图执行：apply 后把执行器状态快照进 UiState；返回播报文本（未知 → null）。 */
    internal fun applyVehicleIntent(intent: Intent): String? =
        vehicleState.apply(intent)?.also {
            _uiState.update { s -> s.copy(vehicle = VehicleUiState.from(vehicleState)) }
        }

    // ------------------------------------------------------------------ 内部

    private fun onVadEvent(event: VadEvent) {
        when (event) {
            VadEvent.SpeechStart -> {
                // 段起点：开始收集降噪 PCM 块（Task 20）
                speechActive = true
                segmentBlocks.clear()
            }
            // VAD 自动结束：UI 状态回 IDLE，段 PCM 拼接后交给引擎竞速（Task 20）
            VadEvent.SpeechEnd -> {
                setRecording(false)
                speechActive = false
                val segment = concatBlocks(segmentBlocks)
                segmentBlocks.clear()
                if (segment.isNotEmpty()) {
                    engine.onVadSegment(segment)
                }
            }
        }
    }

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

    private fun setRecording(recording: Boolean) {
        _uiState.update {
            it.copy(
                recording = recording,
                sessionState = if (recording) SessionState.LISTENING else SessionState.IDLE,
                permissionHint = if (recording) false else it.permissionHint,
            )
        }
    }

    /** 配置：assets/demo-full.json（Task 21 落地）存在则解析，否则内置默认（Task 20 明文）。 */
    private fun loadConfig(): DemoConfig {
        val json = runCatching {
            getApplication<Application>().assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()
        if (json != null) {
            runCatching { DemoConfig.fromJson(json) }.onSuccess { return it }.onFailure {
                Log.w(TAG, "demo-full.json 解析失败，使用内置默认配置", it)
            }
        }
        return defaultConfig()
    }

    /** 内置默认配置（brief 明文）：demo-full，云端优先，本地 fake-cmd 链路。 */
    private fun defaultConfig(): DemoConfig =
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

    override fun onCleared() {
        recorder.close()
        ttsPlayer.release()
        ttsFallback.shutdown()
        super.onCleared()
    }

    private companion object {
        const val TAG = "MainViewModel"

        /** 全模式配置资产（Task 21 落地；缺失时用 [defaultConfig]）。 */
        const val CONFIG_ASSET = "demo-full.json"
    }
}

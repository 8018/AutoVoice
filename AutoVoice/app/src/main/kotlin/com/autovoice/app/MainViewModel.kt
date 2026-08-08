package com.autovoice.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autovoice.adapterlocal.vad.VadEvent
import com.autovoice.app.audio.AudioRecorder
import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.Intent
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
 * - [decisionLog]：决策日志（Task 19 种子为空，由 Task 20 仲裁器通过 [MainViewModel.addDecision] 追加）；
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
 * 主 ViewModel（Task 19）：持有 [AudioRecorder]（由 UI 驱动）+ [MockVehicleState] + UI 状态。
 *
 * VAD 事件在 [viewModelScope] 收集，所有 UI 状态一律走 [uiState] StateFlow——
 * 回调协程/线程里不碰任何 Android 视图。
 *
 * VoiceSession + GatewayClient + 引擎接线、段 PCM 装配、决策日志产出均为 Task 20。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    /** 录音器（Task 19 由 UI 驱动；Task 20 的 VoiceSession 将消费其 [AudioRecorder.pcmBlocks]）。 */
    private val recorder = AudioRecorder(application)

    /** 模拟车控执行器（Task 20 的 executor 经 [applyVehicleIntent] 路由到这里）。 */
    val vehicleState = MockVehicleState()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            recorder.vadEvents.collect { onVadEvent(it) }
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
    }

    /** 停止录音（松开按钮触发；幂等）。 */
    fun stopRecording() {
        recorder.stop()
        setRecording(false)
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

    /** 模拟弱网（云端延迟 3s）开关；3000ms 延迟 hook 是 Task 20 的。 */
    fun setWeakNetwork(enabled: Boolean) {
        _uiState.update { it.copy(weakNetwork = enabled) }
    }

    // ------------------------------------------------------------------
    // Task 20 接线点（本任务不调用）：
    //  - 段 PCM 装配 + VoiceSession 引擎调用将落在 [onVadEvent] 的 SpeechEnd 分支；
    //  - 仲裁结果经 [addDecision] / [applyVehicleIntent] 进入 UI。
    // ------------------------------------------------------------------

    /** 仲裁器 sink（Task 20）：追加一条决策日志；Task 19 种子为空，不产生条目。 */
    internal fun addDecision(entry: DecisionEntry) {
        _uiState.update { it.copy(decisionLog = it.decisionLog + entry) }
    }

    /** 车控意图执行（Task 20 的 executor 调用）：apply 后把执行器状态快照进 UiState；返回播报文本（未知 → null）。 */
    internal fun applyVehicleIntent(intent: Intent): String? =
        vehicleState.apply(intent)?.also {
            _uiState.update { s -> s.copy(vehicle = VehicleUiState.from(vehicleState)) }
        }

    // ------------------------------------------------------------------ 内部

    private fun onVadEvent(event: VadEvent) {
        when (event) {
            VadEvent.SpeechStart -> Unit // 段起点；Task 20 从这里开始收集 pcmBlocks
            // VAD 自动结束：UI 状态回到 IDLE。引擎调用（段装配 → VoiceSession → 仲裁）在 Task 20。
            VadEvent.SpeechEnd -> setRecording(false)
        }
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

    override fun onCleared() {
        recorder.close()
        super.onCleared()
    }
}

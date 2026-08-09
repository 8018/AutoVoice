package com.autovoice.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autovoice.app.DemoMode
import com.autovoice.app.UiState
import com.autovoice.voicecore.session.SessionState

/**
 * 主屏（Task 19）：会话状态头 + 车辆面板 + 决策日志 + 设置区 + 录音按钮。
 * 所有状态来自 [UiState] StateFlow，交互经回调进 ViewModel。
 */
@Composable
fun VoiceScreen(
    state: UiState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onModeChange: (DemoMode) -> Unit,
    onWeakNetworkChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header(state.sessionState)
        Spacer(Modifier.height(12.dp))
        VehiclePanel(state.vehicle, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        // Task 53 对话区：识别 + 回复各占一半主空间（仲裁结果只进 logcat，不再上屏）
        // Task 61：识别卡片标题行带竞速胜出徽标（端侧胜出 / 云端胜出，null = 尚无结果不显示）
        AsrResultCard(
            title = "识别",
            text = state.lastRecognizedText,
            winner = state.lastWinner,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Spacer(Modifier.height(12.dp))
        AsrResultCard(
            title = "回复",
            text = state.lastReplyText,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Spacer(Modifier.height(12.dp))
        SettingsSection(
            mode = state.mode,
            weakNetwork = state.weakNetwork,
            onModeChange = onModeChange,
            onWeakNetworkChange = onWeakNetworkChange,
        )
        if (state.permissionHint) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "需要录音权限才能使用语音控制，请在系统弹窗中允许",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.vadUnavailable) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "语音检测不可用（模型加载失败），语音控制失效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(12.dp))
        // Task 50 按钮回归：按住录音（VAD 保留，抬手后双路：云端段 + 本地整段）
        RecordButton(
            recording = state.recording,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
        )
    }
}

/**
 * 对话区卡片（Task 53）：识别/回复各一张，垂直平分主空间，等宽上下对齐。
 * [winner]（Task 61）：竞速胜出方徽标文本（"端侧"/"云端"，由 ViewModel 映射），
 * 非 null 时在标题行右侧显示「XX胜出」小徽标。
 */
@Composable
private fun AsrResultCard(
    title: String,
    text: String?,
    modifier: Modifier = Modifier,
    winner: String? = null,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                )
                if (winner != null) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = "${winner}胜出",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = text ?: "(${title}内容待更新)",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** 底部录音按钮：按住说话、松开停止（Task 50 按钮双路：抬手后音频分两路送识别）。 */
@Composable
private fun RecordButton(
    recording: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val container = if (recording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val content = if (recording) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    val ring = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .size(88.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onStartRecording()
                        // 阻塞到抬手（含手势取消）；无论成败都停止录音
                        tryAwaitRelease()
                        onStopRecording()
                    },
                )
            }
            .background(container, CircleShape)
            .border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (recording) "松开" else "按住说话",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = content,
        )
    }
}

@Composable
private fun Header(sessionState: SessionState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "AutoVoice 语音车控 Demo",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Surface(
            shape = CircleShape,
            color = if (sessionState == SessionState.IDLE) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ) {
            Text(
                text = sessionState.displayName(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SettingsSection(
    mode: DemoMode,
    weakNetwork: Boolean,
    onModeChange: (DemoMode) -> Unit,
    onWeakNetworkChange: (Boolean) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == DemoMode.DEMO_FULL,
                    onClick = { onModeChange(DemoMode.DEMO_FULL) },
                    label = { Text(DemoMode.DEMO_FULL.label) },
                )
                FilterChip(
                    selected = mode == DemoMode.DEMO_OFFLINE,
                    onClick = { onModeChange(DemoMode.DEMO_OFFLINE) },
                    label = { Text(DemoMode.DEMO_OFFLINE.label) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "模拟弱网（云端延迟 3s）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = weakNetwork, onCheckedChange = onWeakNetworkChange)
            }
        }
    }
}

/** 会话阶段展示文案。 */
private fun SessionState.displayName(): String = when (this) {
    SessionState.IDLE -> "空闲"
    SessionState.LISTENING -> "聆听中…"
    SessionState.UNDERSTANDING -> "理解中…"
    SessionState.EXECUTING -> "执行中…"
    SessionState.SPEAKING -> "播报中…"
}

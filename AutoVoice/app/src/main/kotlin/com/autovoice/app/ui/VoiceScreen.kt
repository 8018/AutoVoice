package com.autovoice.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autovoice.app.DemoMode
import com.autovoice.app.UiState
import com.autovoice.app.VoiceUiPhase

/** 语音交互首页。设置是二级页面，首页只展示真实状态与已有操作。 */
@Composable
fun VoiceScreen(
    state: UiState,
    onModeChange: (DemoMode) -> Unit,
    onWeakNetworkChange: (Boolean) -> Unit,
    onDismissNavigationCandidates: () -> Unit,
    onSelectNavigationCandidate: (com.autovoice.app.NavigationExecutor.NavigationCandidate) -> Unit,
) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(
            sessionState = state.sessionState,
            cloudPending = state.cloudPending,
            settingsOpen = settingsOpen,
            onToggleSettings = { settingsOpen = !settingsOpen },
        )
        if (settingsOpen) {
            SettingsSection(
                mode = state.mode,
                weakNetwork = state.weakNetwork,
                onModeChange = onModeChange,
                onWeakNetworkChange = onWeakNetworkChange,
            )
        } else {
            Text(
                text = "车辆概览",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VehiclePanel(state.vehicle, Modifier.fillMaxWidth())
            state.locationHint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            InteractionHeadline(state)
            ConversationCard(state)
            NavigationStatus(state)
            WakeGuidance(state)
        }
        if (state.permissionHint || state.vadUnavailable || state.wakeError != null) {
            Text(
                text = when {
                    state.permissionHint -> "需要录音权限，请在系统弹窗中允许麦克风访问。"
                    state.vadUnavailable -> "语音检测不可用，语音控制暂时失效。"
                    else -> "唤醒不可用：${state.wakeError}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    if (state.navigationCandidates.isNotEmpty()) {
        NavigationCandidateDialog(
            candidates = state.navigationCandidates,
            onDismissRequest = onDismissNavigationCandidates,
            onSelect = onSelectNavigationCandidate,
        )
    }
}

@Composable
private fun InteractionHeadline(state: UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (state.chatMode) "陪你聊会天" else when (state.sessionState) {
                VoiceUiPhase.IDLE -> "你好，飞飞在这里"
                VoiceUiPhase.LISTENING -> "我在听，请说"
                VoiceUiPhase.UNDERSTANDING -> "正在理解你的意思"
                VoiceUiPhase.EXECUTING -> "正在为你处理"
                VoiceUiPhase.SPEAKING -> "正在回复你"
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = when {
                state.chatMode -> "闲聊模式持续聆听；说“退出闲聊”返回普通指令。"
                state.recording -> "正在接收语音。"
                state.wakeListening && state.openMicBargeInAvailable -> "说“你好飞飞”唤醒；播报时也可直接说话打断。"
                state.wakeListening -> "说“你好飞飞”开始。"
                else -> "语音能力正在准备。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConversationCard(state: UiState) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("最近一次对话", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                state.lastReplyText?.takeIf { it.isNotBlank() } ?: "唤醒后，我会在这里回复你。",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
            state.lastRecognizedText?.takeIf { it.isNotBlank() }?.let { recognized ->
                Text(
                    "你说：$recognized",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.lastWinner?.let { winner ->
                Text("${winner}胜出", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun NavigationStatus(state: UiState) {
    val trip = state.navigation.trip ?: return
    val status = when (state.navigation.handoff) {
        com.autovoice.app.NavigationHandoff.OPENING -> "正在打开高德…"
        com.autovoice.app.NavigationHandoff.ACCEPTED -> "已交给高德：${trip.destination.name}"
        com.autovoice.app.NavigationHandoff.FAILED -> "未能打开高德，请确认已安装高德地图。"
        else -> "目的地：${trip.destination.name}"
    }
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(status, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WakeGuidance(state: UiState) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            if (state.chatMode) "持续聆听中" else if (state.wakeListening) "你好飞飞 · 随时可以说" else "等待语音就绪",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun NavigationCandidateDialog(
    candidates: List<com.autovoice.app.NavigationExecutor.NavigationCandidate>,
    onDismissRequest: () -> Unit,
    onSelect: (com.autovoice.app.NavigationExecutor.NavigationCandidate) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("请选择导航目的地") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                candidates.forEachIndexed { index, candidate ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(candidate) },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                text = "${index + 1}. ${candidate.poiname}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (candidate.address.isNotBlank()) {
                                Text(
                                    text = candidate.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Text(
                text = "请说“第几个”或具体地址名称",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        },
    )
}

@Composable
private fun Header(
    sessionState: VoiceUiPhase,
    cloudPending: Boolean,
    settingsOpen: Boolean,
    onToggleSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (settingsOpen) "设置" else "AutoVoice",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (!settingsOpen) {
            Surface(
                shape = CircleShape,
                color = if (sessionState == VoiceUiPhase.IDLE) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            ) {
                Text(
                    text = if (cloudPending && sessionState == VoiceUiPhase.UNDERSTANDING) "处理中…"
                    else sessionState.displayName(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        TextButton(onClick = onToggleSettings) { Text(if (settingsOpen) "返回" else "设置") }
    }
}

@Composable
private fun SettingsSection(
    mode: DemoMode,
    weakNetwork: Boolean,
    onModeChange: (DemoMode) -> Unit,
    onWeakNetworkChange: (Boolean) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "识别模式",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "在线模式可使用云端语音服务；离线模式仅使用本地能力。切换模式会重建语音连接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Text(
                "弱网模拟仅供调试，正常使用请保持关闭。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 会话阶段展示文案。 */
private fun VoiceUiPhase.displayName(): String = when (this) {
    VoiceUiPhase.IDLE -> "空闲"
    VoiceUiPhase.LISTENING -> "聆听中…"
    VoiceUiPhase.UNDERSTANDING -> "理解中…"
    VoiceUiPhase.EXECUTING -> "执行中…"
    VoiceUiPhase.SPEAKING -> "播报中…"
}

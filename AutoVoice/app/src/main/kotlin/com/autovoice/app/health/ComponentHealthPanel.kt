package com.autovoice.app.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.autovoice.voicecore.health.*

internal fun componentName(id: String) = when (id) {
    "config" -> "配置";"capture" -> "麦克风";"iflytek.runtime" -> "语音授权";"wake" -> "语音唤醒"
    "frontend" -> "音频处理";"vad" -> "语音分段";"vad.aux" -> "续听与打断检测";"render_reference" -> "播放参考";"aec" -> "回声消除"
    "local.asr" -> "本地识别";"local.nlu" -> "本地语义";"cloud" -> "云端连接";"playback" -> "语音播放"
    else -> "组件"
}
internal fun lifecycleLabel(status: ComponentStatus) = when (status.lifecycle) {
    Lifecycle.NOT_STARTED -> "尚未触发";Lifecycle.INITIALIZING -> "准备中";Lifecycle.FAILED -> "初始化失败"
    Lifecycle.BLOCKED -> "等待依赖";Lifecycle.DISABLED -> if (status.descriptor.provider == "iflytek.fake-cmd") "显式测试模式" else "未启用"
    Lifecycle.RELEASED -> "已释放";Lifecycle.READY -> when (status.health) {
        Health.UNAVAILABLE -> "暂不可用";Health.DEGRADED -> "部分受限";Health.UNKNOWN -> "已初始化 · 当前空闲";Health.HEALTHY -> "就绪"
    }
}

internal fun componentImpact(id: String) = when (id) {
    "capture" -> "影响语音输入"
    "iflytek.runtime" -> "影响语音唤醒及所选讯飞识别"
    "wake" -> "影响语音唤醒"
    "frontend" -> "影响处理后的语音输入"
    "vad" -> "影响语音分段与自动结束"
    "vad.aux", "aec", "render_reference" -> "影响续听或普通话术打断"
    "local.asr", "local.nlu" -> "影响本地识别候选"
    "cloud" -> "影响云端语音和文字路由"
    "playback" -> "影响语音播报，回复文字仍可显示"
    else -> "影响当前渠道装配"
}

@Composable internal fun ComponentHealthPanel(snapshot: ComponentHealthSnapshot, alwaysExpanded: Boolean = false) {
    var expanded by remember { mutableStateOf(alwaysExpanded) }
    var showNormal by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起组件状态" else "组件状态") }
        if (expanded) {
            Text("组件状态", style = MaterialTheme.typography.titleMedium)
            for (status in snapshot.components.values) {
                val normal = status.lifecycle == Lifecycle.READY && status.health !in setOf(Health.UNAVAILABLE, Health.DEGRADED)
                if (normal && !showNormal) continue
                Column {
                    Text("${componentName(status.componentId)}：${lifecycleLabel(status)}", style = MaterialTheme.typography.bodyMedium)
                    if (!normal) Text(componentImpact(status.componentId), style = MaterialTheme.typography.bodySmall)
                    status.error?.sanitized()?.let { Text("${it.message}。${it.suggestion}", style = MaterialTheme.typography.bodySmall) }
                    status.rootCauseId?.takeIf { it != status.componentId }?.let { Text("受${componentName(it)}影响", style = MaterialTheme.typography.bodySmall) }
                    if (status.stage == "reuse_existing") Text("沿用已有实例", style = MaterialTheme.typography.bodySmall)
                    status.durationMs?.let { Text("准备耗时 ${it}ms", style = MaterialTheme.typography.bodySmall) }
                }
            }
            TextButton(onClick = { showNormal = !showNormal }) { Text(if (showNormal) "折叠正常组件" else "显示正常组件") }
            TextButton(onClick = { clipboard.setText(AnnotatedString(ComponentLogSink.diagnosticsJson(snapshot))) }) { Text("复制脱敏诊断") }
            if (snapshot.droppedLogEvents > 0) Text("诊断事件队列已合并/省略 ${snapshot.droppedLogEvents} 项", style = MaterialTheme.typography.bodySmall)
        }
    }
}

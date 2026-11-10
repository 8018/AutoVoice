package com.autovoice.app.health

import com.autovoice.voicecore.health.*

data class CapabilitySummary(val features: Map<String, CapabilityAvailability> = emptyMap(), val summary: String = "",
    val canTriggerManual: Boolean = true, val configUsable: Boolean = false, val canSubmitText: Boolean = false)
internal object CapabilityEvaluator {
    fun evaluate(snapshot: ComponentHealthSnapshot, online: Boolean, auxiliaryBridgeAvailable: Boolean = true, localBridgeAvailable: Boolean = true, cloudRouteEnabled: Boolean = online, capturePathUnavailable: Boolean = false, openMicDeviceValidated: Boolean = true): CapabilitySummary {
        val components = snapshot.components
        fun failed(id: String) = components[id]?.let { it.lifecycle in setOf(Lifecycle.FAILED, Lifecycle.RELEASED) ||
            (it.lifecycle != Lifecycle.BLOCKED && it.health == Health.UNAVAILABLE) ||
            (it.lifecycle == Lifecycle.BLOCKED && components[it.rootCauseId]?.let { c -> c.lifecycle in setOf(Lifecycle.FAILED, Lifecycle.RELEASED) || c.health == Health.UNAVAILABLE } == true) } == true
        fun evaluate(vararg ids: String): CapabilityAvailability {
            val values = ids.mapNotNull(components::get)
            if (ids.any(::failed)) return CapabilityAvailability.UNAVAILABLE
            if (values.any { it.lifecycle == Lifecycle.DISABLED }) return CapabilityAvailability.DISABLED
            if (values.size < ids.size || values.any { it.lifecycle != Lifecycle.READY }) return CapabilityAvailability.PENDING
            return if (values.any { it.health == Health.DEGRADED }) CapabilityAvailability.LIMITED else CapabilityAvailability.AVAILABLE
        }
        val features = mapOf("wake" to evaluate("wake"), "local" to if (capturePathUnavailable) CapabilityAvailability.UNAVAILABLE else if (localBridgeAvailable) evaluate("frontend", "local.asr", "local.nlu") else CapabilityAvailability.UNAVAILABLE,
            "cloud" to if (online && cloudRouteEnabled) { if (capturePathUnavailable) CapabilityAvailability.UNAVAILABLE else evaluate("frontend", "vad", "cloud") } else CapabilityAvailability.DISABLED,
            "playback" to evaluate("playback"), "barge_in" to if (auxiliaryBridgeAvailable && !capturePathUnavailable && openMicDeviceValidated) evaluate("frontend", "aec", "vad.aux") else CapabilityAvailability.UNAVAILABLE,
            "follow_up" to if (auxiliaryBridgeAvailable && !capturePathUnavailable) evaluate("frontend", "vad.aux") else CapabilityAvailability.UNAVAILABLE,
            "text" to if (online && cloudRouteEnabled) evaluate("config", "cloud") else CapabilityAvailability.DISABLED)
        val configFailed = failed("config")
        val failures = listOf("capture" to "麦克风", "local.asr" to "本地识别", "frontend" to "音频处理", "vad" to "语音分段", "wake" to "语音唤醒", "playback" to "语音播放")
            .filter { failed(it.first) }.map { it.second }.distinct()
        val summary = when {
            configFailed -> "配置暂不可用，请查看组件状态"
            components["capture"]?.error?.code == ErrorCode.MIC_PERMISSION_DENIED -> "需要录音权限，请允许麦克风访问"
            capturePathUnavailable -> "语音处理暂不可用，请查看组件状态"
            failures.isNotEmpty() -> "语音功能受限：${failures.joinToString("、")}暂不可用"
            components.values.any { it.lifecycle == Lifecycle.INITIALIZING } -> "语音组件准备中"
            !localBridgeAvailable -> "本地语音尚未就绪"
            else -> ""
        }
        return CapabilitySummary(features, summary, !configFailed && !failed("frontend") && !capturePathUnavailable, components["config"]?.lifecycle == Lifecycle.READY,
            online && cloudRouteEnabled && components["config"]?.lifecycle == Lifecycle.READY)
    }
}

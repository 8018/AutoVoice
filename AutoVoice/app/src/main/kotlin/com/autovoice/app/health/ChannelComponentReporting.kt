package com.autovoice.app.health

import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.health.*

/** Small assembly wiring, not a second component owner. Actual adapters decide when to report. */
internal class ChannelComponentReporting(val registry: ComponentStatusRegistry, val channel: String, val generation: Long) {
    private val providers = linkedMapOf("config" to "application", "capture" to "android", "iflytek.runtime" to "aikit",
        "wake" to "iflytek.ivw", "frontend" to if (channel == "open") "webrtc.apm" else "rnnoise",
        "vad" to if (channel == "open") "webrtc.vad" else "silero",
        "vad.aux" to if (channel == "open") "webrtc.vad" else "silero", "render_reference" to "pcm.render",
        "aec" to if (channel == "open") "webrtc.apm" else "system.aec", "local.asr" to if (channel == "open") "firered.ctc.int8" else "iflytek.offline",
        "local.nlu" to if (channel == "open") "rule.text" else "rule.nlu", "cloud" to "gateway", "playback" to "android")
    fun registerDefaults() { providers.keys.forEach { reporter(it) } }
    fun reporter(id: String, provider: String? = null): ComponentReporter {
        if (provider != null) providers[id] = provider
        val deps = when (id) {
            "config", "iflytek.runtime" -> emptySet()
            "wake" -> setOf("config", "iflytek.runtime")
            "local.asr" -> if (channel == "iflytek") setOf("config", "iflytek.runtime") else setOf("config")
            "vad", "vad.aux", "aec" -> if (channel == "open") setOf("config", "frontend") else setOf("config")
            else -> setOf("config")
        }
        return registry.reporter(ComponentDescriptor(id, providers.getValue(id), if (id == "iflytek.runtime") "shared" else channel,
            if (id == "iflytek.runtime") ComponentScope.PROCESS else ComponentScope.ASSEMBLY), generation, deps)
    }
    fun readConfig(read: () -> DemoConfig): DemoConfig? {
        val r = reporter("config");val token = r.start("config_validate")
        return try { read().also { r.report(token, ComponentUpdate.ready()) } }
        catch (error: Throwable) {
            error.rethrowCancellationOrFatal()
            val code = if (error.message?.contains("unsupported") == true) ErrorCode.PROVIDER_MISMATCH else ErrorCode.CONFIG_INVALID
            r.report(token, ComponentUpdate.failed(ComponentError(code)));null
        }
    }
    fun runtimeBlocked(): Boolean = registry.snapshot.value.components["iflytek.runtime"]?.let {
        it.lifecycle == Lifecycle.FAILED || it.health == Health.UNAVAILABLE
    } == true
    fun failure(r: ComponentReporter, token: ComponentToken, error: Throwable, fallback: ErrorCode = ErrorCode.COMPONENT_RUNTIME_FAILED, runtimeDependent: Boolean = false) {
        error.rethrowCancellationOrFatal()
        r.report(token, if (runtimeDependent && runtimeBlocked()) ComponentUpdate.blocked("iflytek.runtime") else ComponentUpdate.failed(initializationError(error, fallback)))
    }
    fun unavailable(id: String, code: ErrorCode, stage: String = "native_load") {
        val r = reporter(id);r.report(r.start(stage), ComponentUpdate.failed(ComponentError(code)))
    }
    fun disabled(id: String) { val r = reporter(id);r.report(r.start("disabled"), ComponentUpdate.disabled()) }
    fun preparePlayback() {
        if (channel == "iflytek") disabled("render_reference")
    }
}

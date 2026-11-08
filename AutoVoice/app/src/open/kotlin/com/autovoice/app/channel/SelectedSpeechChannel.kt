package com.autovoice.app.channel

import android.content.Context
import android.media.MediaRecorder
import com.autovoice.app.health.ChannelComponentReporting
import com.autovoice.voicecore.health.*
import com.autovoice.adapterfirered.FireRedAvailability
import com.autovoice.adapterwebrtc.WebRtcFrontend
import com.autovoice.audiofrontend.*
import com.autovoice.voicecore.*
import kotlinx.coroutines.CoroutineScope

internal object SelectedSpeechChannel {
    fun create(): SpeechChannel = OpenSpeechChannel()
}

private class OpenSpeechChannel : SpeechChannel {
    override val id = "open"
    private var reporting: ChannelComponentReporting? = null
    override fun attachReporting(reporting: ChannelComponentReporting) { this.reporting = reporting }
    override val frontendFactory = AudioFrontendEngineFactory { _, config, _ ->
        WebRtcFrontend.create(config,reporting?.reporter("frontend") ?: ComponentReporter.none(),
            reporting?.reporter("vad") ?: ComponentReporter.none(),reporting?.reporter("vad.aux") ?: ComponentReporter.none(),
            reporting?.reporter("aec") ?: ComponentReporter.none())
    }
    override val vadFactory = VadEngineFactory { null }
    // Open APM owns EC/NS; never add Android system AEC/NS in parallel.
    override val capturePolicy = CapturePolicy(MediaRecorder.AudioSource.MIC, CaptureEffectsFactory {
        object : CaptureEffects {
            override val echoCancellationActive = false
            override val noiseSuppressionActive = false
            override fun attach(audioSessionId: Int) = Unit
            override fun release() = Unit
        }
    })
    override val capabilities = SpeechChannelCapabilities(
        auxiliaryBridgeAvailable = false,
        localAsrUnavailableReason = FireRedAvailability.UNAVAILABLE_REASON,
        renderReferenceUnavailableReason = "PCM playback reference driver is not installed",
    )
    override fun validateConfig(config: DemoConfig) = config.validateProviders(
        frontend = setOf("webrtc.apm"), vad = setOf("webrtc.vad"), ecnr = setOf("none"),
        asr = setOf("firered.ctc.int8"), nlu = setOf("rule.text"),
    ).also { WebRtcFrontend.validateVadConfig(it.vad) }
    override fun missingAssetConfig(asset: String, onlineAsset: String): DemoConfig =
        throw IllegalStateException("Open channel asset '$asset' is missing; no fallback is permitted")

    override fun createLocalEngines(context: Context, config: DemoConfig, scope: CoroutineScope): LocalEngineAssembly {
        validateConfig(config)
        reporting?.unavailable("local.asr", ErrorCode.NATIVE_LIBRARY_MISSING)
        reporting?.unavailable("local.nlu", ErrorCode.IMPLEMENTATION_UNAVAILABLE, "create")
        return LocalEngineAssembly(
            asr = FireRedAvailability.createUnavailable(),
            nlu = NluEngine { _, _, _ -> throw IllegalStateException("Text rule NLU implementation is not installed") },
        )
    }
}

package com.autovoice.app.channel

import android.content.Context
import android.media.MediaRecorder
import com.autovoice.app.health.ChannelComponentReporting
import com.autovoice.voicecore.health.*
import com.autovoice.adapterfirered.FireRedAsrEngine
import com.autovoice.adapterfirered.FireRedAdmissionProvider
import com.autovoice.voiceengine.local.TextRuleNluEngine
import com.autovoice.adapterwebrtc.WebRtcFrontend
import com.autovoice.audiofrontend.*
import com.autovoice.voicecore.*
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicReference
import com.autovoice.app.AudioPlayer
import com.autovoice.app.audio.AndroidPlaybackPcmPlatform
import com.autovoice.app.audio.OpenPcmPlaybackDriver
import com.autovoice.tts.TtsPlaybackDriver

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
    @Volatile private var admissionBound = false
    override val capabilities get() = SpeechChannelCapabilities(
        auxiliaryBridgeAvailable = admissionBound,
        localBridgeAvailable = admissionBound,
        openMicDeviceValidated = false,
        renderReferenceUnavailableReason = null,
    )
    override fun validateConfig(config: DemoConfig) = config.validateProviders(
        frontend = setOf("webrtc.apm"), vad = setOf("webrtc.vad"), ecnr = setOf("none"),
        asr = setOf("firered.ctc.int8"), nlu = setOf("rule.text"),
    ).also { WebRtcFrontend.validateVadConfig(it.vad) }
    override fun missingAssetConfig(asset: String, onlineAsset: String): DemoConfig =
        throw IllegalStateException("Open channel asset '$asset' is missing; no fallback is permitted")

    override fun createPlaybackDriver(player: AudioPlayer,ports: PlaybackDriverPorts?): TtsPlaybackDriver {
        val bound = requireNotNull(ports) { "Open playback ports are required" }
        return OpenPcmPlaybackDriver(AndroidPlaybackPcmPlatform(bound.context),bound.scope,bound.render,bound.events,
            bound.playbackReporter,bound.referenceReporter)
    }

    override fun createLocalEngines(context: Context, config: DemoConfig, scope: CoroutineScope): LocalEngineAssembly {
        validateConfig(config)
        admissionBound = false
        val admission = OpenRequestAdmissionBinding()
        val asr = FireRedAsrEngine.create(
            requireNotNull(context.getExternalFilesDir("speech-models")),
            // Task6 binds Coordinator-owned admission facts. Missing facts must deny native decode.
            admission.nativeProvider,reporting?.reporter("local.asr") ?: ComponentReporter.none())
        val nluReporter = reporting?.reporter("local.nlu") ?: ComponentReporter.none()
        val token = nluReporter.start("create")
        val nlu = TextRuleNluEngine()
        nluReporter.report(token,ComponentUpdate.ready())
        return LocalEngineAssembly(asr,nlu,bindAdmission = { owner -> admission.bind(owner);admissionBound = true }) {
            admission.close();admissionBound = false;asr.close();nluReporter.close(token)
        }
    }
}

/** The native worker reads only atomic publication and original owner facts, never engineRef. */
internal class OpenRequestAdmissionBinding : AutoCloseable {
    private val provider = AtomicReference<LocalRequestAdmissionProvider?>(null)
    fun bind(owner: LocalRequestAdmissionProvider) { provider.set(owner) }
    val nativeProvider = FireRedAdmissionProvider { id -> provider.get()?.let { owner -> owner.capture(id)?.let { facts ->
        com.autovoice.adapterfirered.FireRedAdmission(facts.deadlineMs,
            { provider.get() === owner && facts.ownerLive() && facts.finalized() },
            { provider.get() === owner && facts.ownerLive() && facts.originalPermitValid() })
    } } }
    override fun close() { provider.set(null) }
}

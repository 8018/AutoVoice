package com.autovoice.app.channel

import android.content.Context
import com.autovoice.app.AudioPlayer
import com.autovoice.audiofrontend.AudioFrontendEngineFactory
import com.autovoice.audiofrontend.CaptureEffectsFactory
import com.autovoice.audiofrontend.VadEngineFactory
import com.autovoice.tts.TtsPlaybackDriver
import com.autovoice.voicecore.*
import kotlinx.coroutines.CoroutineScope
import java.io.FileNotFoundException

/** Known missing implementations at assembly time; runtime model/auth status remains adapter-owned. */
internal data class SpeechChannelCapabilities(
    val frontendUnavailableReason: String? = null,
    val localAsrUnavailableReason: String? = null,
    val renderReferenceUnavailableReason: String? = null,
    val auxiliaryBridgeAvailable: Boolean = true,
    val localBridgeAvailable: Boolean = true,
)

internal data class CapturePolicy(val audioSource: Int, val effectsFactory: CaptureEffectsFactory)

internal class LocalEngineAssembly(
    val asr: AsrEngine,
    val nlu: NluEngine<NluResult>,
    private val release: () -> Unit = {},
) : AutoCloseable {
    override fun close() = release()
}

/** App composition contract: provider types never escape the selected flavor. */
internal interface SpeechChannel {
    val id: String
    fun attachReporting(reporting: com.autovoice.app.health.ChannelComponentReporting)
    val frontendFactory: AudioFrontendEngineFactory
    val vadFactory: VadEngineFactory
    val capturePolicy: CapturePolicy
    val capabilities: SpeechChannelCapabilities
    fun validateConfig(config: DemoConfig): DemoConfig
    fun missingAssetConfig(asset: String, onlineAsset: String): DemoConfig
    fun createLocalEngines(context: Context, config: DemoConfig, scope: CoroutineScope): LocalEngineAssembly

    // Only adapts the platform driver port; all identity/cache/stop policy remains in :tts.
    fun createPlaybackDriver(player: AudioPlayer): TtsPlaybackDriver = object : TtsPlaybackDriver {
        override fun play(reply: AudioReply, identity: com.autovoice.tts.PlaybackIdentity) = player.play(reply, identity)
        override suspend fun playStream(reply: StreamingAudioReply, identity: com.autovoice.tts.PlaybackIdentity) =
            player.playStream(reply, identity)
        override fun stop() = player.stop()
    }
}

internal fun DemoConfig.validateProviders(
    frontend: Set<String>, vad: Set<String>, ecnr: Set<String>, asr: Set<String>, nlu: Set<String>,
): DemoConfig {
    validateForRuntime()
    for ((field, value, supported) in listOf(
        Triple("frontend", this.frontend, frontend), Triple("vad.provider", this.vad.provider, vad),
        Triple("ecnr", this.ecnr, ecnr), Triple("local.asr", local.asr, asr), Triple("local.nlu", local.nlu, nlu),
    )) require(value in supported) { "config: unsupported $field '$value'; supported: ${supported.joinToString()}" }
    return this
}

/** A missing asset is the sole legacy fallback trigger. Invalid JSON/providers always fail closed. */
internal fun loadChannelConfig(
    channel: SpeechChannel,
    asset: String,
    onlineAsset: String,
    authToken: String,
    readAsset: () -> String,
    onMissingAsset: () -> Unit = {},
): DemoConfig = try {
    var usedFallback = false
    val config = try {
        DemoConfig.fromJson(readAsset())
    } catch (missing: FileNotFoundException) {
        onMissingAsset()
        usedFallback = true
        channel.missingAssetConfig(asset, onlineAsset)
    }
    val validated = channel.validateConfig(config)
    if (usedFallback || (asset != "demo-offline.json" && authToken.isNotBlank())) {
        validated.copy(cloud = validated.cloud.copy(authToken = authToken))
    } else validated
} catch (error: Exception) {
    throw IllegalStateException("$asset 配置不可用，拒绝装配: ${error.message}", error)
}

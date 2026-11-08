package com.autovoice.app.channel

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import com.autovoice.adapteriflytek.IflytekOfflineCommandAsrStage
import com.autovoice.adapteriflytek.RuleNluProvider
import com.autovoice.app.BuildConfig
import com.autovoice.app.recognizeLocalCommand
import com.autovoice.audiofrontend.*
import com.autovoice.voicecore.*
import com.autovoice.voiceengine.local.LocalAsrEngine
import com.autovoice.voiceengine.local.LocalNluEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal object SelectedSpeechChannel { fun create(): SpeechChannel = IflytekSpeechChannel() }

/** Narrow SDK lifecycle boundary, so assembly tests do not initialize native libraries. */
internal interface IflytekCommand : AutoCloseable {
    fun initialize(context: Context)
    fun recognize(segment: ByteArray): String?
}

internal class IflytekSpeechChannel(
    private val initializationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val createCommand: () -> IflytekCommand = {
        val stage = IflytekOfflineCommandAsrStage(BuildConfig.XFYUN_APPID, BuildConfig.XFYUN_API_KEY, BuildConfig.XFYUN_API_SECRET)
        object : IflytekCommand {
            override fun initialize(context: Context) = stage.init(context)
            override fun recognize(segment: ByteArray) = stage.recognize(segment)
            override fun close() = stage.release()
        }
    },
) : SpeechChannel {
    override val id = "iflytek"
    override val frontendFactory = AudioFrontendEngineFactory(AudioFrontendFactory::create)
    override val vadFactory = VadEngineFactory(AudioFrontendFactory::createVad)
    override val capturePolicy = CapturePolicy(MediaRecorder.AudioSource.VOICE_COMMUNICATION, CaptureEffectsFactory(::AndroidCaptureEffects))
    override val capabilities = SpeechChannelCapabilities()

    override fun validateConfig(config: DemoConfig) = config.validateProviders(
        frontend = setOf("legacy"), vad = setOf("silero"), ecnr = setOf("rnnoise", "none"),
        asr = setOf("iflytek.offline", "iflytek.fake-cmd"), nlu = setOf("rule.nlu"),
    )

    // Preserve historical Demo fallback only for missing assets; never for invalid config or SDK failure.
    override fun missingAssetConfig(asset: String, onlineAsset: String): DemoConfig {
        val dev = onlineAsset == "demo-dev.json"
        val full = DemoConfig(
            mode = if (dev) "dev" else "full", vad = VadConfig(), ecnr = "rnnoise",
            local = LocalConfig("iflytek.fake-cmd", "rule.nlu"),
            cloud = CloudConfig(true, if (dev) "ws://8.153.153.77:8090/ws" else "ws://47.94.4.204:8080/ws", 2000),
            mock = MockConfig(),
        )
        return if (asset == "demo-offline.json") full.copy(mode = "offline", cloud = full.cloud.copy(enabled = false, gatewayUrl = "")) else full
    }

    override fun createLocalEngines(context: Context, config: DemoConfig, scope: CoroutineScope): LocalEngineAssembly {
        validateConfig(config)
        val command = if (config.local.asr == DemoConfig.LOCAL_ASR_IFLYTEK) createCommand() else null
        if (command != null) scope.launch(initializationDispatcher) {
            try {
                command.initialize(context)
                Log.i(TAG, "讯飞离线命令词初始化完成（授权通过）")
            } catch (error: Throwable) {
                Log.w(TAG, "讯飞离线命令词初始化失败，本地真实候选保持不可用", error)
            }
        }
        val nlu = LocalNluEngine(
            recognizeCommand = { segment -> recognizeLocalCommand(config.local.asr, segment) { command?.recognize(segment) } },
            understandCommand = RuleNluProvider::understand,
            onRecognizerFailure = { Log.w(TAG, "本地 2C 命令词异常，按未命中继续", it) },
        )
        return LocalEngineAssembly(LocalAsrEngine(), nlu) { command?.close() }
    }

    private companion object { const val TAG = "VoiceEngineFactory" }
}

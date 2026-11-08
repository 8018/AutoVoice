package com.autovoice.app.channel

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import com.autovoice.adapteriflytek.IflytekOfflineCommandAsrStage
import com.autovoice.adapteriflytek.RuleNluProvider
import com.autovoice.app.BuildConfig
import com.autovoice.app.health.ChannelComponentReporting
import com.autovoice.voicecore.health.*
import com.autovoice.app.recognizeLocalCommand
import com.autovoice.audiofrontend.*
import com.autovoice.voicecore.*
import com.autovoice.voiceengine.local.LocalAsrEngine
import com.autovoice.voiceengine.local.LocalNluEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
    private var reporting: ChannelComponentReporting? = null
    override fun attachReporting(reporting: ChannelComponentReporting) { this.reporting = reporting }
    override val frontendFactory = AudioFrontendEngineFactory { context, config, ecnr ->
        val frontend = reporting?.reporter("frontend", if (ecnr == "none") "none" else "rnnoise") ?: ComponentReporter.none()
        val vad = reporting?.reporter("vad") ?: ComponentReporter.none()
        AudioFrontendFactory.create(context, config, ecnr, frontend, vad)
    }
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
        val health = reporting
        val asrReporter = health?.reporter("local.asr", config.local.asr) ?: ComponentReporter.none()
        val nluReporter = health?.reporter("local.nlu") ?: ComponentReporter.none()
        val nluToken = nluReporter.start("parameters")
        nluReporter.report(nluToken, ComponentUpdate.ready())
        val token = asrReporter.start("initialize")
        val fake = config.local.asr == DemoConfig.LOCAL_ASR_FAKE
        var command: IflytekCommand? = null
        if (fake) asrReporter.report(token, ComponentUpdate.disabled()) else {
            try { command = createCommand() }
            catch (error: Throwable) {
                error.rethrowCancellationOrFatal()
                health?.failure(asrReporter, token, error) ?: asrReporter.report(token, ComponentUpdate.failed(initializationError(error)))
            }
        }
        val lease = if (command != null) synchronized(COMMAND_TAIL_LOCK) {
            val previous = commandTail
            val current = CompletableDeferred<Unit>()
            commandTail = current
            previous to current
        } else null
        val cleanupScope = CoroutineScope(SupervisorJob() + initializationDispatcher)
        val lifecycleLock = Any()
        var initializationStarted = false
        var closeRequested = false
        var initializationFinished = command == null
        var released = false
        var ready = false
        var activeUses = 0
        var usesFinished: CompletableDeferred<Unit>? = null
        suspend fun releaseCommand() {
            // A cancelled queued owner must preserve the entire predecessor chain.
            lease?.first?.await()
            val waitingUse = synchronized(lifecycleLock) {
                if (activeUses == 0) null else usesFinished ?: CompletableDeferred<Unit>().also { usesFinished = it }
            }
            waitingUse?.await()
            val shouldRelease = synchronized(lifecycleLock) { if (released) false else { released = true;true } }
            if (shouldRelease) try { command?.close() } finally {
                lease?.second?.complete(Unit)
                cleanupScope.cancel()
            }
        }
        val initializationJob = if (command != null) scope.launch(initializationDispatcher) {
                lease?.first?.await()
                synchronized(lifecycleLock) { initializationStarted = true }
                try {
                    if (!synchronized(lifecycleLock) { closeRequested }) {
                        command!!.initialize(context.applicationContext ?: context)
                        synchronized(lifecycleLock) {
                            if (!closeRequested) { ready = true;asrReporter.report(token, ComponentUpdate.ready()) }
                        }
                    }
                } catch (error: Throwable) {
                    error.rethrowCancellationOrFatal()
                    health?.failure(asrReporter, token, error, runtimeDependent = true)
                        ?: asrReporter.report(token, ComponentUpdate.failed(initializationError(error)))
                    Log.w(TAG, "讯飞离线命令词初始化失败，本地真实候选保持不可用")
                } finally {
                    val close = synchronized(lifecycleLock) { initializationFinished = true;closeRequested }
                    if (close) releaseCommand()
                }
        } else null
        initializationJob?.invokeOnCompletion {
            val release = synchronized(lifecycleLock) {
                if (!initializationStarted) { initializationFinished = true;closeRequested } else false
            }
            if (release) cleanupScope.launch { releaseCommand() }
        }
        val nlu = LocalNluEngine(
            recognizeCommand = { segment ->
                if (fake) recognizeLocalCommand(config.local.asr, segment) { null }
                else {
                    val accepted = synchronized(lifecycleLock) {
                        if (ready && !closeRequested && health?.runtimeBlocked() != true) { activeUses++;true } else false
                    }
                    if (!accepted) null else try {
                        recognizeLocalCommand(config.local.asr, segment) { command?.recognize(segment) }
                    } finally {
                        synchronized(lifecycleLock) { activeUses--;if (activeUses == 0) usesFinished?.complete(Unit) }
                    }
                }
            },
            understandCommand = RuleNluProvider::understand,
            onRecognizerFailure = { Log.w(TAG, "本地 2C 命令词异常，按未命中继续") },
        )
        return LocalEngineAssembly(LocalAsrEngine(), nlu) {
            val close = synchronized(lifecycleLock) { closeRequested = true;ready = false;initializationFinished }
            asrReporter.close(token);nluReporter.close(nluToken)
            if (close) cleanupScope.launch { releaseCommand() }
        }
    }

    private companion object { const val TAG = "VoiceEngineFactory";val COMMAND_TAIL_LOCK = Any();var commandTail = CompletableDeferred<Unit>().apply { complete(Unit) } }
}

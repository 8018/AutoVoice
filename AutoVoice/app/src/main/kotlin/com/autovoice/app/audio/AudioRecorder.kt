package com.autovoice.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.autovoice.voicecore.health.*
import com.autovoice.audiofrontend.AudioFrontendEngine
import com.autovoice.audiofrontend.AudioFrontendEngineFactory
import com.autovoice.audiofrontend.CaptureEffects
import com.autovoice.audiofrontend.CaptureEffectsFactory
import com.autovoice.audiofrontend.VadEngineFactory
import com.autovoice.audiofrontend.vad.OpenMicBargeInGate
import com.autovoice.audiofrontend.vad.VadEvent
import com.autovoice.app.RecordingCapture
import com.autovoice.app.CaptureFrontendBatch
import com.autovoice.app.CaptureFrontendFailure
import com.autovoice.app.CaptureCompletion
import com.autovoice.app.CaptureStreamingInput
import com.autovoice.audiofrontend.FrontendBatch
import com.autovoice.audiofrontend.VadPurpose
import com.autovoice.audiofrontend.VadObservation
import com.autovoice.audiofrontend.AudioInputHistory
import com.autovoice.audiofrontend.inSampleRange
import com.autovoice.audiofrontend.PositionedPcmBlock
import com.autovoice.audiofrontend.RenderReferenceFrame
import com.autovoice.audiofrontend.RenderReferenceSink
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.VadConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 录音流格式常量（16k 单声道 PCM16）。 */
object AudioFormat {
    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8 // 2

    /** 读取块：512 samples = 32ms @16k = 1024 字节。 */
    const val BLOCK_SAMPLES = 512
    const val BLOCK_BYTES = BLOCK_SAMPLES * BYTES_PER_SAMPLE // 1024

    /** 单块时长 ms（测试音频源按此节流，与真实录音同速）。 */
    const val BLOCK_MS = BLOCK_SAMPLES * 1000 / SAMPLE_RATE // 32

    /** RNNoise 帧：480 samples = 30ms @16k。 */
    const val RNN_FRAME_SAMPLES = 480
    const val RNN_FRAME_BYTES = RNN_FRAME_SAMPLES * BYTES_PER_SAMPLE // 960
}

/**
 * 录音通道（VOICE_COMMUNICATION 16k 单声道 PCM16）：只负责采集与发布流。
 *
 * 原始 1024B 块统一交给 [AudioFrontendEngine]；VAD、切段、RNNoise 网格和实现选择均在
 * 所选前端实现模块内闭环。recorder 仅发布处理后的 [pcmBlocks]、[vadEvents] 与原始
 * [rawPcmBlocks]，不依赖 Silero/RNNoise 具体类型。
 *
 * 测试模式（Task 58 云端联调）：demo-full.json 声明 testAudio asset 时，输入源从
 * 麦克风切换为预置语音（[TestAudioSource]），读循环按真实 32ms/块节奏喂同一双网格
 * —— 除麦克风采集外整条端云链路可验证，且无需 RECORD_AUDIO 权限。
 *
 * recorder 保持哑通道，只出 PCM 流 + VAD 事件流 + 抬手取段入口；
 * 麦克风使用权、段装配与双路送识别由 RecordingCoordinator 编排。
 *
 * SileroVad 模型加载失败（assets 缺失等）→ [vadEvents] 不产生事件、[finishSegments]
 * 返回空（VAD 不可用，上层提示），录音降噪流不受影响。
 *
 * RECORD_AUDIO 权限由 Activity 申请；recorder 只管录音，未授权/创建失败时
 * [start] 返回 false 并静默降级（Log.w，不抛到 UI）。
 */
class AudioRecorder(
    private val context: Context,
    /** 测试音频源（demo-full.json 声明 testAudio 时自动启用）；null = 麦克风。 */
    testAudioAsset: String? = null,
    testAudioSource: TestAudioSource? = TestAudioSource.fromConfig(context, testAudioAsset),
    vadConfig: VadConfig = VadConfig(),
    ecnr: String = DemoConfig.ECNR_RNNOISE,
    /** Optional initial instance override; subsequent mode changes use [frontendFactory]. */
    frontend: AudioFrontendEngine? = null,
    captureEffects: CaptureEffects? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private var frontendFactory: AudioFrontendEngineFactory,
    vadFactory: VadEngineFactory,
    captureEffectsFactory: CaptureEffectsFactory,
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    private var captureReporter: ComponentReporter = ComponentReporter.none(),
    private var aecReporter: ComponentReporter = ComponentReporter.none(),
    private var auxVadReporter: ComponentReporter = ComponentReporter.none(),
    private val frontendQueueCapacity: Int = 64,
) : RecordingCapture, RenderReferenceSink {

    @Volatile
    private var testAudioSource: TestAudioSource? = testAudioSource

    @Volatile
    private var activeTestAudioAsset: String? = testAudioAsset

    @Volatile
    private var frontend: AudioFrontendEngine = frontend ?: frontendFactory.create(context, vadConfig, ecnr)

    private val captureEffects: CaptureEffects = captureEffects ?: captureEffectsFactory.create()
    private var closed = false
    private var captureToken: ComponentToken? = null
    private var aecToken: ComponentToken? = null
    private var pendingFrontendReporting: Pair<ComponentReporter, ComponentReporter>? = null
    private var pendingAuxReporting: ComponentReporter? = null
    private var auxVadToken: ComponentToken? = null
    fun configureReporting(capture: ComponentReporter, aec: ComponentReporter, factory: AudioFrontendEngineFactory = frontendFactory,
        frontend: ComponentReporter? = null, vad: ComponentReporter? = null, auxVad: ComponentReporter? = null) {
        frontendFactory = factory
        pendingAuxReporting = auxVad
        pendingFrontendReporting = if (frontend != null && vad != null) frontend to vad else null
        captureReporter = capture;aecReporter = aec;captureToken = null;aecToken = null
    }
    fun reportPermissionDenied() {
        val token = captureToken ?: captureReporter.start("waiting_permission").also { captureToken = it }
        captureReporter.report(token, ComponentUpdate.failed(ComponentError(ErrorCode.MIC_PERMISSION_DENIED, retryable = true)))
    }

    @Volatile
    private var activeVadConfig: VadConfig = vadConfig

    @Volatile
    private var activeEcnr: String = ecnr

    /** 播报期开放式打断使用独立 VAD，不污染正式话语的切段状态。 */
    private val bargeInVad = if (this.frontend.ownsAuxiliaryVad) null else run {
        val token = auxVadReporter.start("model_create").also { auxVadToken = it }
        val vad = vadFactory.create(context)
        auxVadReporter.report(token, if (vad != null) ComponentUpdate.ready()
            else if (this.frontend.unavailabilityReason != null) ComponentUpdate.blocked("frontend")
            else ComponentUpdate.failed(ComponentError(ErrorCode.MODEL_LOAD_FAILED)))
        vad
    }
    private val bargeInGate = OpenMicBargeInGate()
    private val bargeInPreRoll = ArrayDeque<ByteArray>()
    private var pendingBargeInPreRoll: List<ByteArray> = emptyList()
    @Volatile
    private var followUpListening = false
    @Volatile
    private var bargeInListening = false
    private val turnProcessingLock = Any()
    private val captureStreamEpoch = AtomicLong(0)
    private var invalidatedRenderThrough = Long.MIN_VALUE

    override fun acceptRender(frame: RenderReferenceFrame): Boolean = synchronized(turnProcessingLock) {
        if (closed || frame.playbackId <= invalidatedRenderThrough) false
        else (frontend as? RenderReferenceSink)?.acceptRender(frame) ?: false
    }

    override fun invalidateRender(playbackId: Long) = synchronized(turnProcessingLock) {
        if (!closed && playbackId > invalidatedRenderThrough) {
            invalidatedRenderThrough = playbackId
            (frontend as? RenderReferenceSink)?.invalidateRender(playbackId)
        }
    }
    private val orderedBatches = ArrayDeque<CaptureFrontendBatch>()
    private val frontendSignal = MutableStateFlow(0L)
    override val frontendBatchSignal: StateFlow<Long>? get() = if (frontend.continuousProcessing) frontendSignal else null
    @Volatile override var inputEpoch: Long? = null
        private set
    @Volatile override var frontendFailure: CaptureFrontendFailure? = null
        private set
    private var inputCounter = 0L
    private var streamingInput: CaptureStreamingInput? = null
    private var capturedSamples = 0L
    private var inputStartSample = 0L
    private var completedTail = ByteArray(0)
    private var completedSegments = emptyList<ByteArray>()
    private var followUpEpoch = 0L
    private var bargeInEpoch = 0L
    private val processedHistory = ArrayDeque<PositionedPcmBlock>()
    private var historySamples = 0
    private val mainHistory = ArrayDeque<VadObservation>()
    private var pendingAuxiliaryConfirmation: Long? = null
    private var pendingProcessedStart: Long? = null
    init { require(frontendQueueCapacity in 1..256) }
    private fun rememberProcessed(batch: FrontendBatch) {
        for (block in batch.processedBlocks) {
            processedHistory.addLast(block.copy(pcm = block.pcm.copyOf()));historySamples += block.validSamples
            val limit = (frontendQueueCapacity+BARGE_IN_PRE_ROLL_BLOCKS+1)*AudioFormat.BLOCK_SAMPLES
            while (historySamples > limit) historySamples -= processedHistory.removeFirst().validSamples
        }
        mainHistory.addAll(batch.vadObservations.filter { it.purpose == VadPurpose.SEGMENTATION })
        processedHistory.firstOrNull()?.let { first ->
            while (mainHistory.firstOrNull()?.let { it.startSample+it.validSamples <= first.startSample } == true) mainHistory.removeFirst()
        }
    }
    private fun selectedProcessedPreRoll(): AudioInputHistory {
        val start = requireNotNull(pendingProcessedStart) { "processed pre-roll has no auxiliary confirmation" }
        val selected = processedHistory.mapNotNull { block ->
            val skipped = (start-block.startSample).coerceIn(0L,block.validSamples.toLong()).toInt()
            if (skipped == block.validSamples) null else PositionedPcmBlock(block.startSample+skipped,block.pcm.copyOfRange(skipped*2,block.pcm.size),format = block.format)
        }
        require(selected.isNotEmpty() && selected.first().startSample == start) { "processed pre-roll history is unavailable" }
        require(selected.zipWithNext().all { (a,b) -> a.startSample+a.validSamples == b.startSample }) { "processed pre-roll history is discontinuous" }
        return AudioInputHistory(FrontendBatch(selected,mainHistory.filter { it.startSample+it.validSamples > start }),
            requireNotNull(pendingAuxiliaryConfirmation))
    }
    override fun detectAuxiliarySpeech(event: CaptureFrontendBatch, purpose: VadPurpose): Boolean = synchronized(turnProcessingLock) {
        if (!frontend.continuousProcessing || turnActive || frontendFailure != null || frontend.unavailabilityReason != null || event.inputEpoch != null) return@synchronized false
        val eligible = when (purpose) {
            VadPurpose.FOLLOW_UP -> followUpListening && event.followUpEpoch == followUpEpoch
            VadPurpose.BARGE_IN -> bargeInListening && openMicBargeInAvailable && event.bargeInEpoch == bargeInEpoch
            else -> false
        }
        if (!eligible || event.batch.vadObservations.none { it.purpose == purpose && VadEvent.SpeechStart in it.events }) return@synchronized false
        val end = event.batch.processedBlocks.lastOrNull()?.let { it.startSample+it.validSamples } ?: return@synchronized false
        pendingProcessedStart = maxOf(processedHistory.firstOrNull()?.startSample ?: end,end-BARGE_IN_PRE_ROLL_BLOCKS*AudioFormat.BLOCK_SAMPLES)
        val confirmation = event.batch.vadObservations.first { it.purpose == purpose && VadEvent.SpeechStart in it.events }
        pendingAuxiliaryConfirmation = confirmation.startSample+confirmation.validSamples
        if (purpose == VadPurpose.FOLLOW_UP) followUpListening = false else bargeInListening = false
        true
    }
    override fun drainFrontendBatches(): List<CaptureFrontendBatch> = synchronized(turnProcessingLock) {
        orderedBatches.toList().also { orderedBatches.clear() }
    }
    override fun beginStreamingInput(): CaptureStreamingInput? = synchronized(turnProcessingLock) {
        if (closed || !frontend.continuousProcessing || turnActive || streamingInput != null ||
            frontendFailure != null || frontend.unavailabilityReason != null) return@synchronized null
        CaptureStreamingInput(++inputCounter,captureStreamEpoch.get(),capturedSamples).also {
            streamingInput = it;inputEpoch = it.inputEpoch
            followUpListening = false;bargeInListening = false
        }
    }
    override fun endStreamingInput() = synchronized(turnProcessingLock) {
        streamingInput?.let { owner -> orderedBatches.removeAll { it.inputEpoch == owner.inputEpoch } }
        streamingInput = null;inputEpoch = null
    }
    private fun beginFrontendInput(includeProcessedPreRoll: Boolean) {
        if (!frontend.continuousProcessing) { frontend.startTurn();return }
        val history = if (includeProcessedPreRoll) selectedProcessedPreRoll() else null
        if (history == null) frontend.startTurn() else frontend.startTurn(history)
        inputEpoch = ++inputCounter;inputStartSample = history?.batch?.processedBlocks?.firstOrNull()?.startSample ?: capturedSamples
        val preRoll = history?.batch?.inSampleRange(inputStartSample,frontend.diagnostics.observedInputEndSample ?: Long.MAX_VALUE)
        completedTail = ByteArray(0);completedSegments = emptyList()
        followUpListening = false;bargeInListening = false
        if (preRoll != null && preRoll.processedBlocks.isNotEmpty()) {
            check(orderedBatches.size < frontendQueueCapacity) { "capture queue overflow" }
            orderedBatches.addLast(CaptureFrontendBatch(inputEpoch,preRoll,speechConfirmedForInput = true))
        }
        pendingProcessedStart = null;pendingAuxiliaryConfirmation = null
    }
    private fun tryBeginFrontendInput(includeProcessedPreRoll: Boolean): Boolean = try {
        beginFrontendInput(includeProcessedPreRoll);true
    } catch (error: Throwable) {
        error.rethrowCancellationOrFatal()
        if (!frontend.continuousProcessing) throw error
        failOpenInput(error);false
    }
    private fun activePcm(batch: FrontendBatch): FrontendBatch =
        batch.inSampleRange(inputStartSample,frontend.diagnostics.observedInputEndSample ?: Long.MAX_VALUE)
    private fun failOpenInput(error: Throwable) {
        if (frontendFailure != null) return
        val failure = initializationError(error)
        frontendFailure = CaptureFrontendFailure(streamingInput?.inputEpoch ?: if (turnActive) inputEpoch else null,failure)
        streamingInput = null
        turnActive = false;completedTail = ByteArray(0);completedSegments = emptyList();orderedBatches.clear()
        frontend.abortInput()
        captureToken?.let { captureReporter.report(it,ComponentUpdate.health(Health.UNAVAILABLE,failure)) }
    }
    @Synchronized
    override fun abortInput() {
        if (closed) return
        if (!frontend.continuousProcessing) { stop();return }
        synchronized(turnProcessingLock) {
            turnActive = false;frontend.abortInput();orderedBatches.clear()
            completedTail = ByteArray(0);completedSegments = emptyList()
        }
        if (testAudioSource != null || !monitoringRequested) stopCapture()
    }

    private val _pcmBlocks = MutableSharedFlow<ByteArray>(extraBufferCapacity = BUFFER_CAPACITY)

    /** 降噪后 PCM 块流（960B/块，16k 单声道 PCM16）；普通轮由 RecordingCoordinator 收集。 */
    override val pcmBlocks: SharedFlow<ByteArray> = _pcmBlocks.asSharedFlow()

    private val _rawPcmBlocks = MutableSharedFlow<ByteArray>(extraBufferCapacity = BUFFER_CAPACITY)

    /**
     * 麦克风原始 PCM 块流（1024B/块，16k 单声道 PCM16）。这是唯一 AudioRecord 的共享
     * 输出；待机时唤醒观察者消费它，进入一轮后 VAD/RNNoise 也消费同一批后续块。
     */
    override val rawPcmBlocks: SharedFlow<ByteArray> = _rawPcmBlocks.asSharedFlow()

    private val _vadEvents = MutableSharedFlow<VadEvent>(extraBufferCapacity = BUFFER_CAPACITY)

    /** VAD 事件流（SpeechStart / SpeechEnd；模型加载失败时永不发射）。 */
    override val vadEvents: SharedFlow<VadEvent> = _vadEvents.asSharedFlow()

    val frontendUnavailableReason: String? get() = frontendFailure?.error?.message ?: frontend.unavailabilityReason

    /** VAD 是否可用（模型加载成功）。 */
    override val vadAvailable: Boolean get() = frontend.vadAvailable

    /**
     * Applies mode-specific capture settings after an engine mode switch. Barge-in/follow-up VAD
     * deliberately keeps its own thresholds because it serves a different detection purpose.
     */
    @Synchronized
    fun configureAudioProcessing(vad: VadConfig, ecnr: String, testAudioAsset: String? = null) {
        check(!closed) { "audio recorder is closed" }
        require(!turnActive) { "audio processing cannot be reconfigured during an active turn" }
        require(streamingInput == null) { "audio processing cannot be reconfigured during streaming input" }
        require(!monitoringRequested) { "audio processing cannot be reconfigured while monitoring" }
        if (testAudioAsset != activeTestAudioAsset) {
            testAudioSource = TestAudioSource.fromConfig(context, testAudioAsset)
            activeTestAudioAsset = testAudioAsset
        }
        val changed = vad.forProcessingComparison() != activeVadConfig.forProcessingComparison() || ecnr != activeEcnr
        val failedOpen = frontend.continuousProcessing && (frontendFailure != null || frontend.unavailabilityReason != null)
        synchronized(turnProcessingLock) { captureStreamEpoch.incrementAndGet() }
        var replacedOpen = false
        if (frontend.continuousProcessing && (changed || failedOpen)) {
            synchronized(turnProcessingLock) {
                // Capture is explicitly paused: release the old APM before constructing another.
                try {
                    frontend.close()
                    frontend = frontendFactory.create(context,vad,ecnr)
                    activeVadConfig = vad;activeEcnr = ecnr;replacedOpen = true
                } catch (error: Throwable) { error.rethrowCancellationOrFatal();failOpenInput(error);return }
            }
        } else if (changed) {
            val replacement = frontendFactory.create(context, vad, ecnr)
            synchronized(turnProcessingLock) {
                val old = frontend
                frontend = replacement
                activeVadConfig = vad
                activeEcnr = ecnr
                runCatching { old.close() }
            }
        } else pendingFrontendReporting?.let { (front, vadReporter) -> frontend.attachReporting(front, vadReporter) }
        if (frontend.continuousProcessing) synchronized(turnProcessingLock) {
            if (replacedOpen) capturedSamples = 0
            inputEpoch = null;frontendFailure = null
            orderedBatches.clear();processedHistory.clear();mainHistory.clear();historySamples = 0
            completedTail = ByteArray(0);completedSegments = emptyList()
            pendingProcessedStart = null;pendingAuxiliaryConfirmation = null
            followUpEpoch++;bargeInEpoch++;followUpListening = false;bargeInListening = false
        }
        pendingFrontendReporting = null
        pendingAuxReporting?.let { reporter ->
            auxVadReporter = reporter
            if (frontend.ownsAuxiliaryVad) frontend.attachAuxiliaryReporting(reporter,aecReporter)
            else {
            auxVadToken = reporter.start("reuse_existing")
            reporter.report(requireNotNull(auxVadToken), if (bargeInVad != null) ComponentUpdate.ready()
                else if (this.frontend.unavailabilityReason != null) ComponentUpdate.blocked("frontend")
                else ComponentUpdate.failed(ComponentError(ErrorCode.MODEL_LOAD_FAILED)))
            }
        }
        pendingAuxReporting = null
        Log.i(
            TAG,
            "audio config applied: vad.threshold=${vad.threshold} " +
                "vad.minSpeechMs=${vad.minSpeechMs} vad.minSilenceMs=${vad.minSilenceMs} " +
                "ecnr=$ecnr testAudio=${testAudioAsset ?: "microphone"}",
        )
    }

    @Volatile
    private var record: AudioRecord? = null

    /** 只有系统 AEC 真正启用时才允许普通话术打断，避免扬声器回声自激。 */
    @Volatile
    override var openMicBargeInAvailable: Boolean = false
        private set

    private var readJob: Job? = null

    @Volatile
    private var turnActive = false

    @Volatile
    private var monitoringRequested = false

    /** 是否正在收集一轮语音；底层麦克风可能因唤醒监听而继续运行。 */
    val isRecording: Boolean get() = turnActive

    /**
     * 启动待机监听的共享麦克风。Open 持续处理 APM 及其观测，待机时不保留整轮 PCM。
     * Legacy 待机保持原始唤醒流，不开启录音轮的 VAD/RNNoise。
     * 测试音频源不支持常驻唤醒，避免 fixture 在后台无限循环触发。
     */
    @Synchronized
    override fun startMonitoring(): Boolean {
        if (closed) return false
        if (testAudioSource != null) return false
        monitoringRequested = true
        if (ensureMicrophoneCapture()) return true
        monitoringRequested = false
        return false
    }

    /** 停止待机监听；若当前仍在收集一轮语音，麦克风保持运行到 [stop]。 */
    @Synchronized
    override fun stopMonitoring() {
        monitoringRequested = false
        if (!turnActive) stopCapture()
    }

    /** 播报开始/结束时切换打断 VAD；不创建第二路 AudioRecord。 */
    override fun setOpenMicBargeInListening(enabled: Boolean) {
        if (frontend.continuousProcessing) {
            synchronized(turnProcessingLock) {
                if (closed) return
                bargeInEpoch++;bargeInListening = enabled && frontendFailure == null && frontend.unavailabilityReason == null && frontend.vadAvailable
                if (bargeInListening) frontend.resetVad(VadPurpose.BARGE_IN)
            }
            return
        }
        if (enabled && openMicBargeInAvailable && bargeInVad != null) {
            bargeInListening = true
            bargeInVad.reset()
            synchronized(bargeInPreRoll) {
                bargeInPreRoll.clear()
                pendingBargeInPreRoll = emptyList()
            }
            bargeInGate.start()
        } else {
            bargeInListening = false
            if (!followUpListening) bargeInGate.stop()
        }
    }

    /** 播报真正结束后的免唤醒窗口；复用同一 VAD/预卷缓冲，但不要求 AEC。 */
    override fun setFollowUpListening(enabled: Boolean) {
        if (frontend.continuousProcessing) {
            synchronized(turnProcessingLock) {
                if (closed) return
                followUpEpoch++;followUpListening = enabled && frontendFailure == null && frontend.unavailabilityReason == null && frontend.vadAvailable
                if (followUpListening) { pendingProcessedStart = null;frontend.resetVad(VadPurpose.FOLLOW_UP) }
            }
            return
        }
        followUpListening = enabled && bargeInVad != null
        if (followUpListening) {
            bargeInVad?.reset()
            synchronized(bargeInPreRoll) {
                bargeInPreRoll.clear()
                pendingBargeInPreRoll = emptyList()
            }
            bargeInGate.start()
        } else if (!bargeInListening) {
            bargeInGate.stop()
        }
    }

    /** 待机原始 PCM 同时喂给打断 VAD；每次播报最多触发一次。 */
    override fun detectOpenMicBargeIn(block: ByteArray): Boolean {
        val vad = bargeInVad ?: return false
        if (!bargeInListening || !openMicBargeInAvailable || turnActive || !bargeInGate.listening) return false
        synchronized(bargeInPreRoll) {
            bargeInPreRoll.addLast(block.copyOf())
            while (bargeInPreRoll.size > BARGE_IN_PRE_ROLL_BLOCKS) bargeInPreRoll.removeFirst()
        }
        val triggered = bargeInGate.feed(vad.feed(block))
        if (triggered) {
            synchronized(bargeInPreRoll) {
                pendingBargeInPreRoll = bargeInPreRoll.toList()
            }
        }
        return triggered
    }

    /** 延时聆听期检测到连续人声后仅触发 capture；是否形成 turn 仍由 ASR/语义准入确认。 */
    override fun detectFollowUpSpeech(block: ByteArray): Boolean {
        val vad = bargeInVad ?: return false
        if (!followUpListening || turnActive || !bargeInGate.listening) return false
        synchronized(bargeInPreRoll) {
            bargeInPreRoll.addLast(block.copyOf())
            while (bargeInPreRoll.size > BARGE_IN_PRE_ROLL_BLOCKS) bargeInPreRoll.removeFirst()
        }
        val triggered = bargeInGate.feed(vad.feed(block))
        if (triggered) {
            followUpListening = false
            synchronized(bargeInPreRoll) { pendingBargeInPreRoll = bargeInPreRoll.toList() }
        }
        return triggered
    }

    /** 启动录音；已在录或创建 AudioRecord 失败（如缺 RECORD_AUDIO 权限）时返回 false。 */
    @Synchronized
    override fun start(includeBargeInPreRoll: Boolean): Boolean {
        if (closed) return false
        if (streamingInput != null) return false
        if (turnActive) return false
        frontendUnavailableReason?.let {
            Log.w(TAG, "audio frontend unavailable: $it")
            return false
        }
        val source = testAudioSource
        if (source != null) {
            captureToken = captureReporter.start("capture_open")
            captureReporter.report(requireNotNull(captureToken), ComponentUpdate.disabled())
            // 测试模式（Task 58）：屏蔽麦克风，预置语音按真实节奏喂双网格，无需录音权限
            Log.i(TAG, "测试音频源模式：${source.describe()}")
            source.reset() // 每轮从头播（游标跨轮不重置 → 段起点随机，Task 58 联调发现）
            val started = synchronized(turnProcessingLock) {
                tryBeginFrontendInput(includeBargeInPreRoll).also { if (it) turnActive = true }
            }
            if (!started) return false
            val streamEpoch = synchronized(turnProcessingLock) { captureStreamEpoch.incrementAndGet() }
            readJob = scope.launch { testReadLoop(source,streamEpoch) }
            return true
        }
        if (!ensureMicrophoneCapture()) return false
        val preRoll = synchronized(bargeInPreRoll) {
            val audio = if (includeBargeInPreRoll) pendingBargeInPreRoll else emptyList()
            pendingBargeInPreRoll = emptyList()
            bargeInPreRoll.clear()
            audio
        }
        // 本轮录音从干净状态起步；开放式打断会先补入触发前的环形缓冲，避免丢首字。
        val started = synchronized(turnProcessingLock) {
            tryBeginFrontendInput(includeBargeInPreRoll).also { ready ->
                if (ready) {
                    turnActive = true
                    if (!frontend.continuousProcessing) preRoll.forEach(::processTurnBlock)
                }
            }
        }
        return started
    }

    /**
     * 抬手取段（Task 49 双路：云端路段的 VAD 切段，时间顺序）。
     * 必须在 [stop] 之后调用（前端 feed 已停，finish 与录音线程互斥安全）；
     * VAD 不可用时返回空列表。
     */
    override fun finishSegments(): List<ByteArray> {
        if (frontend.continuousProcessing) return synchronized(turnProcessingLock) { completedSegments.also { completedSegments = emptyList() } }
        val segments = frontend.finishSegments()
        // Task 55 诊断：云端段为空时区分"VAD 未启用/概率过低/未切段"
        val vs = frontend.diagnostics
        Log.i(
            TAG,
            "VAD 诊断: vad=$vadAvailable segments=${segments.size} maxProb=${vs.maxVadProbability} " +
                "startEvt=${vs.speechStartEvents} endEvt=${vs.speechEndEvents} " +
                "blocks=${vs.inputBlocks} openStart=${vs.openSegmentStart}",
        )
        return segments
    }

    override fun finishProcessedAudio(): ByteArray = synchronized(turnProcessingLock) {
        if (frontend.continuousProcessing) completedTail.also { completedTail = ByteArray(0) }
        else frontend.finishProcessedAudio()
    }

    /**
     * 结束本轮收集（幂等）。若唤醒监听仍启用，只关闭 VAD/RNNoise 分支，底层
     * AudioRecord 与原始 PCM 流保持不变；否则释放麦克风。
     */
    @Synchronized
    override fun stop() {
        synchronized(turnProcessingLock) {
            if (frontend.continuousProcessing && turnActive && frontendFailure == null) {
                try {
                    val tail = frontend.finishBatch()
                    rememberProcessed(tail)
                    completedTail = activePcm(tail).processedPcm
                    completedSegments = frontend.finishSegments()
                } catch (error: Throwable) { error.rethrowCancellationOrFatal();failOpenInput(error) }
            }
            turnActive = false
        }
        if (testAudioSource != null || !monitoringRequested) stopCapture()
    }

    @Synchronized
    override fun stopAndSnapshot(): CaptureCompletion? {
        if (!frontend.continuousProcessing) return null
        return synchronized(turnProcessingLock) {
            stop()
            CaptureCompletion(inputEpoch,orderedBatches.toList(),completedTail.copyOf(),
                completedSegments.map { it.copyOf() },frontendFailure).also {
                orderedBatches.clear();completedTail = ByteArray(0);completedSegments = emptyList()
            }
        }
    }

    private fun stopCapture() {
        synchronized(turnProcessingLock) {
            captureStreamEpoch.incrementAndGet()
            if (streamingInput != null) endStreamingInput()
        }
        readJob?.cancel()
        readJob = null
        val audioRecord = record
        record = null
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
            // 未启动/已释放时 stop 抛异常，可忽略
        }
        audioRecord?.release()
        if (audioRecord != null) captureToken?.let { captureReporter.report(it, ComponentUpdate.health(Health.UNKNOWN)) }
        releaseCaptureEffects()
    }

    /** 释放统一音频前端与协程 scope（释放后不可再 [start]）。 */
    @Synchronized
    override fun close() {
        if (closed) return
        synchronized(turnProcessingLock) {
            closed = true
            streamingInput = null
            captureStreamEpoch.incrementAndGet()
            monitoringRequested = false
            turnActive = false
            followUpListening = false
            bargeInListening = false
            try {
                frontend.close()
            } catch (_: Throwable) {
            }
        }
        stopCapture()
        try {
            bargeInVad?.close()
        } catch (_: Throwable) {
        }
        captureReporter.close();if (!frontend.ownsAuxiliaryVad) aecReporter.close()
        auxVadToken?.let { auxVadReporter.close(it) }
        scope.cancel()
    }

    // ------------------------------------------------------------------ 读取循环

    /** 测试模式读循环：预置语音按真实 32ms/块节奏循环喂双网格（VAD 切段 + RNNoise 降噪）。 */
    private suspend fun testReadLoop(source: TestAudioSource, streamEpoch: Long) {
        val block = ByteArray(AudioFormat.BLOCK_BYTES)
        while (currentCoroutineContext().isActive) {
            source.nextBlock(block)
            try {
                handleCapturedBlock(block,streamEpoch)
            } catch (t: Throwable) {
                t.rethrowCancellationOrFatal()
                // 单块处理失败（RNNoise 异常）不中断录音，静默降级
                Log.w(TAG, "block processing failed, degraded silently", t)
            }
            delay(AudioFormat.BLOCK_MS.toLong())
        }
    }

    private suspend fun readLoop(audioRecord: AudioRecord, streamEpoch: Long) {
        val block = ByteArray(AudioFormat.BLOCK_BYTES)
        while (currentCoroutineContext().isActive) {
            if (!readFully(audioRecord, block)) {
                if (record === audioRecord && currentCoroutineContext().isActive) captureToken?.let {
                    captureReporter.report(it, ComponentUpdate.health(Health.UNAVAILABLE, ComponentError(ErrorCode.CAPTURE_OPEN_FAILED, retryable = true)))
                }
                break // 停止 / 读错误
            }
            try {
                handleCapturedBlock(block,streamEpoch)
            } catch (t: Throwable) {
                t.rethrowCancellationOrFatal()
                // 单块处理失败（RNNoise 异常）不中断录音，静默降级
                Log.w(TAG, "block processing failed, degraded silently", t)
            }
        }
    }

    /** 阻塞读满 1024 字节；EOF/错误时返回 false。 */
    private fun readFully(audioRecord: AudioRecord, buf: ByteArray): Boolean {
        var offset = 0
        while (offset < buf.size) {
            val n = try {
                audioRecord.read(buf, offset, buf.size - offset)
            } catch (t: Throwable) {
                Log.w(TAG, "audio read interrupted (likely stopped), degraded silently", t)
                return false
            }
            if (n <= 0) return false
            offset += n
        }
        return true
    }

    /** 单一采集流扇出：原始 PCM 始终给观察者；只有本轮激活时才进入语音处理分支。 */
    private fun handleCapturedBlock(block: ByteArray) {
        handleCapturedBlock(block,captureStreamEpoch.get())
    }

    private fun handleCapturedBlock(block: ByteArray, streamEpoch: Long) {
        val admitted = synchronized(turnProcessingLock) { !closed && streamEpoch == captureStreamEpoch.get() }
        if (!admitted) return
        // Raw observers may start/stop capture inline. Preserve legacy raw-before-DSP ordering,
        // then recheck the original producer token rather than adopting any new owner.
        _rawPcmBlocks.tryEmit(block.copyOf())
        val changed = synchronized(turnProcessingLock) {
            if (closed || streamEpoch != captureStreamEpoch.get()) return@synchronized false
            if (frontend.continuousProcessing) {
                if (frontendFailure != null || frontend.unavailabilityReason != null) false else {
                    try {
                        val result = frontend.process(block);capturedSamples += block.size/2
                        rememberProcessed(result)
                        val streaming = streamingInput
                        val event = CaptureFrontendBatch(streaming?.inputEpoch ?: if (turnActive) inputEpoch else null,
                            if (streaming != null) result.inSampleRange(streaming.startSample,Long.MAX_VALUE)
                            else if (turnActive) activePcm(result) else result,
                            if (followUpListening) followUpEpoch else null,if (bargeInListening) bargeInEpoch else null)
                        check(orderedBatches.size < frontendQueueCapacity) { "capture queue overflow" }
                        orderedBatches.addLast(event)
                    } catch (error: Throwable) {
                        error.rethrowCancellationOrFatal()
                        failOpenInput(if (error.message == "capture queue overflow") java.util.concurrent.RejectedExecutionException("capture queue overflow") else error)
                    }
                    true
                }
            } else { if (turnActive) processTurnBlock(block);false }
        }
        // A signal can resume collectors inline: publish only after releasing Recorder/FIFO locks.
        if (changed) frontendSignal.update { it + 1 }
    }

    private fun processTurnBlock(block: ByteArray) {
        val result = frontend.process(block)
        for (observation in result.vadObservations) if (observation.purpose == com.autovoice.audiofrontend.VadPurpose.SEGMENTATION) {
            for (event in observation.events) _vadEvents.tryEmit(event)
        }
        for (pcm in result.processedBlocks) _pcmBlocks.tryEmit(pcm.pcm)
    }

    // ------------------------------------------------------------------ 资源

    // Activity 在调用 start 前负责运行时授权；仍捕获 SecurityException 处理权限被撤回的竞态。
    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioFormat.SAMPLE_RATE,
            AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBuffer, AudioFormat.BLOCK_BYTES * 2)
        return try {
            AudioRecord.Builder()
                // Channel policy selects the capture source; legacy keeps VOICE_COMMUNICATION.
                .setAudioSource(audioSource)
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setSampleRate(AudioFormat.SAMPLE_RATE)
                        .setChannelMask(AndroidAudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (t: Throwable) {
            // 缺 RECORD_AUDIO 权限（SecurityException）/ 设备不支持（UnsupportedOperationException 等）
            t.rethrowCancellationOrFatal()
            if (t is SecurityException) captureToken?.let { captureReporter.report(it, ComponentUpdate.failed(ComponentError(ErrorCode.MIC_PERMISSION_DENIED, retryable = true))) }
            Log.w(TAG, "AudioRecord creation failed, degraded silently", t)
            null
        }
    }

    /** 创建一次 AudioRecord 并持续读；唤醒/语音只切换观察分支，不重建麦克风。 */
    private fun ensureMicrophoneCapture(): Boolean {
        if (readJob?.isActive == true && record != null) return true
        captureToken = captureReporter.start("capture_open")
        val audioRecord = try { createAudioRecord() } catch (error: Throwable) {
            error.rethrowCancellationOrFatal()
            captureReporter.report(requireNotNull(captureToken), ComponentUpdate.failed(initializationError(error, ErrorCode.CAPTURE_OPEN_FAILED)))
            return false
        } ?: run {
            captureReporter.report(requireNotNull(captureToken), ComponentUpdate.failed(ComponentError(ErrorCode.CAPTURE_OPEN_FAILED, retryable = true)))
            return false
        }
        record = audioRecord
        try {
            attachCaptureEffects(audioRecord.audioSessionId)
            audioRecord.startRecording()
        } catch (t: Throwable) {
            t.rethrowCancellationOrFatal()
            captureReporter.report(requireNotNull(captureToken), ComponentUpdate.failed(initializationError(t, ErrorCode.CAPTURE_OPEN_FAILED)))
            Log.w(TAG, "AudioRecord.startRecording failed, degraded silently", t)
            audioRecord.release()
            releaseCaptureEffects()
            record = null
            return false
        }
        captureReporter.report(requireNotNull(captureToken), ComponentUpdate.ready())
        val streamEpoch = synchronized(turnProcessingLock) { captureStreamEpoch.incrementAndGet() }
        readJob = scope.launch { readLoop(audioRecord,streamEpoch) }
        return true
    }

    private fun attachCaptureEffects(audioSessionId: Int) {
        releaseCaptureEffects()
        if (!frontend.ownsAuxiliaryVad) aecToken = aecReporter.start("effects")
        captureEffects.attach(audioSessionId)
        aecToken?.let { aecReporter.report(it, if (captureEffects.echoCancellationActive) ComponentUpdate.ready() else ComponentUpdate.disabled()) }
        openMicBargeInAvailable = captureEffects.echoCancellationActive && bargeInVad != null
        Log.i(
            TAG,
            "capture effects: aec=${captureEffects.echoCancellationActive} " +
                "ns=${captureEffects.noiseSuppressionActive} openMicBargeIn=$openMicBargeInAvailable",
        )
    }

    private fun releaseCaptureEffects() {
        followUpListening = false
        bargeInListening = false
        bargeInGate.stop()
        openMicBargeInAvailable = false
        captureEffects.release()
        aecToken?.let { aecReporter.report(it, ComponentUpdate.health(Health.UNKNOWN)) }
    }

    private companion object {
        const val TAG = "AudioRecorder"

        /** SharedFlow 缓冲（块：不阻塞读取循环）。 */
        const val BUFFER_CAPACITY = 16

        /** 保留触发前约 384ms PCM，包含 VAD 确认窗口和少量话首。 */
        const val BARGE_IN_PRE_ROLL_BLOCKS = 12
    }
}

/** Presence metadata is a config-validation concern; legacy only consumes its historical fields. */
private fun VadConfig.forProcessingComparison() =
    if (provider == "silero") copy(thresholdProvided = false,aggressiveness = 2) else this

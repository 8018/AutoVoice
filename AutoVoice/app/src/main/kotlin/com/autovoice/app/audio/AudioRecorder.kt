package com.autovoice.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.autovoice.adapterlocal.ecnr.RnnoiseProcessor
import com.autovoice.adapterlocal.vad.SileroVad
import com.autovoice.adapterlocal.vad.VadEvent
import com.autovoice.adapterlocal.vad.VadSegmenter
import com.autovoice.adapterlocal.vad.VoiceActivityGate
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    const val RNN_FRAME_SAMPLES = RnnoiseProcessor.FRAME_SIZE // 480
    const val RNN_FRAME_BYTES = RNN_FRAME_SAMPLES * BYTES_PER_SAMPLE // 960
}

/** 1024 字节 PCM16 块（LE）→ 512 samples。 */
internal fun pcm16BytesToShorts(bytes: ByteArray): ShortArray {
    require(bytes.size == AudioFormat.BLOCK_BYTES) {
        "expected ${AudioFormat.BLOCK_BYTES} bytes (${AudioFormat.BLOCK_SAMPLES} samples), got ${bytes.size}"
    }
    val samples = ShortArray(AudioFormat.BLOCK_SAMPLES)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
    return samples
}

/** samples → PCM16 字节（LE）。 */
internal fun pcm16ShortsToBytes(shorts: ShortArray): ByteArray {
    val out = ByteArray(shorts.size * AudioFormat.BYTES_PER_SAMPLE)
    ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts)
    return out
}

/**
 * RNNoise 480 帧网格上的块切分/尾帧对齐（纯逻辑，JVM 可测）：
 * 512 samples 的块切出 1 帧（480 samples），尾 32 samples（2ms）丢弃——
 * 不重写切帧逻辑，直接复用 [RnnoiseProcessor.chunk]（纯 Kotlin，无 native 调用；
 * [RnnoiseProcessor] native 句柄懒加载，只在 [RnnoiseProcessor.process] 时触发）。
 */
internal fun first480Frame(samples: ShortArray): ShortArray {
    require(samples.size == AudioFormat.BLOCK_SAMPLES) {
        "expected ${AudioFormat.BLOCK_SAMPLES} samples per block, got ${samples.size}"
    }
    val frames = RnnoiseProcessor().chunk(samples)
    check(frames.size == 1) { "512 samples should yield exactly 1 frame of 480, got ${frames.size}" }
    return frames[0]
}

/**
 * 录音通道（VOICE_COMMUNICATION 16k 单声道 PCM16）：AudioRecord 读 1024B/块 → 双网格。
 *
 * - VAD 网格（512 samples/块 = 32ms）：原始 1024B 块喂 [VadSegmenter]（Silero VAD +
 *   门控切段，Task 49）——按住期间实时切出云端语音段；抬手后 [finishSegments]
 *   取全部段（含强制切出的未闭合尾段）。
 * - RNNoise 网格（480 samples/帧，独立于 VAD 网格）：块内切 480 帧、尾 32 samples 丢弃
 *   （Task 18 chunk 语义），降噪后 960B/块进 [pcmBlocks]——本地路整段音频，
 *   由 MainViewModel 按"按住期间"收集。
 *
 * 测试模式（Task 58 云端联调）：demo-full.json 声明 testAudio asset 时，输入源从
 * 麦克风切换为预置语音（[TestAudioSource]），读循环按真实 32ms/块节奏喂同一双网格
 * —— 除麦克风采集外整条端云链路可验证，且无需 RECORD_AUDIO 权限。
 *
 * recorder 保持哑通道，只出 PCM 流 + VAD 事件流 + 抬手取段入口；
 * 段装配/双路送识别的编排在 MainViewModel。
 *
 * SileroVad 模型加载失败（assets 缺失等）→ [vadEvents] 不产生事件、[finishSegments]
 * 返回空（VAD 不可用，上层提示），录音降噪流不受影响。
 *
 * RECORD_AUDIO 权限由 Activity 申请；recorder 只管录音，未授权/创建失败时
 * [start] 返回 false 并静默降级（Log.w，不抛到 UI）。
 */
class AudioRecorder(
    context: Context,
    /** 测试音频源（demo-full.json 声明 testAudio 时自动启用）；null = 麦克风。 */
    private val testAudioSource: TestAudioSource? = TestAudioSource.fromDemoConfig(context),
    private val vadSegmenter: VadSegmenter? = try {
        VadSegmenter(SileroVad(context, SILERO_VAD_ASSET))
    } catch (t: Throwable) {
        Log.w(TAG, "Silero VAD 模型加载失败，VAD 不可用（语音检测失效）", t)
        null
    },
    private val denoiser: RnnoiseProcessor = RnnoiseProcessor(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {

    /** 播报期开放式打断使用独立 VAD，不污染正式话语的切段状态。 */
    private val bargeInVad: SileroVad? = try {
        SileroVad(context, SILERO_VAD_ASSET)
    } catch (t: Throwable) {
        Log.w(TAG, "打断 VAD 加载失败，降级为仅唤醒词打断", t)
        null
    }
    private val bargeInGate = OpenMicBargeInGate()
    private val bargeInPreRoll = ArrayDeque<ByteArray>()
    private var pendingBargeInPreRoll: List<ByteArray> = emptyList()
    private val turnProcessingLock = Any()

    private val _pcmBlocks = MutableSharedFlow<ByteArray>(extraBufferCapacity = BUFFER_CAPACITY)

    /** 降噪后 PCM 块流（960B/块，16k 单声道 PCM16）；按住期间收集在 MainViewModel。 */
    val pcmBlocks: SharedFlow<ByteArray> = _pcmBlocks.asSharedFlow()

    private val _rawPcmBlocks = MutableSharedFlow<ByteArray>(extraBufferCapacity = BUFFER_CAPACITY)

    /**
     * 麦克风原始 PCM 块流（1024B/块，16k 单声道 PCM16）。这是唯一 AudioRecord 的共享
     * 输出；待机时唤醒观察者消费它，进入一轮后 VAD/RNNoise 也消费同一批后续块。
     */
    val rawPcmBlocks: SharedFlow<ByteArray> = _rawPcmBlocks.asSharedFlow()

    private val _vadEvents = MutableSharedFlow<VadEvent>(extraBufferCapacity = BUFFER_CAPACITY)

    /** VAD 事件流（SpeechStart / SpeechEnd；模型加载失败时永不发射）。 */
    val vadEvents: SharedFlow<VadEvent> = _vadEvents.asSharedFlow()

    /** VAD 是否可用（模型加载成功）。 */
    val vadAvailable: Boolean get() = vadSegmenter != null

    @Volatile
    private var record: AudioRecord? = null

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    /** 只有系统 AEC 真正启用时才允许普通话术打断，避免扬声器回声自激。 */
    @Volatile
    var openMicBargeInAvailable: Boolean = false
        private set

    private var readJob: Job? = null

    @Volatile
    private var turnActive = false

    @Volatile
    private var monitoringRequested = false

    /** 是否正在收集一轮语音；底层麦克风可能因唤醒监听而继续运行。 */
    val isRecording: Boolean get() = turnActive

    /**
     * 启动待机监听的共享麦克风。不会启用 VAD/RNNoise，也不会积累一轮语音。
     * 测试音频源不支持常驻唤醒，避免 fixture 在后台无限循环触发。
     */
    @Synchronized
    fun startMonitoring(): Boolean {
        if (testAudioSource != null) return false
        monitoringRequested = true
        if (ensureMicrophoneCapture()) return true
        monitoringRequested = false
        return false
    }

    /** 停止待机监听；若当前仍在收集一轮语音，麦克风保持运行到 [stop]。 */
    @Synchronized
    fun stopMonitoring() {
        monitoringRequested = false
        if (!turnActive) stopCapture()
    }

    /** 播报开始/结束时切换打断 VAD；不创建第二路 AudioRecord。 */
    fun setOpenMicBargeInListening(enabled: Boolean) {
        if (enabled && openMicBargeInAvailable && bargeInVad != null) {
            bargeInVad.resetDiagnostics()
            synchronized(bargeInPreRoll) {
                bargeInPreRoll.clear()
                pendingBargeInPreRoll = emptyList()
            }
            bargeInGate.start()
        } else {
            bargeInGate.stop()
        }
    }

    /** 待机原始 PCM 同时喂给打断 VAD；每次播报最多触发一次。 */
    fun detectOpenMicBargeIn(block: ByteArray): Boolean {
        val vad = bargeInVad ?: return false
        if (!openMicBargeInAvailable || turnActive || !bargeInGate.listening) return false
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

    /** 启动录音；已在录或创建 AudioRecord 失败（如缺 RECORD_AUDIO 权限）时返回 false。 */
    @Synchronized
    fun start(includeBargeInPreRoll: Boolean = false): Boolean {
        if (turnActive) return false
        val source = testAudioSource
        if (source != null) {
            // 测试模式（Task 58）：屏蔽麦克风，预置语音按真实节奏喂双网格，无需录音权限
            Log.i(TAG, "测试音频源模式：${source.describe()}")
            source.reset() // 每轮从头播（游标跨轮不重置 → 段起点随机，Task 58 联调发现）
            synchronized(turnProcessingLock) {
                vadSegmenter?.resetForTurn()
                turnActive = true
            }
            readJob = scope.launch { testReadLoop(source) }
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
        synchronized(turnProcessingLock) {
            vadSegmenter?.resetForTurn()
            turnActive = true
            preRoll.forEach(::processTurnBlock)
        }
        return true
    }

    /**
     * 抬手取段（Task 49 双路：云端路段的 VAD 切段，时间顺序）。
     * 必须在 [stop] 之后调用（feed 已停，[VadSegmenter.finish] 与录音线程互斥安全）；
     * VAD 不可用时返回空列表。
     */
    fun finishSegments(): List<ByteArray> {
        val segments = vadSegmenter?.finish() ?: emptyList()
        // Task 55 诊断：云端段为空时区分"VAD 未启用/概率过低/未切段"
        val vs = vadSegmenter
        Log.i(
            TAG,
            "VAD 诊断: vad=$vadAvailable segments=${segments.size} maxProb=${vs?.maxProbability} " +
                "startEvt=${vs?.speechStartEvents} endEvt=${vs?.speechEndEvents} " +
                "blocks=${vs?.blockCount} openStart=${vs?.openStart}",
        )
        return segments
    }

    /**
     * 结束本轮收集（幂等）。若唤醒监听仍启用，只关闭 VAD/RNNoise 分支，底层
     * AudioRecord 与原始 PCM 流保持不变；否则释放麦克风。
     */
    @Synchronized
    fun stop() {
        synchronized(turnProcessingLock) { turnActive = false }
        if (testAudioSource != null || !monitoringRequested) stopCapture()
    }

    private fun stopCapture() {
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
        releaseCaptureEffects()
    }

    /** 释放 VAD 切分器 / RNNoise / 协程 scope（释放后不可再 [start]）。 */
    override fun close() {
        monitoringRequested = false
        turnActive = false
        stopCapture()
        try {
            vadSegmenter?.close()
        } catch (_: Throwable) {
        }
        try {
            bargeInVad?.close()
        } catch (_: Throwable) {
        }
        try {
            denoiser.close()
        } catch (_: Throwable) {
        }
        scope.cancel()
    }

    // ------------------------------------------------------------------ 读取循环

    /** 测试模式读循环：预置语音按真实 32ms/块节奏循环喂双网格（VAD 切段 + RNNoise 降噪）。 */
    private suspend fun testReadLoop(source: TestAudioSource) {
        val block = ByteArray(AudioFormat.BLOCK_BYTES)
        while (currentCoroutineContext().isActive) {
            source.nextBlock(block)
            try {
                handleCapturedBlock(block)
            } catch (t: Throwable) {
                // 单块处理失败（RNNoise 异常）不中断录音，静默降级
                Log.w(TAG, "block processing failed, degraded silently", t)
            }
            delay(AudioFormat.BLOCK_MS.toLong())
        }
    }

    private suspend fun readLoop(audioRecord: AudioRecord) {
        val block = ByteArray(AudioFormat.BLOCK_BYTES)
        while (currentCoroutineContext().isActive) {
            if (!readFully(audioRecord, block)) {
                break // 停止 / 读错误
            }
            try {
                handleCapturedBlock(block)
            } catch (t: Throwable) {
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
        _rawPcmBlocks.tryEmit(block.copyOf())
        synchronized(turnProcessingLock) {
            if (turnActive) processTurnBlock(block)
        }
    }

    private fun processTurnBlock(block: ByteArray) {
        vadSegmenter?.feed(block)?.let { _vadEvents.tryEmit(it) }
        val denoised = denoiser.process(first480Frame(pcm16BytesToShorts(block)))
        _pcmBlocks.tryEmit(pcm16ShortsToBytes(denoised))
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
                // VOICE_COMMUNICATION 请求设备的语音前处理路径，为 AEC 提供回参。
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
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
            Log.w(TAG, "AudioRecord creation failed, degraded silently", t)
            null
        }
    }

    /** 创建一次 AudioRecord 并持续读；唤醒/语音只切换观察分支，不重建麦克风。 */
    private fun ensureMicrophoneCapture(): Boolean {
        if (readJob?.isActive == true && record != null) return true
        val audioRecord = createAudioRecord() ?: return false
        record = audioRecord
        attachCaptureEffects(audioRecord.audioSessionId)
        try {
            audioRecord.startRecording()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord.startRecording failed, degraded silently", t)
            audioRecord.release()
            releaseCaptureEffects()
            record = null
            return false
        }
        readJob = scope.launch { readLoop(audioRecord) }
        return true
    }

    private fun attachCaptureEffects(audioSessionId: Int) {
        releaseCaptureEffects()
        echoCanceler = if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(audioSessionId)?.also { it.enabled = true } }
                .onFailure { Log.w(TAG, "AEC 启用失败，开放式打断将禁用", it) }
                .getOrNull()
        } else {
            null
        }
        openMicBargeInAvailable = echoCanceler?.enabled == true && bargeInVad != null
        noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(audioSessionId)?.also { it.enabled = true } }
                .onFailure { Log.w(TAG, "系统降噪启用失败，继续使用 RNNoise", it) }
                .getOrNull()
        } else {
            null
        }
        Log.i(
            TAG,
            "capture effects: aec=${echoCanceler?.enabled == true} " +
                "ns=${noiseSuppressor?.enabled == true} openMicBargeIn=$openMicBargeInAvailable",
        )
    }

    private fun releaseCaptureEffects() {
        bargeInGate.stop()
        openMicBargeInAvailable = false
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
    }

    private companion object {
        const val TAG = "AudioRecorder"

        /** Silero VAD v5 模型资产（adapter-local assets，随 APK 打包）。 */
        const val SILERO_VAD_ASSET = "silero_vad.onnx"

        /** SharedFlow 缓冲（块：不阻塞读取循环）。 */
        const val BUFFER_CAPACITY = 16

        /** 保留触发前约 384ms PCM，包含 VAD 确认窗口和少量话首。 */
        const val BARGE_IN_PRE_ROLL_BLOCKS = 12
    }
}

/**
 * 播报期打断门：连续有效人声 160ms 才触发，触发后立即自动关闭，
 * 由上层建立新话语。AEC 是否可用由 [AudioRecorder] 额外门控。
 */
internal class OpenMicBargeInGate(
    private val gate: VoiceActivityGate = VoiceActivityGate(
        threshold = 0.65f,
        minSpeechMs = 160,
        minSilenceMs = 320,
    ),
) {
    @Volatile
    var listening: Boolean = false
        private set

    @Synchronized
    fun start() {
        gate.reset()
        listening = true
    }

    @Synchronized
    fun stop() {
        listening = false
        gate.reset()
    }

    @Synchronized
    fun feed(probability: Float): Boolean {
        if (!listening) return false
        if (gate.feed(probability) != VadEvent.SpeechStart) return false
        listening = false
        return true
    }
}

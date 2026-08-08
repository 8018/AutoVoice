package com.autovoice.app.audio

import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.autovoice.adapterlocal.ecnr.RnnoiseProcessor
import com.autovoice.adapterlocal.vad.SileroVad
import com.autovoice.adapterlocal.vad.VadEvent
import com.autovoice.adapterlocal.vad.VoiceActivityGate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
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

    /** Silero VAD 帧：512 samples = 32ms @16k = 1024 字节（与 [VoiceActivityGate] 帧周期一致）。 */
    const val BLOCK_SAMPLES = 512
    const val BLOCK_BYTES = BLOCK_SAMPLES * BYTES_PER_SAMPLE // 1024

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
 * 录音通道（SOURCE_MIC 16k 单声道 PCM16）：AudioRecord 读 1024B/块 → 双网格。
 *
 * - VAD 网格（512 samples/块，与 [VoiceActivityGate] 帧周期 32ms 一致）：
 *   原始 1024B 块 → [SileroVad.feed] → 概率 → [VoiceActivityGate.feed] → [VadEvent]（[vadEvents]）。
 * - RNNoise 网格（480 samples/帧，独立于 VAD 网格）：块内切 480 帧、尾 32 samples 丢弃
 *   （Task 16 chunk 语义），降噪后 960B/块进 [pcmBlocks]。
 *
 * 段 PCM 装配（SpeechStart→SpeechEnd 之间收集 pcmBlocks）在 Task 20（engine），
 * recorder 保持哑通道，只出 PCM 流 + VAD 事件流。
 *
 * RECORD_AUDIO 权限由 Activity（Task 19）申请；recorder 只管录音，未授权/创建失败时
 * [start] 返回 false 并静默降级（Log.w，不抛到 UI）。
 */
class AudioRecorder(
    context: Context,
    private val vad: SileroVad = SileroVad(context, SILERO_VAD_ASSET),
    private val gate: VoiceActivityGate = VoiceActivityGate(),
    private val denoiser: RnnoiseProcessor = RnnoiseProcessor(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {

    private val _pcmBlocks = MutableSharedFlow<ByteArray>(extraBufferCapacity = BUFFER_CAPACITY)

    /** 降噪后 PCM 块流（960B/块，16k 单声道 PCM16）；段装配在 Task 20。 */
    val pcmBlocks: SharedFlow<ByteArray> = _pcmBlocks.asSharedFlow()

    private val _vadEvents = MutableSharedFlow<VadEvent>(extraBufferCapacity = BUFFER_CAPACITY)

    /** VAD 事件流（SpeechStart / SpeechEnd）。 */
    val vadEvents: SharedFlow<VadEvent> = _vadEvents.asSharedFlow()

    @Volatile
    private var record: AudioRecord? = null

    private var readJob: Job? = null

    val isRecording: Boolean get() = readJob?.isActive == true

    /** 启动录音；已在录或创建 AudioRecord 失败（如缺 RECORD_AUDIO 权限）时返回 false。 */
    @Synchronized
    fun start(): Boolean {
        if (readJob?.isActive == true) return false
        val audioRecord = createAudioRecord() ?: return false
        record = audioRecord
        try {
            audioRecord.startRecording()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord.startRecording failed, degraded silently", t)
            audioRecord.release()
            record = null
            return false
        }
        readJob = scope.launch { readLoop(audioRecord) }
        return true
    }

    /** 停止录音并释放 AudioRecord（幂等）；scope 不取消，可再次 [start]。 */
    @Synchronized
    fun stop() {
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
    }

    /** 释放 VAD / RNNoise / 协程 scope（释放后不可再 [start]）。 */
    override fun close() {
        stop()
        try {
            vad.close()
        } catch (_: Throwable) {
        }
        try {
            denoiser.close()
        } catch (_: Throwable) {
        }
        scope.cancel()
    }

    // ------------------------------------------------------------------ 读取循环

    private suspend fun readLoop(audioRecord: AudioRecord) {
        val block = ByteArray(AudioFormat.BLOCK_BYTES)
        while (currentCoroutineContext().isActive) {
            if (!readFully(audioRecord, block)) break // 停止 / 读错误
            try {
                processBlock(block)
            } catch (t: Throwable) {
                // 单块处理失败（VAD/RNNoise 异常）不中断录音，静默降级
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

    /** 单块双网格处理：VAD 事件 + RNNoise 降噪 → pcmBlocks（960B/块）。 */
    private fun processBlock(block: ByteArray) {
        // VAD 网格：原始块直喂 Silero（512 samples），概率 → 门控事件
        val probability = vad.feed(block)
        gate.feed(probability)?.let { _vadEvents.tryEmit(it) }
        // RNNoise 网格：块内切 480 帧（尾 32 samples 丢弃）→ 降噪 → 960B
        val denoised = denoiser.process(first480Frame(pcm16BytesToShorts(block)))
        _pcmBlocks.tryEmit(pcm16ShortsToBytes(denoised))
    }

    // ------------------------------------------------------------------ 资源

    private fun createAudioRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioFormat.SAMPLE_RATE,
            AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBuffer, AudioFormat.BLOCK_BYTES * 2)
        return try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AudioFormat.SAMPLE_RATE,
                AndroidAudioFormat.CHANNEL_IN_MONO,
                AndroidAudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (t: Throwable) {
            // 缺 RECORD_AUDIO 权限（SecurityException）/ 设备不支持（UnsupportedOperationException 等）
            Log.w(TAG, "AudioRecord creation failed, degraded silently", t)
            null
        }
    }

    private companion object {
        const val TAG = "AudioRecorder"

        /** 模型随 adapter-local 的 assets 并入 APK。 */
        const val SILERO_VAD_ASSET = "silero_vad.onnx"

        /** SharedFlow 缓冲（块：不阻塞读取循环；事件：不丢边界事件）。 */
        const val BUFFER_CAPACITY = 16
    }
}

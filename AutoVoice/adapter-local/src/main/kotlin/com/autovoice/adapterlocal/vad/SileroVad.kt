package com.autovoice.adapterlocal.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD v5 的 onnxruntime 封装（16k 单声道 PCM16，一次一帧）。
 *
 * 模型：`adapter-local/src/main/assets/silero_vad.onnx`（snakers4/silero-vad v5 导出，~2.3MB，
 * 与官方仓库 `src/silero_vad/data/silero_vad.onnx` 逐字节一致）。
 * 图结构（Task 48 对照官方 [OnnxWrapper](https://github.com/snakers4/silero-vad/blob/master/src/silero_vad/utils_vad.py) 实测）：
 *  - input:  float32 [1, 576]，**上一帧尾部 64 samples 的 context + 本帧 512 samples**——
 *            官方 __call__ 先 `torch.cat([self._context, x])` 再喂模型；
 *  - state:  float32 [2, 1, 128]，LSTM (h,c) 拼接，跨帧携带上下文；
 *  - sr:     int64 scalar，音频采样率（16k）；
 *  - output: float32 [1, 1]，本帧语音概率；
 *  - stateN: float32 [2, 1, 128]，更新后的 state（喂回下一次推理）。
 *
 * 坑（Task 48 根因）：只喂 [1,512]（无 64-sample context）时模型概率恒压 ~0——官方导出
 * 的 input 是 [None, None] 动态形状，onnxruntime 不校验宽度，静默吞掉截断窗口。
 * [feed] 强制 1024 字节（512 samples）一帧并维护 64-sample context 跨帧携带。
 * 多窗口（如 [1,1024]）仍会触发图内 If 分支的 LSTM 维度错误，勿用。
 */
class SileroVad private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : AutoCloseable {

    /** 从 assets 加载模型（Library assets 自动并入 APK）。 */
    constructor(context: Context, modelAsset: String) : this(
        OrtEnvironment.getEnvironment(),
        createSession(context.assets.open(modelAsset).use { it.readBytes() }),
    )

    /** 从模型字节加载（供 JVM 单测真实推理使用）。 */
    internal constructor(modelBytes: ByteArray) : this(
        OrtEnvironment.getEnvironment(),
        createSession(modelBytes),
    )

    private val inputName = resolveName(session.inputNames, 0, "input")
    private val stateName = resolveName(session.inputNames, 1, "state")
    private val srName = resolveName(session.inputNames, 2, "sr")
    private val outputName = resolveName(session.outputNames, 0, "output")
    private val stateNName = resolveName(session.outputNames, 1, "stateN")

    /** LSTM state（初始为零，首次推理由模型从静音基线起步）。 */
    private var state = FloatArray(256) // [2, 1, 128]

    /** 跨帧 context（官方约定：上一帧尾部 64 samples，Task 48 修复前缺失导致概率恒 ~0）。 */
    private var context = FloatArray(64)

    /** 本轮录音最高语音概率（诊断用：云端段为空时区分"模型概率低"与"未切段"）。 */
    @Volatile
    var maxProbability = 0f

    /** 本轮录音开始前重置诊断峰值（maxProbability 跨轮累计会掩盖低概率轮）。 */
    fun resetDiagnostics() {
        maxProbability = 0f
    }

    init {
        // 读图 metadata 校验输入形状：input 必须是 rank-2（[batch, samples]）
        val info = session.inputInfo[inputName]?.info as? TensorInfo
            ?: error("silero model: cannot read input metadata for '$inputName'")
        check(info.shape.size == 2) {
            "silero model: unexpected input rank ${info.shape.size} (shape=${info.shape.contentToString()}), expected rank-2"
        }
    }

    /**
     * 喂入一帧 16k 单声道 PCM16 音频（必须恰好 512 samples / 1024 字节），
     * 返回本帧语音概率（0..1）。
     */
    @Synchronized
    fun feed(pcm16k: ByteArray): Float {
        require(pcm16k.size == 1024) {
            "silero vad expects exactly one 512-sample (1024-byte) PCM16 frame @16k, got ${pcm16k.size} bytes"
        }
        val samples = ShortArray(512)
        ByteBuffer.wrap(pcm16k).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        // 官方约定：模型输入 = 上一帧尾部 64 samples context + 本帧 512 samples（[1, 576]）
        val waveform = FloatArray(576)
        context.copyInto(waveform, 0)
        for (i in samples.indices) waveform[i + 64] = samples[i] / 32768.0f
        context = waveform.copyOfRange(512, 576) // 更新 context：拼接窗口尾部 64 samples

        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(waveform), longArrayOf(1L, 576L))
        val stateTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2L, 1L, 128L))
        // sr：int64 scalar（[] shape），等价于 python 侧的 np.array(16000, dtype=np.int64)
        val srTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000L)), longArrayOf())

        val result = session.run(
            mapOf(
                inputName to inputTensor,
                stateName to stateTensor,
                srName to srTensor,
            ),
        )
        try {
            val prob = (result.get(outputName).get() as OnnxTensor).floatBuffer.get(0)
            if (prob > maxProbability) maxProbability = prob
            val stateN = (result.get(stateNName).get() as OnnxTensor).floatBuffer
            val next = FloatArray(stateN.remaining())
            stateN.get(next)
            state = next
            return prob
        } finally {
            result.close()
        }
    }

    override fun close() {
        // OrtEnvironment.getEnvironment() 是共享单例，这里只关 session，不关 env
        session.close()
    }

    private companion object {
        fun createSession(modelBytes: ByteArray): OrtSession {
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(1)
            }
            return OrtEnvironment.getEnvironment().createSession(modelBytes, options)
        }

        fun resolveName(names: Set<String>, fallbackIndex: Int, preferred: String): String =
            if (preferred in names) preferred else names.toList()[fallbackIndex]
    }
}

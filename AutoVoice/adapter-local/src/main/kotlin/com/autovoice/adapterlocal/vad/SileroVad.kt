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
 * 模型：`adapter-local/src/main/assets/silero_vad.onnx`（snakers4/silero-vad v5 导出，~2.3MB）。
 * 图结构（用 onnxruntime 读图 metadata 验证）：
 *  - input:  float32 [1, 512]，原始波形（一帧 512 samples = 32ms @16k），模型内部做 STFT/mel；
 *  - state:  float32 [2, 1, 128]，LSTM (h,c) 拼接，跨帧携带上下文；
 *  - sr:     int64 scalar，音频采样率（16k）；
 *  - output: float32 [1, 1]，本帧语音概率；
 *  - stateN: float32 [2, 1, 128]，更新后的 state（喂回下一次推理）。
 *
 * 注意：v5 导出只接受 [1,512] 单窗口输入（[1,1024] 等多窗口会触发图内 If 分支的
 * LSTM 维度错误），因此 [feed] 强制 1024 字节（512 samples）一帧。
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

    init {
        // 读图 metadata 校验输入形状：input 必须是 rank-2（[batch, samples]）
        val info = session.inputInfo[inputName]?.info as? TensorInfo
            ?: error("silero model: cannot read input metadata for '$inputName'")
        check(info.shape.size == 2) {
            "silero model: unexpected input rank ${info.shape.size} (shape=${info.shape.contentToString()}), expected rank-2 [1, 512]"
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
        val waveform = FloatArray(512)
        for (i in samples.indices) waveform[i] = samples[i] / 32768.0f

        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(waveform), longArrayOf(1L, 512L))
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

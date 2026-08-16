package com.autovoice.voicecore

/**
 * 独立 ASR 输出。流式 ASR/PGS 可多次回调 partial，最终以 [isFinal] 收敛。
 * ASR 结果只用于识别展示和 NLU 输入，不参与语义仲裁。
 */
data class AsrResult(
    val text: String,
    val isFinal: Boolean = true,
)

/** ASR 阶段：PCM → 0..n 个识别结果。 */
fun interface AsrStage {
    suspend fun recognize(segment: ByteArray, onResult: (AsrResult) -> Unit): AsrResult?
}

/**
 * NLU 阶段输出。某些 2C/命令词引擎在语义中自带识别文本，放入 [recognizedText]；
 * 只有该 NLU 候选最终胜出时，应用层才用它二次刷新识别框。
 */
data class NluResult(
    val intent: Intent,
    val recognizedText: String? = null,
)

/**
 * NLU 阶段：PCM + 可选最终 ASR 文本 → 语义候选。
 * 传统 NLU 使用 asr.text；2C 命令词引擎可直接消费 PCM 并在结果中携带 recognizedText。
 */
fun interface NluStage {
    suspend fun understand(segment: ByteArray, asr: AsrResult?): NluResult
}

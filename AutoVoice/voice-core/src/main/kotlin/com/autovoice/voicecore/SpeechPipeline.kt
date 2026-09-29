package com.autovoice.voicecore

/**
 * 独立 ASR 输出。流式 ASR/PGS 可多次回调 partial，最终以 [isFinal] 收敛。
 * ASR 结果只用于识别展示和 NLU 输入，不参与语义仲裁。
 */
data class AsrResult(
    val text: String,
    val isFinal: Boolean = true,
)

/**
 * ASR 的两个独立输出。识别文本用于 UI/后续 NLU；只有 ASR 自己确认话语成立后才发送
 * [onTurnEstablished]。状态机不得根据文本长度、partial/final 或内容自行推断新轮。
 */
interface AsrSink {
    fun onTranscript(result: AsrResult)

    fun onTurnEstablished()

    companion object {
        val NOOP: AsrSink = object : AsrSink {
            override fun onTranscript(result: AsrResult) = Unit
            override fun onTurnEstablished() = Unit
        }
    }
}

/**
 * ASR 能力：为一轮输入建立独立识别输出。实现可以同步返回最终文本，也可以在返回后继续
 * 经 [sink] 推送 PGS/云端流式结果。云端实现只订阅已共享上传的音频轮，绝不再次上传 PCM。
 * [turnId] 是请求相关 ID，不表示会话状态机已采用该轮。
 */
fun interface AsrEngine {
    fun recognize(turnId: String, segment: ByteArray, sink: AsrSink): AsrResult?
}

/** Legacy migration name. New composition code must depend on [AsrEngine]. */
@Deprecated("Use AsrEngine")
typealias AsrStage = AsrEngine

/**
 * NLU 阶段输出。某些 2C/命令词引擎在语义中自带识别文本，放入 [recognizedText]；
 * 只有该 NLU 候选最终胜出时，应用层才用它二次刷新识别框。
 */
data class NluResult(
    val intent: Intent,
    val recognizedText: String? = null,
)

/**
 * NLU 阶段：话语输入 + 可选最终 ASR 文本 → 语义候选。
 * 端侧返回 [NluResult]；云端返回保留音频、动作及文本的 Reply。二者在 VoiceEngine
 * 的候选链中组合，均须经仲裁与会话准入后才进入业务处理。
 */
fun interface NluEngine<out Result> {
    suspend fun understand(turnId: String, segment: ByteArray, asr: AsrResult?): Result
}

/** One VoiceEngine candidate route is explicitly composed from independent ASR and NLU modules. */
interface SpeechRouteModules<out Result> {
    val asr: AsrEngine
    val nlu: NluEngine<Result>
}

/** Legacy local NLU migration name. New composition code must depend on [NluEngine]. */
@Deprecated("Use NluEngine")
typealias NluStage = NluEngine<NluResult>

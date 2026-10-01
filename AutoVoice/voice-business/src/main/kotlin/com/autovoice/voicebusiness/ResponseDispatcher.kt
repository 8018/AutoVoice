package com.autovoice.voicebusiness

import com.autovoice.business.BusinessCommand
import com.autovoice.business.BusinessHandler
import com.autovoice.business.BusinessResult
import com.autovoice.tts.TtsOutput
import com.autovoice.tts.RealtimePlaybackToken
import com.autovoice.voicecore.ActionReply
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.NluResult
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.voicecore.TextReply

/** Routes an accepted semantic result to UI, business execution and speech output. */
class ResponseDispatcher(
    private val output: TtsOutput,
    private val business: BusinessHandler,
    private val onExecution: (String, Intent, BusinessResult) -> Unit = { _, _, _ -> },
    private val onFailure: (String) -> Unit = {},
    private val isCurrentTurn: (String) -> Boolean,
    private val onRecognized: (String?) -> Unit,
    private val onReplyText: (String) -> Unit,
) {
    enum class Outcome { OUTPUT_REQUESTED, NO_OUTPUT, STALE }

    fun dispatchCloud(turnId: String, reply: Reply): Outcome {
        if (!isCurrentTurn(turnId)) return Outcome.STALE
        if (reply.asrText.isNotBlank()) onRecognized(reply.asrText)
        return when (reply) {
            is AudioReply -> {
                val result = reply.intent?.let { execute(turnId, it) }
                    ?: BusinessResult.applied()
                if (result.status == BusinessResult.Status.APPLIED) {
                    if (reply.speakText.isNotBlank()) onReplyText(reply.speakText)
                    output.play(turnId, reply)
                    Outcome.OUTPUT_REQUESTED
                } else if (result.status == BusinessResult.Status.FAILED) {
                    reportExecutionFailure(turnId)
                    Outcome.OUTPUT_REQUESTED
                } else Outcome.NO_OUTPUT
            }
            is StreamingAudioReply -> {
                output.playStream(turnId, reply) { end ->
                    if (end.speakText.isNotBlank()) onReplyText(end.speakText)
                    if (end.asrText.isNotBlank()) onRecognized(end.asrText)
                    end.intent?.let { execute(turnId, it) }
                }
                Outcome.OUTPUT_REQUESTED
            }
            is TextReply -> {
                if (reply.text.isNotBlank()) onReplyText(reply.text)
                if (reply.text.isBlank()) Outcome.NO_OUTPUT else {
                    output.speak(turnId, reply.text)
                    Outcome.OUTPUT_REQUESTED
                }
            }
            is ActionReply -> {
                when (execute(turnId, reply.intent).status) {
                    BusinessResult.Status.APPLIED -> {
                        if (reply.speakText.isNotBlank()) onReplyText(reply.speakText)
                        if (reply.speakText.isBlank()) Outcome.NO_OUTPUT else {
                            output.speak(turnId, reply.speakText)
                            Outcome.OUTPUT_REQUESTED
                        }
                    }
                    BusinessResult.Status.FAILED -> {
                        reportExecutionFailure(turnId)
                        Outcome.OUTPUT_REQUESTED
                    }
                    else -> Outcome.NO_OUTPUT
                }
            }
        }
    }

    fun dispatchLocal(turnId: String, nlu: NluResult): Outcome {
        nlu.recognizedText?.takeIf(String::isNotBlank)?.let(onRecognized)
        val result = execute(turnId, nlu.intent)
        val applied = result.speakText?.takeIf { result.status == BusinessResult.Status.APPLIED && it.isNotBlank() }
            ?: return Outcome.NO_OUTPUT
        output.speak(turnId, applied)
        return Outcome.OUTPUT_REQUESTED
    }

    fun dispatchFailure(turnId: String) {
        onFailure(turnId)
        output.speak(turnId, FALLBACK_PHRASE)
    }

    fun dispatchRealtime(token: RealtimePlaybackToken, reply: StreamingAudioReply) {
        output.playRealtimeStream(token, reply) { end ->
            if (end.speakText.isNotBlank()) onReplyText(end.speakText)
            end.intent?.takeIf { it.domain == "conversation" }?.let {
                business.handle(BusinessCommand("realtime", it))
            }
        }
    }

    private fun execute(
        turnId: String,
        intent: Intent,
    ): BusinessResult {
        if (turnId.isBlank() || !isCurrentTurn(turnId)) {
            return BusinessResult.rejected()
        }
        val result = business.handle(BusinessCommand(turnId, intent))
        recordExecution(turnId, intent, result)
        return result
    }

    private fun reportExecutionFailure(turnId: String) {
        if (!isCurrentTurn(turnId)) return
        onReplyText(EXECUTION_FAILED_PHRASE)
        output.speak(turnId, EXECUTION_FAILED_PHRASE)
    }

    private fun recordExecution(
        turnId: String,
        intent: Intent,
        result: BusinessResult,
    ) {
        onExecution(turnId, intent, result)
    }

    companion object {
        private const val FALLBACK_PHRASE = "网络开小差了，请稍后再试"
        private const val EXECUTION_FAILED_PHRASE = "这个操作没有执行，请再说一次"
    }
}

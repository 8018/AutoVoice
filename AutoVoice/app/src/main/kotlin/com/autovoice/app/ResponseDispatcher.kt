package com.autovoice.app

import com.autovoice.app.telemetry.TelemetryClient
import com.autovoice.app.telemetry.TelemetryStages
import com.autovoice.business.BusinessCommand
import com.autovoice.business.BusinessHandler
import com.autovoice.business.BusinessResult
import com.autovoice.tts.TtsOutput
import com.autovoice.voicecore.ActionReply
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.NluResult
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.voicecore.TextReply

/** Routes an accepted semantic result to UI, business execution and speech output. */
internal class ResponseDispatcher(
    private val output: TtsOutput,
    private val business: BusinessHandler,
    private val telemetry: TelemetryClient,
    private val isCurrentTurn: (String) -> Boolean,
    private val onRecognized: (String?) -> Unit,
    private val onReplyText: (String) -> Unit,
) {
    fun dispatchCloud(turnId: String, reply: Reply) {
        if (!isCurrentTurn(turnId)) return
        if (reply.asrText.isNotBlank()) onRecognized(reply.asrText)
        when (reply) {
            is AudioReply -> {
                val result = reply.intent?.let { execute(turnId, it) }
                    ?: BusinessResult.applied()
                if (result.status == BusinessResult.Status.APPLIED) {
                    if (reply.speakText.isNotBlank()) onReplyText(reply.speakText)
                    output.play(turnId, reply)
                } else if (result.status == BusinessResult.Status.FAILED) {
                    reportExecutionFailure(turnId)
                }
            }
            is StreamingAudioReply -> output.playStream(turnId, reply) { end ->
                if (end.speakText.isNotBlank()) onReplyText(end.speakText)
                if (end.asrText.isNotBlank()) onRecognized(end.asrText)
                end.intent?.let { execute(turnId, it) }
            }
            is TextReply -> {
                if (reply.text.isNotBlank()) onReplyText(reply.text)
                output.speak(turnId, reply.text)
            }
            is ActionReply -> {
                when (execute(turnId, reply.intent).status) {
                    BusinessResult.Status.APPLIED -> {
                        if (reply.speakText.isNotBlank()) onReplyText(reply.speakText)
                        output.speak(turnId, reply.speakText)
                    }
                    BusinessResult.Status.FAILED -> reportExecutionFailure(turnId)
                    else -> Unit
                }
            }
        }
    }

    fun dispatchLocal(turnId: String, nlu: NluResult) {
        nlu.recognizedText?.takeIf(String::isNotBlank)?.let(onRecognized)
        val result = execute(turnId, nlu.intent)
        val applied = result.speakText.takeIf { result.status == BusinessResult.Status.APPLIED }
        applied?.let {
            output.speak(turnId, it)
        }
    }

    fun dispatchFailure(turnId: String) {
        telemetry.recordFor(turnId, TelemetryStages.EXECUTE, "warn", mapOf("result" to "failed"))
        output.speak(turnId, FALLBACK_PHRASE)
    }

    fun dispatchRealtime(reply: StreamingAudioReply) {
        output.playStream("", reply) { end ->
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
        telemetry.recordFor(
            turnId,
            TelemetryStages.EXECUTE,
            "info",
            mapOf(
                "intent" to intentSummary(intent),
                "result" to result.status.name.lowercase(),
                "speakText" to (result.speakText ?: ""),
            ),
        )
    }

    private fun intentSummary(intent: Intent): String = "${intent.domain}/${intent.intent}"

    companion object {
        private const val FALLBACK_PHRASE = "网络开小差了，请稍后再试"
        private const val EXECUTION_FAILED_PHRASE = "这个操作没有执行，请再说一次"
    }
}

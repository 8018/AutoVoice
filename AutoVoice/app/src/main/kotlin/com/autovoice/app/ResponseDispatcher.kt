package com.autovoice.app

import com.autovoice.app.telemetry.TelemetryClient
import com.autovoice.app.telemetry.TelemetryStages
import com.autovoice.voicecore.ActionReply
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.NluResult
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.voicecore.TextReply

/** Routes an accepted semantic result to UI, business execution and speech output. */
internal class ResponseDispatcher(
    private val output: SpeechOutputService,
    private val vehicle: MockVehicleState,
    private val navigation: NavigationExecutor?,
    private val telemetry: TelemetryClient,
    private val isCurrentTurn: (String) -> Boolean,
    private val onVehicleApplied: () -> Unit,
    private val onRecognized: (String?) -> Unit,
    private val onReplyText: (String) -> Unit,
    private val onConversationMode: (Boolean) -> Unit,
) {
    fun dispatchCloud(turnId: String, reply: Reply) {
        if (!isCurrentTurn(turnId)) return
        if (reply.asrText.isNotBlank()) onRecognized(reply.asrText)
        when (reply) {
            is AudioReply -> {
                if (reply.speakText.isNotBlank()) onReplyText(reply.speakText)
                output.play(turnId, reply)
                reply.intent?.takeIf { isCurrentTurn(turnId) }?.let { applyAndNotify(turnId, it) }
            }
            is StreamingAudioReply -> output.playStream(turnId, reply) { end ->
                if (end.speakText.isNotBlank()) onReplyText(end.speakText)
                if (end.asrText.isNotBlank()) onRecognized(end.asrText)
                end.intent?.let { applyAndNotify(turnId, it) }
            }
            is TextReply -> {
                if (reply.text.isNotBlank()) onReplyText(reply.text)
                output.speak(turnId, reply.text)
            }
            is ActionReply -> {
                if (isCurrentTurn(turnId)) applyAndNotify(turnId, reply.intent)
                if (reply.speakText.isNotBlank()) onReplyText(reply.speakText)
                output.speak(turnId, reply.speakText)
            }
        }
    }

    fun dispatchLocal(turnId: String, nlu: NluResult) {
        nlu.recognizedText?.takeIf(String::isNotBlank)?.let(onRecognized)
        val applied = vehicle.apply(nlu.intent)
        telemetry.recordFor(
            turnId,
            TelemetryStages.EXECUTE,
            "info",
            mapOf(
                "intent" to intentSummary(nlu.intent),
                "result" to if (applied != null) "applied" else "skipped",
                "speakText" to (applied ?: ""),
            ),
        )
        applied?.let {
            onVehicleApplied()
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
            end.intent?.let { applyAndNotify("", it) }
        }
    }

    private fun applyAndNotify(turnId: String, intent: Intent) {
        if (intent.domain == "conversation") {
            val applied = when (intent.intent) {
                "enter_chat" -> true.also { onConversationMode(true) }
                "exit_chat" -> true.also { onConversationMode(false) }
                else -> false
            }
            recordExecution(turnId, intent, applied)
            return
        }
        val applied = if (intent.domain == NavigationExecutor.DOMAIN_NAVIGATION) {
            navigation?.execute(intent) ?: false
        } else {
            vehicle.apply(intent) != null
        }
        recordExecution(turnId, intent, applied)
        if (applied && intent.domain != NavigationExecutor.DOMAIN_NAVIGATION) onVehicleApplied()
    }

    private fun recordExecution(turnId: String, intent: Intent, applied: Boolean) {
        telemetry.recordFor(
            turnId,
            TelemetryStages.EXECUTE,
            "info",
            mapOf("intent" to intentSummary(intent), "result" to if (applied) "applied" else "skipped"),
        )
    }

    private fun intentSummary(intent: Intent): String = "${intent.domain}/${intent.intent}"

    companion object {
        private const val FALLBACK_PHRASE = "网络开小差了，请稍后再试"
    }
}

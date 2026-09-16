package com.autovoice.app

import com.autovoice.app.action.ActionExecutionGateway
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
    private val actionGateway: com.autovoice.app.action.ActionExecutionGateway =
        com.autovoice.app.action.ActionExecutionGateway(),
) {
    fun dispatchCloud(turnId: String, reply: Reply) {
        if (!isCurrentTurn(turnId)) return
        if (reply.asrText.isNotBlank()) onRecognized(reply.asrText)
        when (reply) {
            is AudioReply -> {
                val result = reply.intent?.let { applyAndNotify(turnId, it) }
                    ?: ActionExecutionGateway.Result.APPLIED
                if (result == ActionExecutionGateway.Result.APPLIED) {
                    if (reply.speakText.isNotBlank()) onReplyText(reply.speakText)
                    output.play(turnId, reply)
                } else if (result == ActionExecutionGateway.Result.FAILED) {
                    reportExecutionFailure(turnId)
                }
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
                when (applyAndNotify(turnId, reply.intent)) {
                    ActionExecutionGateway.Result.APPLIED -> {
                        if (reply.speakText.isNotBlank()) onReplyText(reply.speakText)
                        output.speak(turnId, reply.speakText)
                    }
                    ActionExecutionGateway.Result.FAILED -> reportExecutionFailure(turnId)
                    else -> Unit
                }
            }
        }
    }

    fun dispatchLocal(turnId: String, nlu: NluResult) {
        nlu.recognizedText?.takeIf(String::isNotBlank)?.let(onRecognized)
        var appliedText: String? = null
        val result = actionGateway.execute(turnId) {
            appliedText = vehicle.apply(nlu.intent)
            appliedText != null
        }
        val applied = if (result == ActionExecutionGateway.Result.APPLIED) appliedText else null
        telemetry.recordFor(
            turnId,
            TelemetryStages.EXECUTE,
            "info",
            mapOf(
                "intent" to intentSummary(nlu.intent),
                "result" to result.name.lowercase(),
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
            end.intent?.takeIf { it.domain == "conversation" }?.let { applyAndNotify("", it) }
        }
    }

    private fun applyAndNotify(
        turnId: String,
        intent: Intent,
    ): ActionExecutionGateway.Result {
        if (intent.domain == "conversation") {
            // 会话控制不属于副作用动作,不走执行网关
            val applied = when (intent.intent) {
                "enter_chat" -> true.also { onConversationMode(true) }
                "exit_chat" -> true.also { onConversationMode(false) }
                else -> false
            }
            val result = if (applied) ActionExecutionGateway.Result.APPLIED
            else ActionExecutionGateway.Result.FAILED
            recordExecution(turnId, intent, result)
            return result
        }
        if (turnId.isBlank() || !isCurrentTurn(turnId)) {
            return ActionExecutionGateway.Result.INVALID_TURN
        }
        val result = actionGateway.execute(turnId) {
            if (intent.domain == NavigationExecutor.DOMAIN_NAVIGATION) {
                navigation?.execute(intent) == true
            } else {
                vehicle.apply(intent) != null
            }
        }
        recordExecution(turnId, intent, result)
        if (result == ActionExecutionGateway.Result.APPLIED &&
            intent.domain != NavigationExecutor.DOMAIN_NAVIGATION
        ) onVehicleApplied()
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
        result: ActionExecutionGateway.Result,
    ) {
        telemetry.recordFor(
            turnId,
            TelemetryStages.EXECUTE,
            "info",
            mapOf("intent" to intentSummary(intent), "result" to result.name.lowercase()),
        )
    }

    private fun intentSummary(intent: Intent): String = "${intent.domain}/${intent.intent}"

    companion object {
        private const val FALLBACK_PHRASE = "网络开小差了，请稍后再试"
        private const val EXECUTION_FAILED_PHRASE = "这个操作没有执行，请再说一次"
    }
}

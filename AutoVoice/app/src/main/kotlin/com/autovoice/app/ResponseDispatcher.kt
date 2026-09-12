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
    /** D07b 客户端最终准入 + 原子执行抢占;默认直通(旧测试/装配不感知)。 */
    private val actionGateway: com.autovoice.app.action.ActionExecutionGateway =
        com.autovoice.app.action.ActionExecutionGateway(
            object : com.autovoice.app.action.ActionLedgerStore {
                override fun claim(actionId: String, summary: String) = true
                override fun markTerminal(actionId: String, state: String) {}
                override fun stateOf(actionId: String): String? = null
                override fun recoverUnknowns() {}
            }),
) {
    fun dispatchCloud(turnId: String, reply: Reply) {
        if (!isCurrentTurn(turnId)) return
        if (reply.asrText.isNotBlank()) onRecognized(reply.asrText)
        when (reply) {
            is AudioReply -> {
                if (reply.speakText.isNotBlank()) onReplyText(reply.speakText)
                output.play(turnId, reply)
                reply.intent?.takeIf { isCurrentTurn(turnId) }
                    ?.let { applyAndNotify(turnId, it, reply.actionId, reply.actionExpiresAtMs) }
            }
            is StreamingAudioReply -> output.playStream(turnId, reply) { end ->
                if (end.speakText.isNotBlank()) onReplyText(end.speakText)
                if (end.asrText.isNotBlank()) onRecognized(end.asrText)
                end.intent?.let { applyAndNotify(turnId, it, "local-" + java.util.UUID.randomUUID()) } // 流式端暂无服务端签发,先本地身份记账(D07c 覆盖)
            }
            is TextReply -> {
                if (reply.text.isNotBlank()) onReplyText(reply.text)
                output.speak(turnId, reply.text)
            }
            is ActionReply -> {
                if (isCurrentTurn(turnId)) {
                    applyAndNotify(turnId, reply.intent, reply.actionId, reply.actionExpiresAtMs)
                }
                if (reply.speakText.isNotBlank()) onReplyText(reply.speakText)
                output.speak(turnId, reply.speakText)
            }
        }
    }

    fun dispatchLocal(turnId: String, nlu: NluResult) {
        nlu.recognizedText?.takeIf(String::isNotBlank)?.let(onRecognized)
        // D07b:本地动作生成本地身份走执行网关(手机动作手机记账,幂等/未知不重试)
        val localActionId = "local-" + java.util.UUID.randomUUID()
        var appliedText: String? = null
        val executed = actionGateway.execute(localActionId, nlu.intent.intent) {
            appliedText = vehicle.apply(nlu.intent)
            appliedText != null
        }
        val applied = if (executed) appliedText else null
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
            end.intent?.let { applyAndNotify("", it, "local-" + java.util.UUID.randomUUID()) } // 流式端暂无服务端签发,先本地身份记账(D07c 覆盖)
        }
    }

    private fun applyAndNotify(
        turnId: String,
        intent: Intent,
        actionId: String,
        actionExpiresAtMs: Long = 0L,
    ) {
        if (intent.domain == "conversation") {
            // 会话控制不属于副作用动作,不走执行网关
            val applied = when (intent.intent) {
                "enter_chat" -> true.also { onConversationMode(true) }
                "exit_chat" -> true.also { onConversationMode(false) }
                else -> false
            }
            recordExecution(turnId, intent, applied)
            return
        }
        // D07b:副作用动作(导航/车控)经执行网关做最终准入与原子抢占;
        // 幂等命中不重复执行;真实车控当前无执行器(保持关闭)
        val applied = actionGateway.execute(actionId, intent.intent, actionExpiresAtMs) {
            if (intent.domain == NavigationExecutor.DOMAIN_NAVIGATION) {
                navigation?.execute(intent)
            } else {
                vehicle.apply(intent) != null
            }
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

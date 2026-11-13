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
import com.autovoice.voicebusiness.navigation.NavigationExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Routes an accepted semantic result to UI, business execution and speech output. */
class ResponseDispatcher(
    private val output: TtsOutput,
    private val business: BusinessHandler,
    private val onExecution: (String, Intent, BusinessResult) -> Unit = { _, _, _ -> },
    private val onFailure: (String) -> Unit = {},
    private val isCurrentTurn: (String) -> Boolean,
    private val onRecognized: (String?) -> Unit,
    private val onReplyText: (String) -> Unit,
    private val onSavedNavigationCommand: (turnId: String, recognizedText: String) -> String? = { _, _ -> null },
) {
    enum class Outcome { OUTPUT_REQUESTED, NO_OUTPUT, STALE }

    fun dispatchCloud(turnId: String, reply: Reply): Outcome {
        if (!isCurrentTurn(turnId)) return Outcome.STALE
        if (reply.asrText.isNotBlank()) onRecognized(reply.asrText)
        handleSavedNavigation(turnId, reply.asrText)?.let {
            if (reply is StreamingAudioReply) reply.chunks.cancel()
            return it
        }
        return when (reply) {
            is AudioReply -> {
                val intent = reply.intent
                val result = intent?.let { execute(turnId, it) } ?: BusinessResult.applied()
                val navigationFeedback = result.speakText?.takeIf {
                    intent?.domain == NavigationExecutor.DOMAIN_NAVIGATION
                }
                when (result.status) {
                    BusinessResult.Status.APPLIED -> {
                        if (navigationFeedback != null) {
                            speakFeedback(turnId, navigationFeedback)
                        } else {
                            if (!isCurrentTurn(turnId)) return Outcome.STALE
                            if (reply.speakText.isNotBlank()) onReplyText(reply.speakText)
                            output.play(turnId, reply)
                            Outcome.OUTPUT_REQUESTED
                        }
                    }
                    BusinessResult.Status.FAILED ->
                        speakFeedback(turnId, navigationFeedback ?: EXECUTION_FAILED_PHRASE)
                    else -> Outcome.NO_OUTPUT
                }
            }
            is StreamingAudioReply -> dispatchStream(turnId, reply)
            is TextReply -> speakFeedback(turnId, reply.text)
            is ActionReply -> dispatchAction(turnId, reply.intent, reply.speakText)
        }
    }

    private fun dispatchAction(turnId: String, intent: Intent, cloudText: String): Outcome {
        val result = execute(turnId, intent)
        val navigationFeedback = result.speakText?.takeIf { intent.domain == NavigationExecutor.DOMAIN_NAVIGATION }
        return when (result.status) {
            BusinessResult.Status.APPLIED -> speakFeedback(turnId, navigationFeedback ?: cloudText)
            BusinessResult.Status.FAILED -> speakFeedback(turnId, navigationFeedback ?: EXECUTION_FAILED_PHRASE)
            else -> Outcome.NO_OUTPUT
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun dispatchStream(turnId: String, reply: StreamingAudioReply): Outcome {
        // The start frame has no intent. If the end frame is already available at adoption,
        // navigation can be routed through the action path before any buffered PCM is played.
        val completed = if (reply.completion.isCompleted) {
            runCatching { reply.completion.getCompleted() }.getOrNull()
        } else null
        if (completed != null) {
            if (completed.asrText.isNotBlank() && completed.asrText != reply.asrText) {
                onRecognized(completed.asrText)
                handleSavedNavigation(turnId, completed.asrText)?.let {
                    reply.chunks.cancel()
                    return it
                }
            }
            val intent = completed.intent
            if (intent?.domain == NavigationExecutor.DOMAIN_NAVIGATION) {
                reply.chunks.cancel()
                return dispatchAction(turnId, intent, completed.speakText)
            }
        }
        output.playStream(turnId, reply) { end ->
            if (!isCurrentTurn(turnId)) return@playStream
            if (completed == null && end.asrText.isNotBlank() && end.asrText != reply.asrText) {
                onRecognized(end.asrText)
                if (handleSavedNavigation(turnId, end.asrText) != null) return@playStream
            }
            val intent = end.intent
            if (intent?.domain == NavigationExecutor.DOMAIN_NAVIGATION) {
                val result = execute(turnId, intent)
                when (result.status) {
                    BusinessResult.Status.APPLIED -> {
                        val feedback = result.speakText
                        if (feedback != null) speakFeedback(turnId, feedback)
                        else if (end.speakText.isNotBlank()) onReplyText(end.speakText)
                    }
                    BusinessResult.Status.FAILED ->
                        speakFeedback(turnId, result.speakText ?: EXECUTION_FAILED_PHRASE)
                    else -> Unit
                }
            } else {
                if (end.speakText.isNotBlank()) onReplyText(end.speakText)
                intent?.let { execute(turnId, it) }
            }
        }
        return Outcome.OUTPUT_REQUESTED
    }

    fun dispatchLocal(turnId: String, nlu: NluResult): Outcome {
        if (!isCurrentTurn(turnId)) return Outcome.STALE
        nlu.recognizedText?.takeIf(String::isNotBlank)?.let(onRecognized)
        handleSavedNavigation(turnId, nlu.recognizedText.orEmpty())?.let { return it }
        val result = execute(turnId, nlu.intent)
        if (nlu.intent.domain == NavigationExecutor.DOMAIN_NAVIGATION) {
            return speakFeedback(turnId, result.speakText.orEmpty())
        }
        val applied = result.speakText?.takeIf { result.status == BusinessResult.Status.APPLIED && it.isNotBlank() }
            ?: return Outcome.NO_OUTPUT
        output.speak(turnId, applied)
        return Outcome.OUTPUT_REQUESTED
    }

    private fun handleSavedNavigation(turnId: String, recognizedText: String): Outcome? {
        if (recognizedText.isBlank() || !isCurrentTurn(turnId)) return null
        return onSavedNavigationCommand(turnId, recognizedText)?.let { speakFeedback(turnId, it) }
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

    private fun speakFeedback(turnId: String, text: String): Outcome {
        if (!isCurrentTurn(turnId)) return Outcome.STALE
        if (text.isBlank()) return Outcome.NO_OUTPUT
        onReplyText(text)
        output.speak(turnId, text)
        return Outcome.OUTPUT_REQUESTED
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

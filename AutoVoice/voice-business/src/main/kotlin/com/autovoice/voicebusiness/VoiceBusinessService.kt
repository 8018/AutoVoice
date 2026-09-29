package com.autovoice.voicebusiness

import com.autovoice.business.BusinessHandler
import com.autovoice.business.BusinessResult
import com.autovoice.tts.PlaybackInterruptionReason
import com.autovoice.tts.PlaybackStage
import com.autovoice.tts.RealtimePlaybackToken
import com.autovoice.tts.TtsOutput
import com.autovoice.voicebusiness.dialog.AdmissionEvidence
import com.autovoice.voicebusiness.dialog.ConversationController
import com.autovoice.voicebusiness.dialog.DialogueSnapshot
import com.autovoice.voicebusiness.dialog.DialogueState
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.voicecore.arbiter.RaceWinner
import com.autovoice.voiceengine.api.RecognitionControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Business-side owner of dialogue, semantic adoption, response routing and playback policy.
 * ASR, NLU and arbitration may complete late; only this owner decides whether their output is
 * still usable in the active interaction. It does not own capture or candidate production.
 */
class VoiceBusinessService(
    tts: TtsOutput,
    business: BusinessHandler,
    private val scope: CoroutineScope,
    private val thinkingTimeoutMs: Long,
    /** During migration the App adapter provides the engine input gate; fake business tests omit it. */
    private val recognition: RecognitionControl? = null,
    private val onExecution: (String, Intent, BusinessResult) -> Unit = { _, _, _ -> },
    private val onExecutionFailure: (String) -> Unit = {},
    private val onRecognizedText: (String?) -> Unit = {},
    private val onReplyText: (String) -> Unit = {},
    private val onCloudPending: (Boolean) -> Unit = {},
    private val onCloudWon: (String) -> Unit = {},
    private val onDialogueState: (DialogueSnapshot) -> Unit = {},
    private val onTurnAdmitted: (String) -> Unit = {},
    private val finishOpenRounds: (String) -> Unit = {},
    private val endRound: (String, String?) -> Unit = { _, _ -> },
) {
    private val output = tts
    private var thinkingJob: Job? = null
    private var thinkingTimerTurnId: String? = null
    /** A queued capture callback after exit/expiry must not reopen recognition without a new wake. */
    @Volatile private var requiresWake = false
    /** Existing direct VoiceEngine tests can start a first capture without a preceding wake. */
    @Volatile private var legacyFirstCapture = true

    val conversation = ConversationController(
        onState = ::onConversationState,
        onTurnAdmitted = { admitted ->
            output.stop(PlaybackInterruptionReason.NEW_TURN)
            onTurnAdmitted(admitted.turnId)
        },
        onPendingVisible = onCloudPending,
    )

    private val responses = ResponseDispatcher(
        output = output,
        business = business,
        onExecution = onExecution,
        onFailure = onExecutionFailure,
        isCurrentTurn = ::isCurrentTurn,
        onRecognized = onRecognizedText,
        onReplyText = onReplyText,
    )

    fun onWake() {
        finishOpenRounds("new_interaction")
        requiresWake = false
        legacyFirstCapture = false
        recognition?.startRecognition()
        conversation.onWake()
    }

    fun onCaptureStarted(interruptPlayback: Boolean) {
        // Production paths are enabled by wake. Do not override an explicit stopRecognition just
        // because a queued capture or VAD callback arrives later.
        if (legacyFirstCapture && !requiresWake) recognition?.startRecognition()
        if (interruptPlayback) output.stop(PlaybackInterruptionReason.NEW_TURN)
    }

    fun setCloudPending(turnId: String, pending: Boolean) {
        conversation.setPending(turnId, pending)
    }

    fun onRecognized(turnId: String, text: String) {
        if (text.isBlank()) return
        if (turnId.isBlank() || conversation.isVisible(turnId)) onRecognizedText(text)
    }

    fun onAsrTurnEstablished(turnId: String, evidence: AdmissionEvidence) {
        if (turnId.isNotBlank()) conversation.confirmTurn(turnId, evidence)
    }

    fun onTurnResult(turnId: String, winner: RaceWinner) {
        when (winner) {
            is RaceWinner.Cloud -> conversation.confirmTurn(turnId, AdmissionEvidence.CLOUD_FINAL_SEMANTIC)
            is RaceWinner.Local -> conversation.confirmTurn(turnId, AdmissionEvidence.LOCAL_SEMANTIC)
        }
        if (!conversation.isCurrentTurn(turnId)) {
            endRound(turnId, null)
            return
        }
        if (!conversation.onFinalSemantic(turnId)) {
            endRound(turnId, "semantic_not_adopted")
            return
        }
        val outcome = when (winner) {
            is RaceWinner.Cloud -> {
                onCloudWon(turnId)
                responses.dispatchCloud(turnId, winner.reply)
            }
            is RaceWinner.Local -> responses.dispatchLocal(turnId, winner.nlu)
        }
        if (outcome == ResponseDispatcher.Outcome.NO_OUTPUT) conversation.onOutputSkipped(turnId)
        setCloudPending(turnId, false)
        endRound(turnId, null)
    }

    fun onPlaybackLifecycle(turnId: String, stage: PlaybackStage) {
        if (turnId.isBlank() || turnId.startsWith("chat:")) return
        when (stage) {
            PlaybackStage.STARTED -> conversation.onPlaybackStarted(turnId)
            PlaybackStage.COMPLETED, PlaybackStage.FAILED -> conversation.onPlaybackEnded(turnId)
            PlaybackStage.INTERRUPTED -> Unit
        }
    }

    fun stopPlayback() {
        val turnId = conversation.snapshot.value.turnId
        output.stop(PlaybackInterruptionReason.STOP_ONLY)
        if (turnId != null) conversation.onPlaybackEnded(turnId)
    }

    fun playRealtimeChatReply(token: RealtimePlaybackToken, reply: StreamingAudioReply) {
        responses.dispatchRealtime(token, reply)
    }

    fun exitCurrentDialogue() {
        finishOpenRounds("dialogue_exited")
        output.stop(PlaybackInterruptionReason.INTERACTION_CLOSED)
        thinkingJob?.cancel()
        thinkingJob = null
        conversation.reset()
    }

    fun onFollowUpExpired(interactionId: String, expected: DialogueSnapshot? = null) {
        conversation.onFollowUpExpired(interactionId, expected)
        if (conversation.snapshot.value.state == DialogueState.DORMANT) finishOpenRounds("listening_expired")
    }

    fun onInteractionExpired(interactionId: String) {
        if (conversation.snapshot.value.interactionId != interactionId) return
        conversation.onInteractionExpired(interactionId)
        if (conversation.snapshot.value.state == DialogueState.DORMANT) {
            finishOpenRounds("interaction_expired")
            output.stop(PlaybackInterruptionReason.INTERACTION_CLOSED)
        }
    }

    fun resetDialogue() {
        finishOpenRounds("dialogue_reset")
        conversation.reset()
    }

    fun close() {
        output.stop(PlaybackInterruptionReason.INTERACTION_CLOSED)
        thinkingJob?.cancel()
        thinkingJob = null
        conversation.reset()
    }

    private fun isCurrentTurn(turnId: String): Boolean =
        turnId.isBlank() || conversation.isCurrentTurn(turnId)

    private fun onConversationState(snapshot: DialogueSnapshot) {
        onDialogueState(snapshot)
        if (snapshot.state == DialogueState.DORMANT) {
            requiresWake = true
            recognition?.stopRecognition()
        }
        if (snapshot.state == DialogueState.PROCESSING) {
            val turnId = snapshot.turnId ?: return
            if (thinkingTimerTurnId == turnId) return
            thinkingJob?.cancel()
            thinkingTimerTurnId = turnId
            thinkingJob = scope.launch {
                delay(thinkingTimeoutMs)
                conversation.onThinkingExpired(turnId)
                if (!conversation.isCurrentTurn(turnId)) endRound(turnId, "thinking_expired")
            }
        } else {
            thinkingJob?.cancel()
            thinkingJob = null
            thinkingTimerTurnId = null
        }
    }
}

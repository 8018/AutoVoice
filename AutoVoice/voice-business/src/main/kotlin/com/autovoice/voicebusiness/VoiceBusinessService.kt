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
import com.autovoice.voiceengine.api.TextInputPort
import com.autovoice.voiceengine.api.TextSubmission
import java.util.UUID
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
    private val dialogueIdleTimeoutMs: Long = 10_000,
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
    private val onDialogueIdleTimeout: (DialogueSnapshot) -> Unit = {},
    onSavedNavigationCommand: (String, String) -> String? = { _, _ -> null },
    private val onAcceptedVoiceTranscript: (String, String) -> Unit = { _, _ -> },
) {
    private val output = tts
    private var dialogueIdleJob: Job? = null
    private var lastDialogueSnapshot = DialogueSnapshot()
    private val transcriptLock = Any()
    private var lastDeliveredVoiceTurnId: String? = null
    private var finalTranscriptBeforeAdmission: Pair<String, String>? = null
    val conversation = ConversationController(
        onState = ::onConversationState,
        onTurnAdmitted = { admitted ->
            val transcript = synchronized(transcriptLock) {
                finalTranscriptBeforeAdmission?.takeIf { it.first == admitted.turnId }
                    ?.also { finalTranscriptBeforeAdmission = null }
            }
            transcript?.let { (turnId, text) -> deliverAcceptedVoiceTranscript(turnId, text) }
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
        onSavedNavigationCommand = onSavedNavigationCommand,
    )

    fun onWake() {
        finishOpenRounds("new_interaction")
        recognition?.startRecognition()
        conversation.onWake()
    }

    /** Business admission owns the turn; the engine port only produces its cloud candidate. */
    fun submitText(rawText: String, input: TextInputPort, onAccepted: (String) -> Unit = {}): TextSubmission {
        val text = rawText.trim()
        if (text.isEmpty() || text.length > MAX_TEXT_LENGTH) return TextSubmission.BUSY
        val requestId = UUID.randomUUID().toString()
        val result = input.submitText(requestId, text)
        if (result != TextSubmission.ACCEPTED) return result
        finishOpenRounds("new_explicit_text")
        // Opens the interaction gate for later voice follow-up; it does not start microphone capture.
        recognition?.startRecognition()
        if (!conversation.beginExplicitText(requestId)) return TextSubmission.BUSY
        onAccepted(requestId)
        onRecognizedText(text)
        input.finalizeTextInput(requestId)
        return TextSubmission.ACCEPTED
    }

    fun onTextFailure(turnId: String): Boolean {
        if (!conversation.isCurrentTurn(turnId)) return false
        conversation.onThinkingExpired(turnId)
        endRound(turnId, "text_request_failed")
        return true
    }

    fun onCaptureStarted(interruptPlayback: Boolean) {
        // Capturing audio is not an interaction entry point. Manual input explicitly calls onWake
        // first; a queued capture/VAD callback must never open recognition on its own.
        if (interruptPlayback) output.stop(PlaybackInterruptionReason.NEW_TURN)
    }

    fun setCloudPending(turnId: String, pending: Boolean) {
        conversation.setPending(turnId, pending)
    }

    fun onRecognized(turnId: String, text: String, final: Boolean = false) {
        if (text.isBlank()) return
        if (turnId.isBlank() || conversation.isVisible(turnId)) onRecognizedText(text)
        if (!final || turnId.isBlank()) return
        // Some ASR providers deliver the final transcript before the turn-admission signal.
        val accepted = synchronized(transcriptLock) {
            if (conversation.isCurrentTurn(turnId)) true
            else {
                if (conversation.isVisible(turnId)) finalTranscriptBeforeAdmission = turnId to text
                false
            }
        }
        if (accepted) deliverAcceptedVoiceTranscript(turnId, text)
    }

    private fun deliverAcceptedVoiceTranscript(turnId: String, text: String) {
        synchronized(transcriptLock) {
            val snapshot = conversation.snapshot.value
            if (snapshot.turnId != turnId || lastDeliveredVoiceTurnId == turnId ||
                (snapshot.state != DialogueState.LISTENING && snapshot.state != DialogueState.PROCESSING)
            ) return
            lastDeliveredVoiceTurnId = turnId
        }
        onAcceptedVoiceTranscript(turnId, text)
    }

    fun onAsrTurnEstablished(turnId: String, evidence: AdmissionEvidence) {
        if (turnId.isNotBlank()) conversation.confirmTurn(turnId, evidence)
    }

    fun onTurnUnrecognized(turnId: String): Boolean {
        if (!conversation.isCurrentTurn(turnId)) return false
        conversation.onThinkingExpired(turnId)
        endRound(turnId, "unrecognized_command")
        return true
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
        conversation.reset()
    }

    private fun isCurrentTurn(turnId: String): Boolean =
        turnId.isBlank() || conversation.isCurrentTurn(turnId)

    private fun onConversationState(snapshot: DialogueSnapshot) {
        if (snapshot != lastDialogueSnapshot) {
            lastDialogueSnapshot = snapshot
            dialogueIdleJob?.cancel()
            dialogueIdleJob = if (snapshot.state == DialogueState.DORMANT) null else scope.launch {
                delay(dialogueIdleTimeoutMs)
                conversation.expireIfUnchanged(snapshot) { expiredCaptureId ->
                    snapshot.turnId?.let { endRound(it, "dialogue_idle_timeout") }
                    if (expiredCaptureId.isNotBlank() && expiredCaptureId != snapshot.turnId) {
                        endRound(expiredCaptureId, "dialogue_idle_timeout")
                    }
                    output.stop(PlaybackInterruptionReason.INTERACTION_CLOSED)
                    onDialogueIdleTimeout(snapshot)
                }
            }
        }
        onDialogueState(snapshot)
        if (snapshot.state == DialogueState.DORMANT) {
            synchronized(transcriptLock) {
                finalTranscriptBeforeAdmission = null
                lastDeliveredVoiceTurnId = null
            }
            recognition?.stopRecognition()
        }
    }

    private companion object { const val MAX_TEXT_LENGTH = 2_000 }
}

package com.autovoice.voicecore.dialog

import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns capture admission and the current dialogue turn. It does not inspect ASR text, arbitrate
 * semantic candidates, route audio, execute intents or control playback resources.
 */
class ConversationController(
    private val dialogue: DialogueStateMachine = DialogueStateMachine(),
    private val admission: TurnAdmissionGate = TurnAdmissionGate(),
    private val newCaptureId: () -> String = { UUID.randomUUID().toString() },
    private val onState: (DialogueSnapshot) -> Unit = {},
    private val onTurnAdmitted: (AdmittedTurn) -> Unit = {},
    private val onPendingVisible: (Boolean) -> Unit = {},
) {
    val snapshot: StateFlow<DialogueSnapshot> = dialogue.snapshot

    @Volatile
    var captureId: String = ""
        private set

    @Volatile
    private var pendingTurnId: String? = null

    @Synchronized
    fun onWake(): DialogueSnapshot {
        admission.reset()
        captureId = ""
        clearPending()
        return emit(dialogue.onWake())
    }

    /** Starts a capture identity without changing the user-visible dialogue state. */
    @Synchronized
    fun beginCapture(): String = newCaptureId().also { captureId = it }

    /** VAD/recording may open a candidate, but only ASR or final semantic evidence can admit it. */
    @Synchronized
    fun openCapture(id: String = captureId) {
        if (id.isNotBlank()) admission.open(id)
    }

    /** Direct/non-VAD inputs receive an identity and an open admission candidate. */
    @Synchronized
    fun ensureOpenCapture(): String {
        if (captureId.isBlank()) captureId = newCaptureId()
        if (!admission.owns(captureId)) admission.open(captureId)
        return captureId
    }

    @Synchronized
    fun rejectCapture(id: String = captureId): Boolean {
        val rejected = admission.reject(id)
        emit(dialogue.snapshot.value)
        return rejected
    }

    @Synchronized
    fun reset(): DialogueSnapshot {
        admission.reset()
        captureId = ""
        clearPending()
        return emit(dialogue.reset())
    }

    @Synchronized
    fun onFollowUpExpired(interactionId: String): DialogueSnapshot {
        if (dialogue.snapshot.value.interactionId == interactionId) {
            admission.reset()
            captureId = ""
            clearPending()
        }
        return emit(dialogue.onFollowUpExpired(interactionId))
    }

    /**
     * Confirms a capture using evidence produced by ASR/NLU. Repeated evidence for the current turn
     * is idempotent and never moves SPEAKING/FOLLOW_UP back to THINKING.
     */
    @Synchronized
    fun confirmTurn(turnId: String, evidence: AdmissionEvidence): Boolean {
        if (turnId.isBlank()) return false
        if (dialogue.isCurrentTurn(turnId)) return true
        val admitted = when (evidence) {
            AdmissionEvidence.LOCAL_ASR, AdmissionEvidence.CLOUD_ASR ->
                admission.confirmAsr(turnId, evidence)
            AdmissionEvidence.LOCAL_SEMANTIC, AdmissionEvidence.CLOUD_FINAL_SEMANTIC ->
                admission.confirmSemantic(turnId, evidence)
        } ?: return dialogue.isCurrentTurn(turnId)
        onTurnAdmitted(admitted)
        emit(dialogue.onSpeechCommitted(admitted.turnId))
        if (pendingTurnId != admitted.turnId) onPendingVisible(false)
        if (pendingTurnId == admitted.turnId) emit(dialogue.onSemanticProcessing(admitted.turnId))
        return true
    }

    @Synchronized
    fun setPending(turnId: String, pending: Boolean) {
        pendingTurnId = turnId.takeIf { pending }
        onPendingVisible(pending && isVisible(turnId))
        if (pending && dialogue.isCurrentTurn(turnId)) {
            emit(dialogue.onSemanticProcessing(turnId))
        }
    }

    /** Marks a final semantic only when it still belongs to the locally current turn. */
    @Synchronized
    fun onFinalSemantic(turnId: String): Boolean {
        if (!dialogue.isCurrentTurn(turnId)) return false
        emit(dialogue.onFinalSemantic(turnId))
        return true
    }

    @Synchronized
    fun onPlaybackStarted(turnId: String): DialogueSnapshot =
        emit(dialogue.onPlaybackStarted(turnId))

    @Synchronized
    fun onPlaybackEnded(turnId: String): DialogueSnapshot =
        emit(dialogue.onPlaybackEnded(turnId))

    @Synchronized
    fun isCurrentTurn(turnId: String): Boolean = dialogue.isCurrentTurn(turnId)

    @Synchronized
    fun isVisible(turnId: String): Boolean =
        turnId.isNotBlank() &&
            (admission.owns(turnId) || dialogue.snapshot.value.turnId == turnId)

    private fun clearPending() {
        pendingTurnId = null
        onPendingVisible(false)
    }

    private fun emit(value: DialogueSnapshot): DialogueSnapshot = value.also(onState)
}

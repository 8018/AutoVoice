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

    private val lock = Any()
    private val effects = ArrayDeque<() -> Unit>()
    private var drainingEffects = false

    @Volatile
    var captureId: String = ""
        private set

    @Volatile
    private var pendingTurnId: String? = null

    fun onWake(): DialogueSnapshot = mutate { queue ->
        admission.reset()
        captureId = ""
        clearPending(queue)
        dialogue.onWake().also { next -> queue.add { onState(next) } }
    }

    /** Starts a capture identity without changing the user-visible dialogue state. */
    fun beginCapture(): String = mutate { newCaptureId().also { captureId = it } }

    /** VAD/recording may open a candidate, but only ASR or final semantic evidence can admit it. */
    fun openCapture(id: String = captureId) = mutate {
        if (id.isNotBlank()) admission.open(id)
    }

    /** Direct/non-VAD inputs receive an identity and an open admission candidate. */
    fun ensureOpenCapture(): String = mutate {
        if (captureId.isBlank()) captureId = newCaptureId()
        if (!admission.owns(captureId)) admission.open(captureId)
        captureId
    }

    fun rejectCapture(id: String = captureId): Boolean = mutate { queue ->
        val rejected = admission.reject(id)
        val current = dialogue.snapshot.value
        queue.add { onState(current) }
        rejected
    }

    fun reset(): DialogueSnapshot = mutate { queue ->
        admission.reset()
        captureId = ""
        clearPending(queue)
        dialogue.reset().also { next -> queue.add { onState(next) } }
    }

    fun onFollowUpExpired(interactionId: String): DialogueSnapshot = mutate { queue ->
        if (dialogue.snapshot.value.interactionId == interactionId) {
            admission.reset()
            captureId = ""
            clearPending(queue)
        }
        dialogue.onFollowUpExpired(interactionId).also { next -> queue.add { onState(next) } }
    }

    /**
     * Confirms a capture using evidence produced by ASR/NLU. Repeated evidence for the current turn
     * is idempotent and never moves SPEAKING/FOLLOW_UP back to THINKING.
     */
    fun confirmTurn(turnId: String, evidence: AdmissionEvidence): Boolean = mutate { queue ->
        if (turnId.isBlank()) return@mutate false
        if (dialogue.isCurrentTurn(turnId)) return@mutate true
        val admitted = when (evidence) {
            AdmissionEvidence.LOCAL_ASR, AdmissionEvidence.CLOUD_ASR ->
                admission.confirmAsr(turnId, evidence)
            AdmissionEvidence.LOCAL_SEMANTIC, AdmissionEvidence.CLOUD_FINAL_SEMANTIC ->
                admission.confirmSemantic(turnId, evidence)
        } ?: return@mutate dialogue.isCurrentTurn(turnId)
        val thinking = dialogue.onSpeechCommitted(admitted.turnId)
        queue.add { onTurnAdmitted(admitted) }
        queue.add { onState(thinking) }
        if (pendingTurnId != admitted.turnId) queue.add { onPendingVisible(false) }
        if (pendingTurnId == admitted.turnId) {
            val processing = dialogue.onSemanticProcessing(admitted.turnId)
            queue.add { onState(processing) }
        }
        true
    }

    fun setPending(turnId: String, pending: Boolean) = mutate { queue ->
        pendingTurnId = turnId.takeIf { pending }
        val visible = pending && isVisibleLocked(turnId)
        queue.add { onPendingVisible(visible) }
        if (pending && dialogue.isCurrentTurn(turnId)) {
            val processing = dialogue.onSemanticProcessing(turnId)
            queue.add { onState(processing) }
        }
    }

    /** Marks a final semantic only when it still belongs to the locally current turn. */
    fun onFinalSemantic(turnId: String): Boolean = mutate { queue ->
        if (!dialogue.isCurrentTurn(turnId)) return@mutate false
        val responding = dialogue.onFinalSemantic(turnId)
        queue.add { onState(responding) }
        true
    }

    fun onPlaybackStarted(turnId: String): DialogueSnapshot = mutate { queue ->
        dialogue.onPlaybackStarted(turnId).also { next -> queue.add { onState(next) } }
    }

    fun onPlaybackEnded(turnId: String): DialogueSnapshot = mutate { queue ->
        dialogue.onPlaybackEnded(turnId).also { next -> queue.add { onState(next) } }
    }

    fun isCurrentTurn(turnId: String): Boolean = synchronized(lock) {
        dialogue.isCurrentTurn(turnId)
    }

    fun isVisible(turnId: String): Boolean = synchronized(lock) { isVisibleLocked(turnId) }

    private fun isVisibleLocked(turnId: String): Boolean =
        turnId.isNotBlank() &&
            (admission.owns(turnId) || dialogue.snapshot.value.turnId == turnId)

    private fun clearPending(queue: ArrayDeque<() -> Unit>) {
        pendingTurnId = null
        queue.add { onPendingVisible(false) }
    }

    /** State mutations are serialized; callbacks drain later in the same order without holding [lock]. */
    private fun <T> mutate(block: (ArrayDeque<() -> Unit>) -> T): T {
        var shouldDrain = false
        val result = synchronized(lock) {
            block(effects).also {
                if (!drainingEffects && effects.isNotEmpty()) {
                    drainingEffects = true
                    shouldDrain = true
                }
            }
        }
        if (shouldDrain) drainEffects()
        return result
    }

    private fun drainEffects() {
        while (true) {
            val effect = synchronized(lock) {
                if (effects.isEmpty()) {
                    drainingEffects = false
                    null
                } else {
                    effects.removeFirst()
                }
            } ?: return
            try {
                effect()
            } catch (error: Throwable) {
                synchronized(lock) { drainingEffects = false }
                throw error
            }
        }
    }
}

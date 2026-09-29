package com.autovoice.voicecore.dialog

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-visible interaction phase. ASR, NLU and arbitration never own this state. */
enum class DialogueState {
    DORMANT,
    LISTENING,
    PROCESSING,
    RESPONDING,
    SPEAKING,
}

data class DialogueSnapshot(
    val state: DialogueState = DialogueState.DORMANT,
    val interactionId: String? = null,
    /** Only an active, still-adoptable turn is current. */
    val turnId: String? = null,
    /** Identity for a listening window; old timeout callbacks must not close a newer window. */
    val listenWindowGeneration: Long = 0,
)

/**
 * Pure interaction lifecycle. VAD only opens a capture; ASR or final semantic evidence admits a
 * turn. Admission and input finalization are separate: a user may still be speaking after ASR
 * establishes the turn. Arbitration decides who wins, never whether a turn is still current.
 */
class DialogueStateMachine(
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _snapshot = MutableStateFlow(DialogueSnapshot())
    val snapshot: StateFlow<DialogueSnapshot> = _snapshot.asStateFlow()
    private var nextWindowGeneration = 0L

    @Synchronized
    fun onWake(): DialogueSnapshot = update(
        DialogueSnapshot(
            state = DialogueState.LISTENING,
            interactionId = newId(),
            listenWindowGeneration = ++nextWindowGeneration,
        ),
    )

    /** An admitted turn remains LISTENING until its entire input is finalized. */
    @Synchronized
    fun onSpeechCommitted(turnId: String, inputFinalized: Boolean = false): DialogueSnapshot {
        require(turnId.isNotBlank())
        val current = _snapshot.value
        if (current.turnId == turnId) return current
        return update(current.copy(
            state = if (inputFinalized) DialogueState.PROCESSING else DialogueState.LISTENING,
            turnId = turnId,
            interactionId = current.interactionId ?: newId(),
            listenWindowGeneration = ++nextWindowGeneration,
        ))
    }

    @Synchronized
    fun onInputFinalized(turnId: String): DialogueSnapshot = forTurn(turnId) {
        if (it.state == DialogueState.LISTENING) it.copy(state = DialogueState.PROCESSING) else it
    }

    @Synchronized
    fun onFinalSemantic(turnId: String): DialogueSnapshot = forTurn(turnId) {
        if (it.state == DialogueState.LISTENING || it.state == DialogueState.PROCESSING) {
            it.copy(state = DialogueState.RESPONDING)
        } else it
    }

    @Synchronized
    fun isCurrentTurn(turnId: String): Boolean = turnId.isNotBlank() && _snapshot.value.turnId == turnId

    @Synchronized
    fun onPlaybackStarted(turnId: String): DialogueSnapshot = forTurn(turnId) {
        if (it.state == DialogueState.RESPONDING) it.copy(state = DialogueState.SPEAKING) else it
    }

    /** Only a completed logical reply opens the next listening window. */
    @Synchronized
    fun onPlaybackEnded(turnId: String): DialogueSnapshot = forTurn(turnId) {
        if (it.state == DialogueState.SPEAKING || it.state == DialogueState.RESPONDING) {
            it.copy(state = DialogueState.LISTENING, turnId = null,
                listenWindowGeneration = ++nextWindowGeneration)
        } else it
    }

    @Synchronized
    fun onOutputSkipped(turnId: String): DialogueSnapshot = forTurn(turnId) {
        if (it.state == DialogueState.RESPONDING) {
            it.copy(state = DialogueState.LISTENING, turnId = null,
                listenWindowGeneration = ++nextWindowGeneration)
        } else it
    }

    @Synchronized
    fun onFollowUpExpired(interactionId: String): DialogueSnapshot {
        val current = _snapshot.value
        if (current.interactionId != interactionId || current.state != DialogueState.LISTENING ||
            current.turnId != null
        ) return current
        return update(DialogueSnapshot())
    }

    /** Processing expiry invalidates adoption; it never cancels the arbitration pipeline. */
    @Synchronized
    fun onThinkingExpired(turnId: String): DialogueSnapshot {
        val current = _snapshot.value
        if (current.turnId != turnId || current.state != DialogueState.PROCESSING) return current
        return update(DialogueSnapshot())
    }

    @Synchronized
    fun reset(): DialogueSnapshot = update(DialogueSnapshot())

    private fun forTurn(turnId: String, block: (DialogueSnapshot) -> DialogueSnapshot): DialogueSnapshot {
        val current = _snapshot.value
        if (turnId.isBlank() || current.turnId != turnId) return current
        return update(block(current))
    }

    private fun update(next: DialogueSnapshot): DialogueSnapshot {
        _snapshot.value = next
        return next
    }
}

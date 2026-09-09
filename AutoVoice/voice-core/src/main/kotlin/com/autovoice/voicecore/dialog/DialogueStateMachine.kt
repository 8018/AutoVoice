package com.autovoice.voicecore.dialog

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 用户可感知的对话阶段。识别、NLU、仲裁器不得直接持有或修改这些状态。 */
enum class DialogueState {
    DORMANT,
    AWAKE,
    THINKING,
    SEMANTIC_PROCESSING,
    RESPONDING,
    SPEAKING,
    FOLLOW_UP_LISTENING,
}

data class DialogueSnapshot(
    val state: DialogueState = DialogueState.DORMANT,
    val interactionId: String? = null,
    val turnId: String? = null,
)

/**
 * 只管理本地交互生命周期；不判断业务意图，也不参与端侧/云端仲裁。
 *
 * VAD 只建立临时 capture。只有 ASR 明确发出话语成立事件或有效最终语义确认后，capture 才晋升为 turn，
 * 因而环境噪声不会抢占上一轮输出。
 */
class DialogueStateMachine(
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _snapshot = MutableStateFlow(DialogueSnapshot())
    val snapshot: StateFlow<DialogueSnapshot> = _snapshot.asStateFlow()

    @Synchronized
    fun onWake(): DialogueSnapshot = update(
        DialogueSnapshot(state = DialogueState.AWAKE, interactionId = newId()),
    )

    /** Only the admission layer may submit an established turn; no VAD/capture state is held here. */
    @Synchronized
    fun onSpeechCommitted(turnId: String): DialogueSnapshot {
        require(turnId.isNotBlank())
        val current = _snapshot.value
        if (current.turnId == turnId) return current
        return update(current.copy(state = DialogueState.THINKING, turnId = turnId,
            interactionId = current.interactionId ?: newId()))
    }

    @Synchronized
    fun onSemanticProcessing(turnId: String): DialogueSnapshot = forTurn(turnId) {
        it.copy(state = DialogueState.SEMANTIC_PROCESSING)
    }

    @Synchronized
    fun onFinalSemantic(turnId: String): DialogueSnapshot = forTurn(turnId) {
        it.copy(state = DialogueState.RESPONDING)
    }

    /** 仲裁层放行后使用；只回答该语义是否仍属于本地状态机的当前轮。 */
    @Synchronized
    fun isCurrentTurn(turnId: String): Boolean = _snapshot.value.turnId == turnId

    @Synchronized
    fun onPlaybackStarted(turnId: String): DialogueSnapshot = forTurn(turnId) {
        it.copy(state = DialogueState.SPEAKING)
    }

    @Synchronized
    fun onPlaybackEnded(turnId: String): DialogueSnapshot = forTurn(turnId) {
        it.copy(state = DialogueState.FOLLOW_UP_LISTENING)
    }

    @Synchronized
    fun onFollowUpExpired(interactionId: String): DialogueSnapshot {
        val current = _snapshot.value
        if (current.interactionId != interactionId) return current
        return update(DialogueSnapshot())
    }

    @Synchronized
    fun reset(): DialogueSnapshot = update(DialogueSnapshot())

    private fun forTurn(turnId: String, block: (DialogueSnapshot) -> DialogueSnapshot): DialogueSnapshot {
        val current = _snapshot.value
        if (current.turnId != turnId) return current
        return update(block(current))
    }

    private fun update(next: DialogueSnapshot): DialogueSnapshot {
        _snapshot.value = next
        return next
    }
}

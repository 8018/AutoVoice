package com.autovoice.voicecore.dialog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DialogueStateMachineTest {
    private val machine = DialogueStateMachine()
    private val gate = TurnAdmissionGate()

    @Test
    fun `candidate creation and rejection never change any dialogue phase`() {
        for (state in DialogueState.entries) {
            val m = DialogueStateMachine()
            when (state) {
                DialogueState.DORMANT -> Unit
                DialogueState.AWAKE -> m.onWake()
                else -> {
                    m.onSpeechCommitted("old")
                    when (state) {
                        DialogueState.SEMANTIC_PROCESSING -> m.onSemanticProcessing("old")
                        DialogueState.RESPONDING -> m.onFinalSemantic("old")
                        DialogueState.SPEAKING -> m.onPlaybackStarted("old")
                        DialogueState.FOLLOW_UP_LISTENING -> m.onPlaybackEnded("old")
                        else -> Unit
                    }
                }
            }
            val before = m.snapshot.value
            assertEquals(state, before.state)
            gate.open("noise")
            assertEquals(before, m.snapshot.value)
            gate.reject("noise")
            assertEquals(before, m.snapshot.value)
        }
    }

    @Test
    fun `only admitted evidence establishes the turn`() {
        val wake = machine.onWake()
        gate.open("candidate")
        assertEquals(wake, machine.snapshot.value)
        assertNull(gate.confirmAsr("stale", AdmissionEvidence.CLOUD_ASR))
        val admitted = gate.confirmAsr("candidate", AdmissionEvidence.CLOUD_ASR)!!
        val committed = machine.onSpeechCommitted(admitted.turnId)
        assertEquals(DialogueState.THINKING, committed.state)
        assertEquals("candidate", committed.turnId)
    }

    @Test
    fun `playback ends normally while candidate remains available for semantic admission`() {
        machine.onSpeechCommitted("old")
        machine.onPlaybackStarted("old")
        gate.open("new")
        assertEquals(DialogueState.FOLLOW_UP_LISTENING, machine.onPlaybackEnded("old").state)
        val admitted = gate.confirmSemantic("new", AdmissionEvidence.LOCAL_SEMANTIC)!!
        assertEquals(DialogueState.THINKING, machine.onSpeechCommitted(admitted.turnId).state)
        machine.onPlaybackEnded("old")
        assertEquals("new", machine.snapshot.value.turnId)
        assertEquals(DialogueState.THINKING, machine.snapshot.value.state)
    }

    @Test
    fun `repeated admission and vad do not regress a speaking turn`() {
        gate.open("turn")
        gate.confirmAsr("turn", AdmissionEvidence.LOCAL_ASR)
        machine.onSpeechCommitted("turn")
        machine.onPlaybackStarted("turn")
        gate.open("turn")
        assertEquals("turn", gate.current()?.turnId)
        assertEquals(DialogueState.SPEAKING, machine.onSpeechCommitted("turn").state)
    }

    @Test
    fun `playback completion and timer expiry retain their lifecycle roles`() {
        val interaction = machine.onWake().interactionId!!
        machine.onSpeechCommitted("turn")
        machine.onPlaybackStarted("turn")
        assertEquals(DialogueState.FOLLOW_UP_LISTENING, machine.onPlaybackEnded("turn").state)
        assertEquals(DialogueState.DORMANT, machine.onFollowUpExpired(interaction).state)
    }

    @Test
    fun `state machine checks current turn downstream of arbitration`() {
        machine.onSpeechCommitted("turn")
        assertEquals(false, machine.isCurrentTurn("old"))
        assertEquals(DialogueState.THINKING, machine.onFinalSemantic("old").state)
        assertEquals(DialogueState.RESPONDING, machine.onFinalSemantic("turn").state)
        gate.open("pending")
        gate.reset()
        assertNull(gate.confirmSemantic("pending", AdmissionEvidence.CLOUD_FINAL_SEMANTIC))
    }

    @Test
    fun `thinking timeout invalidates adoption without involving arbiter`() {
        machine.onWake()
        machine.onSpeechCommitted("turn")
        assertEquals(DialogueState.DORMANT, machine.onThinkingExpired("turn").state)
        assertEquals(false, machine.isCurrentTurn("turn"))
    }
}

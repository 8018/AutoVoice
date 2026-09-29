package com.autovoice.voicebusiness.dialog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DialogueStateMachineTest {
    private val machine = DialogueStateMachine()
    private val gate = TurnAdmissionGate()

    @Test fun `candidate creation and rejection never change dialogue phase`() {
        for (state in DialogueState.entries) {
            val m = DialogueStateMachine()
            if (state != DialogueState.DORMANT) m.onWake()
            if (state == DialogueState.PROCESSING) {
                m.onSpeechCommitted("old")
                m.onInputFinalized("old")
            }
            if (state == DialogueState.RESPONDING || state == DialogueState.SPEAKING) {
                m.onSpeechCommitted("old")
                m.onFinalSemantic("old")
                if (state == DialogueState.SPEAKING) m.onPlaybackStarted("old")
            }
            val before = m.snapshot.value
            assertEquals(state, before.state)
            gate.open("noise")
            gate.reject("noise")
            assertEquals(before, m.snapshot.value)
        }
    }

    @Test fun `admission stays listening until input finalized`() {
        val wake = machine.onWake()
        gate.open("candidate")
        assertEquals(wake, machine.snapshot.value)
        assertNull(gate.confirmAsr("stale", AdmissionEvidence.CLOUD_ASR))
        val admitted = gate.confirmAsr("candidate", AdmissionEvidence.CLOUD_ASR)!!
        assertEquals(DialogueState.LISTENING, machine.onSpeechCommitted(admitted.turnId).state)
        assertEquals("candidate", machine.snapshot.value.turnId)
        assertEquals(DialogueState.PROCESSING, machine.onInputFinalized("candidate").state)
    }

    @Test fun `final semantic can skip processing and late endpoint cannot rewind`() {
        machine.onWake()
        machine.onSpeechCommitted("turn")
        assertEquals(DialogueState.RESPONDING, machine.onFinalSemantic("turn").state)
        assertEquals(DialogueState.RESPONDING, machine.onInputFinalized("turn").state)
        assertEquals(DialogueState.SPEAKING, machine.onPlaybackStarted("turn").state)
        assertEquals(DialogueState.SPEAKING, machine.onInputFinalized("turn").state)
    }

    @Test fun `reply settles and retires old turn while another candidate may be admitted`() {
        val interaction = machine.onWake().interactionId!!
        machine.onSpeechCommitted("old")
        machine.onFinalSemantic("old")
        machine.onPlaybackStarted("old")
        gate.open("new")
        assertEquals(DialogueState.LISTENING, machine.onPlaybackEnded("old").state)
        assertNull(machine.snapshot.value.turnId)
        assertFalse(machine.isCurrentTurn("old"))
        val admitted = gate.confirmSemantic("new", AdmissionEvidence.LOCAL_SEMANTIC)!!
        assertEquals(DialogueState.LISTENING, machine.onSpeechCommitted(admitted.turnId).state)
        assertEquals("new", machine.snapshot.value.turnId)
        assertEquals(DialogueState.LISTENING, machine.onPlaybackEnded("old").state)
        assertEquals("new", machine.snapshot.value.turnId)
        assertEquals(DialogueState.LISTENING, machine.snapshot.value.state)
        assertEquals(interaction, machine.snapshot.value.interactionId)
    }

    @Test fun `repeated admission does not regress speaking turn`() {
        machine.onSpeechCommitted("turn")
        machine.onFinalSemantic("turn")
        machine.onPlaybackStarted("turn")
        assertEquals(DialogueState.SPEAKING, machine.onSpeechCommitted("turn").state)
    }

    @Test fun `silent result opens follow up without a playback event`() {
        val interaction = machine.onWake().interactionId!!
        machine.onSpeechCommitted("turn")
        machine.onFinalSemantic("turn")
        assertEquals(DialogueState.LISTENING, machine.onOutputSkipped("turn").state)
        assertNull(machine.snapshot.value.turnId)
        assertEquals(DialogueState.LISTENING, machine.onOutputSkipped("turn").state)
        assertEquals(DialogueState.DORMANT, machine.onFollowUpExpired(interaction).state)
    }

    @Test fun `expiry cannot close a live turn`() {
        val interaction = machine.onWake().interactionId!!
        machine.onSpeechCommitted("turn")
        assertEquals(DialogueState.LISTENING, machine.onFollowUpExpired(interaction).state)
        machine.onInputFinalized("turn")
        assertEquals(DialogueState.DORMANT, machine.onThinkingExpired("turn").state)
        assertFalse(machine.isCurrentTurn("turn"))
    }

    @Test fun `state machine checks current turn after arbitration`() {
        machine.onSpeechCommitted("turn")
        assertFalse(machine.isCurrentTurn("old"))
        assertEquals(DialogueState.LISTENING, machine.onFinalSemantic("old").state)
        assertEquals(DialogueState.RESPONDING, machine.onFinalSemantic("turn").state)
        gate.open("pending")
        gate.reset()
        assertNull(gate.confirmSemantic("pending", AdmissionEvidence.CLOUD_FINAL_SEMANTIC))
        assertTrue(machine.isCurrentTurn("turn"))
    }
}

package com.autovoice.voicecore.dialog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DialogueStateMachineTest {
    private var id = 0
    private val machine = DialogueStateMachine { "id-${++id}" }

    @Test
    fun `vad is provisional until speech evidence commits the turn`() {
        val wake = machine.onWake()
        val candidate = machine.onVadStart("capture-1")

        assertEquals(DialogueState.AWAKE, wake.state)
        assertEquals(DialogueState.SPEECH_CANDIDATE, candidate.state)
        assertEquals(null, candidate.turnId)

        val committed = machine.onSpeechCommitted("capture-1")
        assertEquals(DialogueState.THINKING, committed.state)
        assertEquals("capture-1", committed.turnId)
    }

    @Test
    fun `stale events cannot move a newer turn`() {
        machine.onWake()
        machine.onVadStart("new")
        machine.onSpeechCommitted("new")

        machine.onPlaybackStarted("old")

        assertEquals(DialogueState.THINKING, machine.snapshot.value.state)
        assertEquals("new", machine.snapshot.value.turnId)
    }

    @Test
    fun `playback completion starts follow up and timer expiry ends interaction`() {
        val interaction = machine.onWake().interactionId!!
        machine.onVadStart("turn")
        machine.onSpeechCommitted("turn")
        machine.onFinalSemantic("turn")
        machine.onPlaybackStarted("turn")

        assertEquals(DialogueState.FOLLOW_UP_LISTENING, machine.onPlaybackEnded("turn").state)
        assertEquals(DialogueState.DORMANT, machine.onFollowUpExpired(interaction).state)
    }

    @Test
    fun `false vad restores follow up without replacing committed turn`() {
        machine.onWake()
        machine.onVadStart("turn-1")
        machine.onSpeechCommitted("turn-1")
        machine.onPlaybackEnded("turn-1")
        machine.onVadStart("noise")

        val restored = machine.onCaptureRejected("noise")
        assertEquals(DialogueState.FOLLOW_UP_LISTENING, restored.state)
        assertEquals("turn-1", restored.turnId)
    }

    @Test
    fun `false first capture keeps a finite wake-free interaction`() {
        machine.onWake()
        machine.onVadStart("noise")

        val restored = machine.onCaptureRejected("noise")

        assertEquals(DialogueState.FOLLOW_UP_LISTENING, restored.state)
        assertEquals(null, restored.turnId)
    }

    @Test
    fun `old playback completion cannot erase a newer provisional capture`() {
        machine.onWake()
        machine.onVadStart("old")
        machine.onSpeechCommitted("old")
        machine.onVadStart("new-capture")

        val snapshot = machine.onPlaybackEnded("old")

        assertEquals(DialogueState.SPEECH_CANDIDATE, snapshot.state)
        assertEquals("new-capture", snapshot.captureId)
        assertEquals(DialogueState.THINKING, machine.onSpeechCommitted("new-capture").state)
    }

    @Test
    fun `state machine only checks whether semantic belongs to current turn`() {
        machine.onWake()
        machine.onVadStart("turn")
        machine.onSpeechCommitted("turn")

        assertEquals(true, machine.isCurrentTurn("turn"))
        assertEquals(false, machine.isCurrentTurn("old"))
        assertEquals(DialogueState.THINKING, machine.onFinalSemantic("old").state)
        assertEquals(DialogueState.RESPONDING, machine.onFinalSemantic("turn").state)
    }
}

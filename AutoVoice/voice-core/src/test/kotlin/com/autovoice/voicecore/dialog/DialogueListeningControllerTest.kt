package com.autovoice.voicecore.dialog

import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DialogueListeningControllerTest {
    @Test fun `duplicate waiting and unadmitted capture cannot extend answer deadline`() = runTest {
        var state = DialogueSnapshot(DialogueState.FOLLOW_UP_LISTENING, "i", "t")
        val expired = mutableListOf<Long?>()
        val controller = DialogueListeningController(backgroundScope, { testScheduler.currentTime },
            { state }, { _, revision -> expired += revision }, defaultWindowMs = 1000)
        controller.update(state, TaskListeningDirective(1, 2000))
        advanceTimeBy(1500)
        // A VAD candidate never changes the dialogue snapshot.
        controller.update(state, TaskListeningDirective(1, 2000))
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf(1L), expired)
        controller.close()
    }

    @Test fun `admission cancels listening and replacement gets its own revision deadline`() = runTest {
        var state = DialogueSnapshot(DialogueState.FOLLOW_UP_LISTENING, "i", "t")
        val expired = mutableListOf<Long?>()
        val controller = DialogueListeningController(backgroundScope, { testScheduler.currentTime },
            { state }, { _, revision -> expired += revision })
        controller.update(state, TaskListeningDirective(1, 1000))
        advanceTimeBy(500)
        state = state.copy(state = DialogueState.THINKING)
        controller.update(state, null)
        advanceTimeBy(1000); runCurrent()
        assertTrue(expired.isEmpty())
        state = state.copy(state = DialogueState.FOLLOW_UP_LISTENING, turnId = "t2")
        controller.update(state, TaskListeningDirective(2, 2000))
        advanceTimeBy(1999); runCurrent()
        assertTrue(expired.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(2L), expired)
    }

    @Test fun `interaction cap and stale interaction are checked in DM`() = runTest {
        var state = DialogueSnapshot(DialogueState.AWAKE, "i")
        val expired = mutableListOf<String>()
        val controller = DialogueListeningController(backgroundScope, { testScheduler.currentTime },
            { state }, { expected, _ -> expired += expected.interactionId!! }, maxInteractionMs = 1000)
        controller.update(state, null)
        advanceTimeBy(900)
        state = state.copy(state = DialogueState.FOLLOW_UP_LISTENING)
        controller.update(state, null)
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("i"), expired)
        state = state.copy(interactionId = "new")
        controller.update(state, null)
        state = state.copy(interactionId = "newer")
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("i"), expired)
    }
}

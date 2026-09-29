package com.autovoice.voicecore.dialog

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DialogueListeningControllerTest {
    @Test fun `listening timer begins only after microphone is ready`() = runTest {
        val state = DialogueSnapshot(DialogueState.LISTENING, "i")
        val expired = mutableListOf<String>()
        val controller = DialogueListeningController(backgroundScope, { testScheduler.currentTime },
            { state }, { expected, _ -> expired += expected.interactionId!! }, maxInteractionMs = 20_000)
        controller.update(state, null, listeningReady = false)
        advanceTimeBy(2_000); runCurrent()
        assertTrue(expired.isEmpty())
        controller.update(state, null, listeningReady = true)
        advanceTimeBy(9_999); runCurrent()
        assertTrue(expired.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("i"), expired)
    }

    @Test fun `duplicate waiting and unadmitted capture cannot extend answer deadline`() = runTest {
        var state = DialogueSnapshot(DialogueState.LISTENING, "i")
        val expired = mutableListOf<Long?>()
        val controller = DialogueListeningController(backgroundScope, { testScheduler.currentTime },
            { state }, { _, revision -> expired += revision }, defaultWindowMs = 1000)
        controller.update(state, TaskListeningDirective(1, 2000))
        advanceTimeBy(1500)
        controller.update(state, TaskListeningDirective(1, 2000))
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf(1L), expired)
        controller.close()
    }

    @Test fun `admission cancels listening even when state enum remains listening`() = runTest {
        var state = DialogueSnapshot(DialogueState.LISTENING, "i")
        val expired = mutableListOf<Long?>()
        val controller = DialogueListeningController(backgroundScope, { testScheduler.currentTime },
            { state }, { _, revision -> expired += revision })
        controller.update(state, TaskListeningDirective(1, 1000))
        advanceTimeBy(500)
        state = state.copy(turnId = "t")
        controller.update(state, null)
        advanceTimeBy(1000); runCurrent()
        assertTrue(expired.isEmpty())
        state = state.copy(turnId = null)
        controller.update(state, TaskListeningDirective(2, 2000))
        advanceTimeBy(1999); runCurrent()
        assertTrue(expired.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(2L), expired)
    }

    @Test fun `interaction cap applies while processing and stale interaction is ignored`() = runTest {
        var state = DialogueSnapshot(DialogueState.LISTENING, "i")
        val expired = mutableListOf<String>()
        val controller = DialogueListeningController(backgroundScope, { testScheduler.currentTime },
            { state }, { _, _ -> }, maxInteractionMs = 1000,
            onInteractionExpired = expired::add)
        controller.update(state, null)
        advanceTimeBy(900)
        state = state.copy(state = DialogueState.PROCESSING, turnId = "t")
        controller.update(state, null)
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("i"), expired)
        state = state.copy(interactionId = "new", turnId = null, state = DialogueState.LISTENING)
        controller.update(state, null)
        state = state.copy(interactionId = "newer")
        controller.update(state, null)
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("i", "newer"), expired)
    }
}

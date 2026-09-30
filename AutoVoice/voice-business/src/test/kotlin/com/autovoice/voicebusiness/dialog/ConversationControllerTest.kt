package com.autovoice.voicebusiness.dialog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConversationControllerTest {
    @Test fun `explicit text publishes processing directly and stale result remains rejected`() {
        val states = mutableListOf<DialogueSnapshot>()
        val admitted = mutableListOf<AdmittedTurn>()
        val controller = ConversationController(onState = states::add, onTurnAdmitted = admitted::add)

        assertTrue(controller.beginExplicitText("text-1"))
        assertEquals(DialogueState.PROCESSING, controller.snapshot.value.state)
        assertEquals("text-1", controller.snapshot.value.turnId)
        assertEquals(listOf(DialogueState.PROCESSING), states.map { it.state })
        assertEquals(listOf(AdmittedTurn("text-1", AdmissionEvidence.EXPLICIT_TEXT)), admitted)
        assertTrue(controller.beginExplicitText("text-2"))
        assertFalse(controller.onFinalSemantic("text-1"))
        assertEquals("text-2", controller.snapshot.value.turnId)
    }

    @Test fun `late evidence from an open capture cannot wake a dormant dialogue`() {
        val admitted = mutableListOf<AdmittedTurn>()
        val controller = ConversationController(
            newCaptureId = { "late" },
            onTurnAdmitted = admitted::add,
        )
        controller.beginCapture()
        controller.openCapture("late")
        assertFalse(controller.confirmTurn("late", AdmissionEvidence.CLOUD_ASR))
        assertFalse(controller.confirmTurn("late", AdmissionEvidence.CLOUD_FINAL_SEMANTIC))
        assertEquals(DialogueSnapshot(), controller.snapshot.value)
        assertTrue(admitted.isEmpty())

        controller.onWake()
        controller.beginCapture()
        controller.openCapture("late")
        assertTrue(controller.confirmTurn("late", AdmissionEvidence.CLOUD_ASR))
    }

    @Test fun `late listening expiry cannot end a newer admitted turn in the same interaction`() {
        val controller = controller()
        controller.onWake()
        val listening = controller.snapshot.value
        val next = controller.beginCapture()
        controller.openCapture(next)
        assertTrue(controller.confirmTurn(next, AdmissionEvidence.LOCAL_ASR))
        val thinking = controller.snapshot.value
        controller.onFollowUpExpired(listening.interactionId!!, listening)
        assertEquals(thinking, controller.snapshot.value)
    }

    @Test fun `old window expiry cannot close identical looking follow up window`() {
        val controller = ConversationController(newCaptureId = { "capture" })
        val firstWindow = controller.onWake()
        val turnId = controller.beginCapture()
        controller.openCapture(turnId)
        controller.confirmTurn(turnId, AdmissionEvidence.LOCAL_SEMANTIC)
        controller.onFinalSemantic(turnId)
        val secondWindow = controller.onOutputSkipped(turnId)
        assertEquals(firstWindow.state, secondWindow.state)
        assertEquals(firstWindow.interactionId, secondWindow.interactionId)
        assertEquals(firstWindow.turnId, secondWindow.turnId)
        assertTrue(firstWindow.listenWindowGeneration != secondWindow.listenWindowGeneration)
        controller.onFollowUpExpired(firstWindow.interactionId!!, firstWindow)
        assertEquals(secondWindow, controller.snapshot.value)
    }

    @Test fun `hard interaction expiry closes processing responding and speaking`() {
        for (phase in listOf(DialogueState.PROCESSING, DialogueState.RESPONDING, DialogueState.SPEAKING)) {
            val controller = ConversationController(newCaptureId = { "turn" })
            val interactionId = controller.onWake().interactionId!!
            controller.beginCapture()
            controller.openCapture("turn")
            controller.confirmTurn("turn", AdmissionEvidence.CLOUD_ASR)
            controller.onInputFinalized("turn")
            if (phase != DialogueState.PROCESSING) controller.onFinalSemantic("turn")
            if (phase == DialogueState.SPEAKING) controller.onPlaybackStarted("turn")
            assertEquals(phase, controller.snapshot.value.state)
            controller.onInteractionExpired(interactionId)
            assertEquals(DialogueSnapshot(), controller.snapshot.value)
            assertFalse(controller.isCurrentTurn("turn"))
            assertFalse(controller.onFinalSemantic("turn"))
        }
    }

    @Test fun `capture and vad candidate do not replace current dialogue turn`() {
        val controller = controller()
        controller.onWake()
        val first = controller.beginCapture()
        controller.openCapture(first)
        assertTrue(controller.confirmTurn(first, AdmissionEvidence.LOCAL_ASR))
        controller.onFinalSemantic(first)
        controller.onPlaybackStarted(first)
        val speaking = controller.snapshot.value

        val noise = controller.beginCapture()
        controller.openCapture(noise)
        assertEquals(speaking, controller.snapshot.value)
        controller.rejectCapture(noise)
        assertEquals(speaking, controller.snapshot.value)
    }

    @Test fun `pending is progress and only input finalization enters processing`() {
        val visible = mutableListOf<Boolean>()
        val states = mutableListOf<DialogueSnapshot>()
        val admitted = mutableListOf<AdmittedTurn>()
        val controller = ConversationController(
            newCaptureId = { "capture" },
            onState = states::add,
            onTurnAdmitted = admitted::add,
            onPendingVisible = visible::add,
        )
        controller.onWake()
        controller.beginCapture()
        controller.openCapture()

        controller.setPending("capture", true)
        assertEquals(DialogueState.LISTENING, controller.snapshot.value.state)
        assertEquals(true, visible.last())

        assertTrue(controller.confirmTurn("capture", AdmissionEvidence.CLOUD_ASR))
        assertEquals(DialogueState.LISTENING, controller.snapshot.value.state)
        assertEquals(listOf(AdmittedTurn("capture", AdmissionEvidence.CLOUD_ASR)), admitted)
        assertTrue(states.any { it.state == DialogueState.LISTENING && it.turnId == "capture" })
        assertTrue(controller.onInputFinalized("capture"))
        assertEquals(DialogueState.PROCESSING, controller.snapshot.value.state)
    }

    @Test fun `stale semantic and playback cannot replace current turn`() {
        val controller = controller()
        controller.onWake()
        val current = controller.beginCapture()
        controller.openCapture()
        controller.confirmTurn(current, AdmissionEvidence.LOCAL_SEMANTIC)
        val snapshot = controller.snapshot.value

        assertFalse(controller.onFinalSemantic("old"))
        controller.onPlaybackStarted("old")
        controller.onPlaybackEnded("old")
        assertEquals(snapshot, controller.snapshot.value)
    }

    @Test fun `input endpoint before admission is retained and settled capture cannot reenter`() {
        val controller = ConversationController(newCaptureId = { "capture" })
        controller.onWake()
        val turnId = controller.beginCapture()
        controller.openCapture(turnId)
        assertTrue(controller.onInputFinalized(turnId))
        assertEquals(DialogueState.LISTENING, controller.snapshot.value.state)
        assertTrue(controller.confirmTurn(turnId, AdmissionEvidence.CLOUD_ASR))
        assertEquals(DialogueState.PROCESSING, controller.snapshot.value.state)
        assertTrue(controller.onFinalSemantic(turnId))
        controller.onOutputSkipped(turnId)
        assertEquals(DialogueState.LISTENING, controller.snapshot.value.state)
        assertEquals(null, controller.snapshot.value.turnId)
        assertFalse(controller.confirmTurn(turnId, AdmissionEvidence.CLOUD_FINAL_SEMANTIC))
        assertFalse(controller.onFinalSemantic(turnId))
        controller.openCapture(turnId)
        assertFalse(controller.confirmTurn(turnId, AdmissionEvidence.CLOUD_ASR))
    }

    @Test fun `late thinking timeout cannot clear a turn that is already responding`() {
        val pending = mutableListOf<Boolean>()
        val controller = ConversationController(
            newCaptureId = { "capture" },
            onPendingVisible = pending::add,
        )
        controller.onWake()
        val turnId = controller.beginCapture()
        controller.openCapture(turnId)
        assertTrue(controller.confirmTurn(turnId, AdmissionEvidence.CLOUD_ASR))
        assertTrue(controller.setPending(turnId, true))
        assertTrue(controller.onFinalSemantic(turnId))

        val snapshot = controller.onThinkingExpired(turnId)

        assertEquals(DialogueState.RESPONDING, snapshot.state)
        assertEquals(turnId, controller.captureId)
        assertEquals(false, pending.last())
        assertTrue(controller.isVisible(turnId))
    }

    @Test fun `pending after final semantic or playback cannot rewind a turn`() {
        val controller = ConversationController(newCaptureId = { "capture" })
        controller.onWake()
        val turnId = controller.beginCapture()
        controller.openCapture(turnId)
        assertTrue(controller.confirmTurn(turnId, AdmissionEvidence.CLOUD_ASR))
        assertTrue(controller.onFinalSemantic(turnId))
        assertFalse(controller.setPending(turnId, true))
        assertEquals(DialogueState.RESPONDING, controller.snapshot.value.state)
        controller.onPlaybackStarted(turnId)
        assertFalse(controller.setPending(turnId, true))
        assertFalse(controller.onFinalSemantic(turnId))
        assertEquals(DialogueState.SPEAKING, controller.snapshot.value.state)
    }

    @Test fun `reset clears capture pending and dialogue together`() {
        val visible = mutableListOf<Boolean>()
        val controller = ConversationController(
            newCaptureId = { "capture" },
            onPendingVisible = visible::add,
        )
        controller.onWake()
        controller.beginCapture()
        controller.openCapture()
        controller.setPending("capture", true)

        controller.reset()

        assertEquals("", controller.captureId)
        assertEquals(DialogueSnapshot(), controller.snapshot.value)
        assertEquals(false, visible.last())
        assertFalse(controller.confirmTurn("capture", AdmissionEvidence.LOCAL_ASR))
    }

    @Test fun `wake clears stale pending and blank ids are never visible`() {
        val visible = mutableListOf<Boolean>()
        val controller = ConversationController(
            newCaptureId = { "capture" },
            onPendingVisible = visible::add,
        )
        controller.onWake()
        controller.beginCapture()
        controller.openCapture()
        controller.setPending("capture", true)

        controller.onWake()

        assertEquals("", controller.captureId)
        assertEquals(false, visible.last())
        assertFalse(controller.isVisible(""))
        assertFalse(controller.isVisible("capture"))
    }

    @Test fun `stale pending cannot replace or clear current turn pending`() {
        val visible = mutableListOf<Boolean>()
        var nextId = 0
        val controller = ConversationController(
            newCaptureId = { "capture-${++nextId}" },
            onPendingVisible = visible::add,
        )
        controller.onWake()
        val old = controller.beginCapture()
        controller.openCapture(old)
        assertTrue(controller.confirmTurn(old, AdmissionEvidence.CLOUD_ASR))

        val current = controller.beginCapture()
        controller.openCapture(current)
        assertTrue(controller.confirmTurn(current, AdmissionEvidence.CLOUD_ASR))
        assertTrue(controller.setPending(current, true))

        assertFalse(controller.setPending(old, true))
        assertFalse(controller.setPending(old, false))
        assertEquals(true, visible.last())
        assertEquals(DialogueState.LISTENING, controller.snapshot.value.state)

        assertTrue(controller.setPending(current, false))
        assertEquals(false, visible.last())
    }

    @Test fun `blocked admission callback does not lock state and later effects keep order`() {
        val admittedEntered = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        val states = Collections.synchronizedList(mutableListOf<DialogueState>())
        val controller = ConversationController(
            newCaptureId = { "capture" },
            onTurnAdmitted = {
                admittedEntered.countDown()
                assertTrue(releaseAdmission.await(5, TimeUnit.SECONDS))
            },
            onState = { states += it.state },
        )
        controller.onWake()
        controller.beginCapture()
        controller.openCapture()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val admission = executor.submit<Boolean> {
                controller.confirmTurn("capture", AdmissionEvidence.LOCAL_ASR)
            }
            assertTrue(admittedEntered.await(5, TimeUnit.SECONDS))

            val playbackEnd = executor.submit<DialogueSnapshot> {
                controller.onPlaybackEnded("capture")
            }
            assertEquals(DialogueState.LISTENING, playbackEnd.get(5, TimeUnit.SECONDS).state)

            releaseAdmission.countDown()
            assertTrue(admission.get(5, TimeUnit.SECONDS))
            assertEquals(
                listOf(DialogueState.LISTENING, DialogueState.LISTENING),
                states,
            )
        } finally {
            releaseAdmission.countDown()
            executor.shutdownNow()
        }
    }

    private fun controller() = ConversationController(
        newCaptureId = sequenceOf("first", "second", "third").iterator()::next,
    )
}

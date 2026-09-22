package com.autovoice.voicecore.dialog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConversationControllerTest {
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

    @Test fun `pending candidate becomes semantic processing only after admission`() {
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
        assertEquals(DialogueState.AWAKE, controller.snapshot.value.state)
        assertEquals(true, visible.last())

        assertTrue(controller.confirmTurn("capture", AdmissionEvidence.CLOUD_ASR))
        assertEquals(DialogueState.SEMANTIC_PROCESSING, controller.snapshot.value.state)
        assertEquals(listOf(AdmittedTurn("capture", AdmissionEvidence.CLOUD_ASR)), admitted)
        assertTrue(states.any { it.state == DialogueState.THINKING })
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
        assertEquals(true, pending.last())
        assertTrue(controller.isVisible(turnId))
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
        assertEquals(DialogueState.SEMANTIC_PROCESSING, controller.snapshot.value.state)

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
            assertEquals(DialogueState.FOLLOW_UP_LISTENING, playbackEnd.get(5, TimeUnit.SECONDS).state)

            releaseAdmission.countDown()
            assertTrue(admission.get(5, TimeUnit.SECONDS))
            assertEquals(
                listOf(DialogueState.AWAKE, DialogueState.THINKING, DialogueState.FOLLOW_UP_LISTENING),
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

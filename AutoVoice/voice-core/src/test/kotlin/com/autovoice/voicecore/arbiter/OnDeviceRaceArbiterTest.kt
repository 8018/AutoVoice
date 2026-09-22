package com.autovoice.voicecore.arbiter

import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.NluResult
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.TextReply
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnDeviceRaceArbiterTest {
    private fun normalIntent() = Intent(
        schemaVersion = "1.0", domain = "vehicle", intent = "power_on",
        slots = emptyMap(), confidence = 1.0, source = "rule.nlu",
    )

    private fun windowIntent() = Intent(
        schemaVersion = "1.0", domain = "window", intent = "power_on",
        slots = emptyMap(), confidence = 1.0, source = "rule.nlu",
    )

    private fun exitIntent() = Intent(
        schemaVersion = "1.0", domain = "conversation", intent = "exit_dialogue",
        slots = emptyMap(), confidence = 1.0, source = "rule.nlu",
    )

    private fun nlu(intent: Intent, text: String? = null) = NluResult(intent, text)

    private data class Harness(
        val arbiter: OnDeviceRaceArbiter,
        val outputs: Channel<ArbitrationOutput>,
        val events: List<OnDeviceArbiterEvent>,
        val decisions: List<DecisionEntry>,
    ) : AutoCloseable {
        override fun close() = arbiter.close()
    }

    private fun harness(
        cloudWaitMs: Long = 80,
        pendingWaitMs: Long = 200,
    ): Harness {
        val outputs = Channel<ArbitrationOutput>(Channel.UNLIMITED)
        val events = CopyOnWriteArrayList<OnDeviceArbiterEvent>()
        val decisions = CopyOnWriteArrayList<DecisionEntry>()
        val arbiter = OnDeviceRaceArbiter(
            cloudWaitMs = cloudWaitMs,
            pendingWaitMs = pendingWaitMs,
            sink = DecisionSink(decisions::add),
            onEvent = events::add,
        )
        arbiter.openTurn("turn-1") { outputs.trySend(it) }
        return Harness(arbiter, outputs, events, decisions)
    }

    private suspend fun Harness.next(): ArbitrationOutput = withTimeout(1_000) { outputs.receive() }

    @Test
    fun `cloud semantic enters FIFO immediately and wins`() = runBlocking {
        harness().use { h ->
            h.arbiter.submitCloud("turn-1", TextReply("hi"))
            val winner = h.next() as ArbitrationOutput.Winner
            assertTrue(winner.value is RaceWinner.Cloud)
            assertEquals("cloud_won", h.decisions.single().reason)
            assertEquals(
                listOf(
                    OnDeviceArbiterEvent.Received("cloud"),
                    OnDeviceArbiterEvent.Won("cloud", "priority"),
                ),
                h.events,
            )
        }
    }

    @Test
    fun `observer failures do not kill arbitration or block winner delivery`() = runBlocking {
        val outputs = Channel<ArbitrationOutput>(Channel.UNLIMITED)
        val failures = CopyOnWriteArrayList<Throwable>()
        val arbiter = OnDeviceRaceArbiter(
            sink = DecisionSink { error("decision sink failed") },
            onEvent = { error("event sink failed") },
            onPipelineFailure = failures::add,
        )
        arbiter.use {
            arbiter.openTurn("turn-1") { outputs.trySend(it) }
            arbiter.submitCloud("turn-1", TextReply("first"))
            assertTrue(withTimeout(1_000) { outputs.receive() } is ArbitrationOutput.Winner)

            arbiter.openTurn("turn-2") { outputs.trySend(it) }
            arbiter.submitCloud("turn-2", TextReply("second"))
            assertTrue(withTimeout(1_000) { outputs.receive() } is ArbitrationOutput.Winner)
            assertTrue(failures.size >= 4)
        }
    }

    @Test
    fun `ordinary local stays outside FIFO until cloud window opens`() = runBlocking {
        harness(cloudWaitMs = 100).use { h ->
            h.arbiter.submitLocal("turn-1", nlu(normalIntent()))
            assertNull(withTimeoutOrNull(40) { h.outputs.receive() })
            val winner = h.next() as ArbitrationOutput.Winner
            assertTrue(winner.value is RaceWinner.Local)
            assertEquals("cloud_timeout_use_local", h.decisions.single().reason)
        }
    }

    @Test
    fun `cloud always wins when it arrives before held local is released`() = runBlocking {
        harness(cloudWaitMs = 200).use { h ->
            h.arbiter.submitLocal("turn-1", nlu(normalIntent()))
            delay(40)
            h.arbiter.submitCloud("turn-1", TextReply("cloud"))
            val winner = h.next() as ArbitrationOutput.Winner
            assertTrue(winner.value is RaceWinner.Cloud)
            assertEquals("cloud_won", h.decisions.single().reason)
        }
    }

    @Test
    fun `cloud after original deadline still wins when late local has not entered FIFO`() = runBlocking {
        harness(cloudWaitMs = 40).use { h ->
            delay(70)
            h.arbiter.submitCloud("turn-1", TextReply("late cloud"))
            delay(20)
            h.arbiter.submitLocal("turn-1", nlu(normalIntent()))
            val winner = h.next() as ArbitrationOutput.Winner
            assertTrue(winner.value is RaceWinner.Cloud)
            assertTrue(h.next() is ArbitrationOutput.AlreadyOutput)
        }
    }

    @Test
    fun `window command enters FIFO immediately`() = runBlocking {
        harness(cloudWaitMs = 500).use { h ->
            h.arbiter.submitLocal("turn-1", nlu(windowIntent(), "打开车窗"))
            val winner = h.next() as ArbitrationOutput.Winner
            val local = winner.value as RaceWinner.Local
            assertEquals("打开车窗", local.recognizedText)
            assertEquals("local_command_won", h.decisions.single().reason)
        }
    }

    @Test
    fun `local dialogue exit enters FIFO immediately`() = runBlocking {
        harness(cloudWaitMs = 10_000).use { h ->
            h.arbiter.submitLocal("turn-1", nlu(exitIntent(), "退出对话"))
            val winner = h.next() as ArbitrationOutput.Winner
            assertEquals(exitIntent(), (winner.value as RaceWinner.Local).intent)
            assertEquals("local_command_won", h.decisions.single().reason)
            assertEquals(OnDeviceArbiterEvent.Won("local", "local_command"), h.events[1])
        }
    }

    @Test
    fun `ready FIFO is first in first win`() = runBlocking {
        harness().use { h ->
            h.arbiter.submitCloud("turn-1", TextReply("cloud first"))
            h.arbiter.submitLocal("turn-1", nlu(windowIntent()))
            assertTrue((h.next() as ArbitrationOutput.Winner).value is RaceWinner.Cloud)
            assertTrue(h.next() is ArbitrationOutput.AlreadyOutput)
            assertEquals(
                listOf(
                    OnDeviceArbiterEvent.Received("cloud"),
                    OnDeviceArbiterEvent.Won("cloud", "priority"),
                    OnDeviceArbiterEvent.Received("local"),
                    OnDeviceArbiterEvent.Lost("local", "cloud_already_won"),
                ),
                h.events,
            )
        }
    }

    @Test
    fun `local window first in FIFO blocks later cloud`() = runBlocking {
        harness().use { h ->
            h.arbiter.submitLocal("turn-1", nlu(windowIntent()))
            h.arbiter.submitCloud("turn-1", TextReply("cloud later"))
            assertTrue((h.next() as ArbitrationOutput.Winner).value is RaceWinner.Local)
            assertTrue(h.next() is ArbitrationOutput.AlreadyOutput)
            assertEquals("command_already_won", (h.events.last() as OnDeviceArbiterEvent.Lost).reason)
        }
    }

    @Test
    fun `window arriving after cloud deadline uses fallback reason`() = runBlocking {
        harness(cloudWaitMs = 30).use { h ->
            delay(50)
            h.arbiter.submitLocal("turn-1", nlu(windowIntent()))
            assertTrue((h.next() as ArbitrationOutput.Winner).value is RaceWinner.Local)
            assertEquals("cloud_timeout_use_local", h.decisions.single().reason)
            assertEquals(OnDeviceArbiterEvent.Won("local", "cloud_timeout"), h.events[1])
        }
    }

    @Test
    fun `unknown local is rejected without consuming turn output`() = runBlocking {
        harness(cloudWaitMs = 30).use { h ->
            h.arbiter.submitLocal("turn-1", nlu(Intent.unknown("rule.nlu")))
            assertTrue(h.next() is ArbitrationOutput.UnknownLocal)
            assertTrue(h.decisions.isEmpty())
            h.arbiter.submitCloud("turn-1", TextReply("still valid"))
            assertTrue((h.next() as ArbitrationOutput.Winner).value is RaceWinner.Cloud)
        }
    }

    @Test
    fun `pending postpones admission of held local`() = runBlocking {
        harness(cloudWaitMs = 50, pendingWaitMs = 140).use { h ->
            h.arbiter.submitLocal("turn-1", nlu(normalIntent()))
            delay(20)
            h.arbiter.submitPending("turn-1")
            assertNull(withTimeoutOrNull(70) { h.outputs.receive() })
            val winner = h.next() as ArbitrationOutput.Winner
            assertTrue(winner.value is RaceWinner.Local)
            assertEquals(OnDeviceArbiterEvent.Pending("cloud"), h.events.first())
        }
    }

    @Test
    fun `pending never delays an immediate window command`() = runBlocking {
        harness(cloudWaitMs = 40, pendingWaitMs = 300).use { h ->
            h.arbiter.submitPending("turn-1")
            h.arbiter.submitLocal("turn-1", nlu(windowIntent()))
            assertTrue((h.next() as ArbitrationOutput.Winner).value is RaceWinner.Local)
            assertEquals("local_command_won", h.decisions.single().reason)
        }
    }

    @Test
    fun `same turn cannot emit twice`() = runBlocking {
        harness().use { h ->
            h.arbiter.submitCloud("turn-1", TextReply("first"))
            assertTrue((h.next() as ArbitrationOutput.Winner).value is RaceWinner.Cloud)
            h.arbiter.submitCloud("turn-1", TextReply("second"))
            assertTrue(h.next() is ArbitrationOutput.AlreadyOutput)
            assertEquals(1, h.decisions.size)
        }
    }

    @Test
    fun `no candidate produces no synthetic both-failed decision`() = runBlocking {
        harness(cloudWaitMs = 20).use { h ->
            assertNull(withTimeoutOrNull(100) { h.outputs.receive() })
            assertTrue(h.decisions.isEmpty())
        }
    }

    @Test
    fun `cloud unavailable releases held local immediately`() = runBlocking {
        harness(cloudWaitMs = 5_000).use { h ->
            h.arbiter.submitLocal("turn-1", nlu(normalIntent()))
            h.arbiter.submitCloudUnavailable("turn-1", "cloud_request_failed")
            assertTrue((h.next() as ArbitrationOutput.Winner).value is RaceWinner.Local)
            assertEquals("cloud_request_failed", h.decisions.single().reason)
        }
    }

    @Test
    fun `arbiter never asks which turn is current`() = runBlocking {
        val outputs = Channel<ArbitrationOutput>(Channel.UNLIMITED)
        val decisions = CopyOnWriteArrayList<DecisionEntry>()
        val arbiter = OnDeviceRaceArbiter(sink = DecisionSink(decisions::add))
        try {
            arbiter.openTurn("old-turn") { outputs.trySend(it) }
            arbiter.openTurn("new-turn") { outputs.trySend(it) }
            arbiter.submitCloud("old-turn", TextReply("old but valid to arbiter"))
            val winner = withTimeout(1_000) { outputs.receive() } as ArbitrationOutput.Winner
            assertTrue(winner.value is RaceWinner.Cloud)
            assertEquals("old-turn", decisions.single().utteranceId)
        } finally {
            arbiter.close()
        }
    }
}

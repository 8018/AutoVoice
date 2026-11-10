package com.autovoice.voiceengine.request

import com.autovoice.voicecore.CloudConfig
import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.LocalConfig
import com.autovoice.voicecore.MockConfig
import com.autovoice.voicecore.TextReply
import com.autovoice.voicecore.VadConfig
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.arbiter.OnDeviceRaceArbiter
import com.autovoice.voicecore.arbiter.RaceWinner
import com.autovoice.voicecore.session.CloudRequestFailedException
import com.autovoice.voicecore.session.CloudRunner
import com.autovoice.voicecore.session.CloudUnavailableException
import com.autovoice.voicecore.session.LocalChainRunner
import com.autovoice.voicecore.session.ResultListener
import com.autovoice.voiceengine.CloudTextNluEngine
import com.autovoice.voiceengine.api.TextSubmission
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/** Candidate production tests. Dialogue lifecycle is deliberately tested in dialog/, not here. */
class CandidateCoordinatorTest {
    @Test fun `accepted native ownership has a fixed active bound and closes every outstanding ticket`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val legacyOverflow = CompletableDeferred<Unit>()
        val c = CandidateCoordinator(cfg(false),OnDeviceRaceArbiter(cloudWaitMs = 1,sink = DecisionSink {}),
            LocalChainRunner { if (it[0] == 99.toByte()) legacyOverflow.complete(Unit) else release.await();intent() },CloudRunner { TextReply("unused") })
        try {
            repeat(64) { id -> c.beginCapture("id-$id") { true };assertTrue(c.submitTurn("id-$id",segment)) }
            val oldest = requireNotNull(c.localAdmission("id-0"))
            c.beginCapture("overflow") { true };assertTrue(c.submitTurn("overflow",byteArrayOf(99,0)));assertNull(c.localAdmission("overflow"))
            kotlinx.coroutines.withTimeout(1000) { legacyOverflow.await() }
            repeat(64) { assertTrue(requireNotNull(c.localAdmission("id-$it")).ownerLive()) }
            c.close();assertFalse(oldest.ownerLive())
            repeat(64) { assertNull(c.localAdmission("id-$it")) }
        } finally { release.complete(Unit);c.close() }
    }
    @Test fun `local fact pressure preserves one legitimate cloud request without replacing older native owners`() = runBlocking {
        val release = CompletableDeferred<Unit>();val cloudCalled = CompletableDeferred<Unit>();val rejectedNative = CompletableDeferred<Unit>()
        val accepted = AtomicInteger();val cloudCalls = AtomicInteger();val pressure = mutableListOf<String>()
        lateinit var c: CandidateCoordinator
        c = CandidateCoordinator(cfg(true),OnDeviceRaceArbiter(cloudWaitMs = 1,sink = DecisionSink {}),
            object : LocalChainRunner {
                override suspend fun run(segment: ByteArray) = error("original identity required")
                override suspend fun run(segment: ByteArray,utteranceId: String): com.autovoice.voicecore.NluResult {
                    if (utteranceId == "overflow") {
                        assertNull(c.localAdmission(utteranceId));rejectedNative.complete(Unit)
                    } else release.await()
                    return com.autovoice.voicecore.NluResult(intent())
                }
            },CloudRunner { assertTrue(it.contentEquals(segment));cloudCalls.incrementAndGet();cloudCalled.complete(Unit);TextReply("ok") },
            onLocalAdmissionPressure = pressure::add)
        try {
            repeat(64) { id -> c.beginCapture("id-$id") { true };assertTrue(c.submitTurn("id-$id",segment)) }
            c.beginCapture("overflow") { true };assertTrue(c.appendCloudSegment("overflow",segment))
            assertTrue(c.submitTurn("overflow",segment) { accepted.incrementAndGet() })
            kotlinx.coroutines.withTimeout(1000) { cloudCalled.await();rejectedNative.await() }
            assertEquals(1,accepted.get());assertEquals(1,cloudCalls.get());assertEquals(listOf("overflow"),pressure)
            assertNull(c.localAdmission("overflow"))
            repeat(64) { assertTrue(requireNotNull(c.localAdmission("id-$it")).valid(System.nanoTime()/1_000_000)) }
        } finally { c.close();release.complete(Unit) }
    }
    @Test fun `actual accepted original request owns fixed bounded lifetime and finalization independent of next input`() = runBlocking {
        var now = 100L
        val permit = AtomicBoolean(true)
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val c = CandidateCoordinator(cfg(false),OnDeviceRaceArbiter(cloudWaitMs = 1,sink = DecisionSink {}),
            LocalChainRunner { entered.complete(Unit);release.await();intent() },CloudRunner { TextReply("unused") },
            localInferenceBudgetMs = 60_000,localClockMs = { now })
        try {
            assertNull(c.localAdmission("missing"))
            c.beginCapture("old") { permit.get() }
            assertFalse(c.submitTurn("wrong",segment));assertNull(c.localAdmission("wrong"))
            assertTrue(c.submitTurn("old",segment) {
                val unfinished = requireNotNull(c.localAdmission("old"))
                assertFalse(unfinished.finalized());permit.set(false);assertFalse(unfinished.valid(now))
            })
            entered.await()
            val facts = requireNotNull(c.localAdmission("old"))
            assertTrue(facts.finalized());assertTrue(facts.valid(now));assertEquals(60_100,facts.deadlineMs)
            c.beginCapture("new") { false }
            assertTrue(facts.valid(now));now = 60_100;assertFalse(facts.valid(now))
            assertFalse(c.submitTurn("new",segment));assertNull(c.localAdmission("new"))
            assertFalse(c.submitTurn("old",segment));assertNull(c.localAdmission("new"))
            c.close();assertFalse(facts.ownerLive());assertNull(c.localAdmission("old"))
        } finally { release.complete(Unit);c.close() }
    }

    @Test fun `local ownership is removed when worker finishes and accepted id cannot be replayed`() = runBlocking {
        val done = CompletableDeferred<Unit>()
        val c = CandidateCoordinator(cfg(false),OnDeviceRaceArbiter(cloudWaitMs = 1,sink = DecisionSink {}),
            LocalChainRunner { done.complete(Unit);intent() },CloudRunner { TextReply("unused") })
        try {
            c.beginCapture("one") { true };assertTrue(c.submitTurn("one",segment));done.await()
            withTimeoutOrNull(1000) { while (c.localAdmission("one") != null) delay(1) }
            assertNull(c.localAdmission("one"))
            c.beginCapture("one") { true };assertFalse(c.submitTurn("one",segment))
        } finally { c.close() }
    }
    private val segment = ByteArray(16) { it.toByte() }

    private fun cfg(cloudEnabled: Boolean = true, cloudWaitMs: Long = 100) = DemoConfig(
        mode = "full",
        vad = VadConfig(),
        ecnr = "",
        local = LocalConfig(asr = "fake", nlu = "fake"),
        cloud = CloudConfig(enabled = cloudEnabled, gatewayUrl = "ws://fake", waitMs = cloudWaitMs),
        mock = MockConfig(),
    )

    private fun intent() = Intent(
        schemaVersion = "1.0",
        domain = "climate",
        intent = "power_on",
        slots = emptyMap(),
        confidence = 1.0,
        source = "test",
    )

    private data class Harness(
        val coordinator: CandidateCoordinator,
        val results: MutableList<Pair<String, RaceWinner>>,
        val decisions: MutableList<DecisionEntry>,
    ) : AutoCloseable {
        override fun close() = coordinator.close()
    }

    private fun harness(
        local: LocalChainRunner,
        cloud: CloudRunner,
        cloudEnabled: Boolean = true,
        cloudWaitMs: Long = 100,
        cloudText: CloudTextNluEngine? = null,
    ): Harness {
        val results = mutableListOf<Pair<String, RaceWinner>>()
        val decisions = mutableListOf<DecisionEntry>()
        return Harness(
            CandidateCoordinator(
                cfg = cfg(cloudEnabled, cloudWaitMs),
                arbiter = OnDeviceRaceArbiter(
                    cloudWaitMs = cloudWaitMs,
                    sink = DecisionSink(decisions::add),
                ),
                local = local,
                cloud = cloud,
                resultListener = ResultListener { id, winner -> results += id to winner },
                cloudText = cloudText,
            ),
            results,
            decisions,
        )
    }

    @Test
    fun `text waits for input finalization then enters cloud arbitration without audio`() = runBlocking {
        val called = AtomicInteger()
        harness(
            local = LocalChainRunner { error("text must not use local audio") },
            cloud = CloudRunner { error("text must not use cloud audio") },
            cloudText = CloudTextNluEngine { _, text ->
                called.incrementAndGet()
                TextReply("answer: $text")
            },
        ).use { h ->
            assertEquals(TextSubmission.ACCEPTED, h.coordinator.submitText("req-1", "导航到公司"))
            assertEquals(TextSubmission.DUPLICATE_REQUEST, h.coordinator.submitText("req-1", "导航到公司"))
            delay(30)
            assertEquals(0, called.get(), "model must not run before synthetic input finalization")
            assertTrue(h.coordinator.finalizeTextInput("req-1"))
            assertEquals("answer: 导航到公司", (h.awaitResult() as RaceWinner.Cloud).reply.let { (it as TextReply).text })
            assertEquals(1, called.get())
            assertEquals("cloud_won", h.decisions.single().reason)
        }
    }

    @Test
    fun `text admission is bounded and requires a configured cloud route`() {
        val held = CloudTextNluEngine { _, _ -> awaitCancellation() }
        harness(LocalChainRunner { intent() }, CloudRunner { TextReply("unused") }, cloudText = held).use { h ->
            assertEquals(TextSubmission.ACCEPTED, h.coordinator.submitText("one", "a"))
            assertEquals(TextSubmission.ACCEPTED, h.coordinator.submitText("two", "b"))
            assertEquals(TextSubmission.BUSY, h.coordinator.submitText("three", "c"))
        }
        harness(LocalChainRunner { intent() }, CloudRunner { TextReply("unused") }).use { h ->
            assertEquals(TextSubmission.NO_ROUTE, h.coordinator.submitText("one", "a"))
        }
    }

    private suspend fun Harness.awaitResult(timeoutMs: Long = 1_000): RaceWinner? {
        withTimeoutOrNull(timeoutMs) {
            while (results.isEmpty()) delay(5)
        }
        return results.firstOrNull()?.second
    }

    @Test
    fun `fast cloud candidate wins without producing dialogue state`() = runBlocking {
        harness(
            local = LocalChainRunner { delay(200); intent() },
            cloud = CloudRunner { delay(10); TextReply("cloud") },
        ).use { h ->
            h.coordinator.beginCapture("turn-1")
            assertTrue(h.coordinator.appendCloudSegment("turn-1", segment))
            assertTrue(h.coordinator.submitTurn("turn-1", segment))

            assertTrue(h.awaitResult() is RaceWinner.Cloud)
            assertFalse(h.coordinator.isCapturing("turn-1"))
            assertEquals("cloud_won", h.decisions.single().reason)
        }
    }

    @Test
    fun `ordinary local waits for cloud window then wins`() = runBlocking {
        harness(
            local = LocalChainRunner { intent() },
            cloud = CloudRunner { delay(500); TextReply("late") },
            cloudWaitMs = 30,
        ).use { h ->
            h.coordinator.beginCapture("turn-1")
            h.coordinator.appendCloudSegment("turn-1", segment)
            h.coordinator.submitTurn("turn-1", segment)

            assertTrue(h.awaitResult() is RaceWinner.Local)
            assertEquals("cloud_timeout_use_local", h.decisions.single().reason)
        }
    }

    @Test
    fun `winner does not cancel losing candidate`() = runBlocking {
        val cloudStarted = CompletableDeferred<Unit>()
        val cloudCancelled = AtomicBoolean(false)
        harness(
            local = LocalChainRunner { intent() },
            cloud = CloudRunner {
                cloudStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cloudCancelled.set(true)
                }
            },
            cloudWaitMs = 20,
        ).use { h ->
            h.coordinator.beginCapture("turn-1")
            h.coordinator.appendCloudSegment("turn-1", segment)
            h.coordinator.submitTurn("turn-1", segment)
            cloudStarted.await()

            assertTrue(h.awaitResult() is RaceWinner.Local)
            assertFalse(cloudCancelled.get())
        }
    }

    @Test
    fun `late old winner is delivered while newer capture remains owned`() = runBlocking {
        val oldCloud = CompletableDeferred<TextReply>()
        harness(
            local = LocalChainRunner { awaitCancellation() },
            cloud = CloudRunner { oldCloud.await() },
            cloudWaitMs = 2_000,
        ).use { h ->
            h.coordinator.beginCapture("old")
            h.coordinator.appendCloudSegment("old", segment)
            h.coordinator.submitTurn("old", segment)
            h.coordinator.beginCapture("new")
            oldCloud.complete(TextReply("late"))

            assertTrue(h.awaitResult() is RaceWinner.Cloud)
            assertTrue(h.coordinator.isCapturing("new"))
            assertEquals("old", h.results.single().first)
        }
    }

    @Test
    fun `cloud unavailable and missing cloud audio use distinct local reasons`() = runBlocking {
        harness(
            local = LocalChainRunner { intent() },
            cloud = CloudRunner { error("cloud must not run") },
        ).use { h ->
            h.coordinator.onCloudUnavailable()
            h.coordinator.beginCapture("offline")
            assertFalse(h.coordinator.appendCloudSegment("offline", segment))
            h.coordinator.submitTurn("offline", segment)
            assertTrue(h.awaitResult() is RaceWinner.Local)
            assertEquals("cloud_unreachable", h.decisions.single().reason)
        }

        harness(
            local = LocalChainRunner { intent() },
            cloud = CloudRunner { error("cloud must not run") },
        ).use { h ->
            h.coordinator.beginCapture("no-vad")
            h.coordinator.submitTurn("no-vad", segment)
            assertTrue(h.awaitResult() is RaceWinner.Local)
            assertEquals("no_cloud_segment", h.decisions.single().reason)
        }
    }

    @Test
    fun `unknown local candidate emits no result`() = runBlocking {
        harness(
            local = LocalChainRunner { Intent.unknown("test") },
            cloud = CloudRunner { error("cloud must not run") },
        ).use { h ->
            h.coordinator.beginCapture("turn-1")
            h.coordinator.submitTurn("turn-1", segment)
            assertEquals(null, h.awaitResult(150))
            assertTrue(h.decisions.isEmpty())
        }
    }

    @Test
    fun `capture identity rejects stale segments submissions and cancellation`() {
        harness(LocalChainRunner { intent() }, CloudRunner { TextReply("cloud") }).use { h ->
            h.coordinator.beginCapture("current")
            assertFalse(h.coordinator.appendCloudSegment("stale", segment))
            assertFalse(h.coordinator.submitTurn("stale", segment))
            assertFalse(h.coordinator.cancelCapture("stale"))
            assertTrue(h.coordinator.cancelCapture("current"))
            assertFalse(h.coordinator.isCapturing("current"))
        }
    }

    @Test
    fun `cloud segments concatenate into one provider call`() = runBlocking {
        val calls = AtomicInteger()
        val audio = AtomicReference<ByteArray>()
        harness(
            local = LocalChainRunner { delay(300); intent() },
            cloud = CloudRunner {
                calls.incrementAndGet()
                audio.set(it)
                TextReply("cloud")
            },
        ).use { h ->
            h.coordinator.beginCapture("turn-1")
            h.coordinator.appendCloudSegment("turn-1", segment)
            h.coordinator.appendCloudSegment("turn-1", segment)
            h.coordinator.submitTurn("turn-1", segment)

            assertTrue(h.awaitResult() is RaceWinner.Cloud)
            assertEquals(1, calls.get())
            assertEquals(segment.toList() + segment.toList(), audio.get().toList())
        }
    }

    @Test
    fun `transport failure opens local gate and request failure does not latch route`() = runBlocking {
        val calls = AtomicInteger()
        harness(
            local = LocalChainRunner { intent() },
            cloud = CloudRunner {
                when (calls.incrementAndGet()) {
                    1 -> throw CloudUnavailableException("down")
                    2 -> throw CloudRequestFailedException("busy")
                    else -> TextReply("recovered")
                }
            },
        ).use { h ->
            h.coordinator.beginCapture("one")
            h.coordinator.appendCloudSegment("one", segment)
            h.coordinator.submitTurn("one", segment)
            assertTrue(h.awaitResult() is RaceWinner.Local)
            assertEquals("cloud_unreachable", h.decisions.last().reason)

            h.results.clear()
            h.decisions.clear()
            h.coordinator.onCloudAvailable()
            h.coordinator.beginCapture("two")
            h.coordinator.appendCloudSegment("two", segment)
            h.coordinator.submitTurn("two", segment)
            assertTrue(h.awaitResult() is RaceWinner.Local)
            assertEquals("cloud_request_failed", h.decisions.last().reason)

            h.results.clear()
            h.decisions.clear()
            h.coordinator.beginCapture("three")
            h.coordinator.appendCloudSegment("three", segment)
            h.coordinator.submitTurn("three", segment)
            assertTrue(h.awaitResult() is RaceWinner.Cloud)
            assertEquals(3, calls.get())
        }
    }
}

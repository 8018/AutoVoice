package com.autovoice.voicecore.session

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
import org.junit.jupiter.api.Test

/** Candidate production tests. Dialogue lifecycle is deliberately tested in dialog/, not here. */
class CandidateCoordinatorTest {
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
            ),
            results,
            decisions,
        )
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

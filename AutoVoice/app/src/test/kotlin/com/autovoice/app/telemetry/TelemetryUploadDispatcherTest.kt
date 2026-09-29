package com.autovoice.app.telemetry

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelemetryUploadDispatcherTest {
    @Test
    fun `JSON queue is bounded, ordered, and drains after close`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val received = Collections.synchronizedList(mutableListOf<String>())
        val transport = object : TelemetryTransport {
            override suspend fun postRound(round: TelemetryRoundPayload): Int {
                firstStarted.complete(Unit)
                releaseFirst.await()
                received.add("round:${round.utteranceId}")
                return 200
            }

            override suspend fun postEvents(batch: TelemetryEventBatch): Int {
                received.add("events:${batch.utteranceId}")
                return 200
            }

            override suspend fun postAudio(utteranceId: String, deviceId: String?, pcm: ByteArray) = 200
        }
        try {
            val dispatcher = TelemetryUploadDispatcher(scope, transport, jsonCapacity = 1)
            assertTrue(dispatcher.postRound(round("first")))
            withTimeout(2_000) { firstStarted.await() }
            assertTrue(dispatcher.postEvents(batch("second")))
            assertFalse(dispatcher.postRound(round("overflow")))
            dispatcher.close()
            assertFalse(dispatcher.postEvents(batch("after-close")))
            releaseFirst.complete(Unit)
            withTimeout(2_000) {
                while (received.size < 2) delay(10)
            }
            assertEquals(listOf("round:first", "events:second"), received.toList())
        } finally {
            releaseFirst.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `blocked audio upload does not delay JSON`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audioStarted = CompletableDeferred<Unit>()
        val releaseAudio = CompletableDeferred<Unit>()
        val jsonSent = CompletableDeferred<Unit>()
        val transport = object : TelemetryTransport {
            override suspend fun postRound(round: TelemetryRoundPayload): Int {
                jsonSent.complete(Unit)
                return 200
            }

            override suspend fun postEvents(batch: TelemetryEventBatch) = 200

            override suspend fun postAudio(utteranceId: String, deviceId: String?, pcm: ByteArray): Int {
                audioStarted.complete(Unit)
                releaseAudio.await()
                return 200
            }
        }
        try {
            val dispatcher = TelemetryUploadDispatcher(scope, transport)
            assertTrue(dispatcher.postAudio("utt", "device", byteArrayOf(1)))
            withTimeout(2_000) { audioStarted.await() }
            assertTrue(dispatcher.postRound(round("json")))
            withTimeout(2_000) { jsonSent.await() }
            dispatcher.close()
        } finally {
            releaseAudio.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `close cancels an unresponsive upload after grace period`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val transport = object : TelemetryTransport {
            override suspend fun postRound(round: TelemetryRoundPayload): Int {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }

            override suspend fun postEvents(batch: TelemetryEventBatch) = 200
            override suspend fun postAudio(utteranceId: String, deviceId: String?, pcm: ByteArray) = 200
        }
        try {
            val dispatcher = TelemetryUploadDispatcher(scope, transport, closeGraceMs = 25)
            assertTrue(dispatcher.postRound(round("waiting")))
            withTimeout(2_000) { started.await() }
            dispatcher.close()
            withTimeout(2_000) { cancelled.await() }
        } finally {
            scope.cancel()
        }
    }

    private fun round(id: String) = TelemetryRoundPayload(id, "session", "device", "button", 1, 2, emptyList())

    private fun batch(id: String) = TelemetryEventBatch(id, emptyList())
}

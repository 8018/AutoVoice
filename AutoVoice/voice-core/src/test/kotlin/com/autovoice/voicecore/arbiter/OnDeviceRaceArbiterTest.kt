package com.autovoice.voicecore.arbiter

import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.TextReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Task 64 本地优先语义（spec §5.1 修订）：本地命令词命中即胜，未命中转云端兜底。
 */
class OnDeviceRaceArbiterTest {
    private val entries = mutableListOf<DecisionEntry>()
    private val sink = DecisionSink { entries.add(it) }

    private fun commandIntent(intent: String = "climate.power_on") =
        Intent(
            schemaVersion = "1.0",
            domain = "vehicle",
            intent = intent,
            slots = emptyMap(),
            confidence = 1.0,
            source = "test",
        )

    @Test
    fun `local command word wins immediately even if cloud already arrived`() = runBlocking {
        val arbiter = OnDeviceRaceArbiter(cloudWaitMs = 2000, sink = sink)
        val cloud = CompletableDeferred<Reply>().also { it.complete(TextReply("hi")) }
        val local = CompletableDeferred<Intent>().also { it.complete(commandIntent()) }
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Local, "本地命令词命中必须立即胜出，不受云端已到影响")
        assertEquals("local_won", entries.last().reason)
    }

    @Test
    fun `local command word wins even if cloud is faster`() = runBlocking {
        val arbiter = OnDeviceRaceArbiter(cloudWaitMs = 2000, sink = sink)
        val cloud = async { delay(50); TextReply("hi") }
        val local = async { delay(200); commandIntent() }
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Local, "本地命令词即使比云端慢也要赢（本地优先，不取消在途本地）")
        assertEquals("local_won", entries.last().reason)
    }

    @Test
    fun `local miss falls through to cloud`() = runBlocking {
        val arbiter = OnDeviceRaceArbiter(cloudWaitMs = 2000, sink = sink)
        val cloud = CompletableDeferred<Reply>().also { it.complete(TextReply("hi")) }
        val local = CompletableDeferred<Intent>().also { it.complete(Intent.unknown("t")) }
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Cloud, "本地未命中（unknown）→ 云端语义兜底")
        assertEquals("cloud_won", entries.last().reason)
    }

    @Test
    fun `local miss and cloud timeout is both failed`() = runBlocking {
        val arbiter = OnDeviceRaceArbiter(cloudWaitMs = 100, sink = sink)
        val cloud = CompletableDeferred<Reply>() // 永不完成
        val local = CompletableDeferred<Intent>().also { it.complete(Intent.unknown("t")) }
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Failed, "本地未命中 + 云端超时 → 双败（unknown 不能兜底胜出）")
        assertEquals("both_failed", entries.last().reason)
    }

    @Test
    fun `local never completes but cloud times out`() = runBlocking {
        val arbiter = OnDeviceRaceArbiter(cloudWaitMs = 100, sink = sink)
        val cloud = CompletableDeferred<Reply>()
        val local = CompletableDeferred<Intent>()
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Failed)
        assertEquals("both_failed", entries.last().reason)
    }
}

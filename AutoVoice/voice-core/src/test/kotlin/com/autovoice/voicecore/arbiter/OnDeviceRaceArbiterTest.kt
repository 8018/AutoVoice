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

class OnDeviceRaceArbiterTest {
    private val entries = mutableListOf<DecisionEntry>()
    private val sink = DecisionSink { entries.add(it) }

    /** 正常（非 unknown）本地意图：规则命中形态，可在云端超时后仲裁胜出。 */
    private fun normalIntent() = Intent(
        schemaVersion = "1.0", domain = "vehicle", intent = "power_on",
        slots = emptyMap(), confidence = 1.0, source = "rule.nlu",
    )

    @Test fun `cloud first wins`() = runBlocking {
        val arbiter = OnDeviceRaceArbiter(cloudWaitMs = 2000, sink = sink)
        val cloud = CompletableDeferred<Reply>().also { it.complete(TextReply("hi")) }
        val local = CompletableDeferred<Intent>().also { it.complete(Intent.unknown("t")) }
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Cloud)
        assertEquals("cloud_won", entries.last().reason)
    }

    @Test fun `cloud timeout falls back to local`() = runBlocking {
        val arbiter = OnDeviceRaceArbiter(cloudWaitMs = 100, sink = sink)
        val cloud = CompletableDeferred<Reply>() // 永不完成
        val local = CompletableDeferred<Intent>().also { it.complete(normalIntent()) }
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Local)
        assertEquals("cloud_timeout_use_local", entries.last().reason)
    }

    /** 拒识（语音拒识 = unknown 意图）：本地未命中语义不参与仲裁胜出——云端超时直接失败。 */
    @Test fun `unknown local intent after cloud timeout fails directly`() = runBlocking {
        val events = mutableListOf<OnDeviceArbiterEvent>()
        val arbiter = OnDeviceRaceArbiter(cloudWaitMs = 50, sink = sink, onEvent = { events.add(it) })
        val cloud = CompletableDeferred<Reply>() // 永不完成 → 云端超时
        val local = CompletableDeferred<Intent>().also { it.complete(Intent.unknown("rule.nlu")) }
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Failed, "unknown 意图不应仲裁胜出，应直接失败")
        assertEquals(
            listOf(
                OnDeviceArbiterEvent.Received("local"),
                OnDeviceArbiterEvent.Lost("local", "unknown_intent"),
            ),
            events,
        )
        assertEquals(0, entries.size, "拒识不写决策（unknown 无执行语义）")
    }

    @Test fun `cloud arrives within window even if late local`() = runBlocking {
        val arbiter = OnDeviceRaceArbiter(cloudWaitMs = 500, sink = sink)
        val cloud = async { delay(50); TextReply("hi") }
        val local = CompletableDeferred<Intent>()
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Cloud)
    }

    @Test fun `local never completes but cloud times out`() = runBlocking {
        val arbiter = OnDeviceRaceArbiter(cloudWaitMs = 100, localFallbackMs = 200, sink = sink)
        val cloud = CompletableDeferred<Reply>()
        val local = CompletableDeferred<Intent>()
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Failed)
        assertEquals("both_failed", entries.last().reason)
    }

    /** T7：决策日志的 utteranceId 取注入 provider 的真实值（装配方绑到会话 currentUtteranceId）。 */
    @Test fun `decision carries utteranceId from provider`() = runBlocking {
        val arbiter = OnDeviceRaceArbiter(
            cloudWaitMs = 50,
            localFallbackMs = 100,
            clock = { 1L },
            sink = sink,
            utteranceId = { "utt-provided" },
        )
        val cloud = CompletableDeferred<Reply>().also { it.complete(TextReply("好的")) }
        val local = CompletableDeferred<Intent>()
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Cloud)
        assertEquals("utt-provided", entries.last().utteranceId)
        assertEquals(1L, entries.last().timestampMs)
    }

    // ------------------------------------------------------------------ B2（需求 4）：仲裁过程事件

    /** 云端先到 → received(cloud) + won(cloud, priority)；本地命令词已到 → lost(local, cloud_already_won)。 */
    @Test fun `cloud win emits received won and local already-won lost events`() = runBlocking {
        val events = mutableListOf<OnDeviceArbiterEvent>()
        val arbiter = OnDeviceRaceArbiter(cloudWaitMs = 2000, sink = sink, onEvent = { events.add(it) })
        val cloud = CompletableDeferred<Reply>().also { it.complete(TextReply("hi")) }
        val local = CompletableDeferred<Intent>().also { it.complete(Intent.unknown("t")) }
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Cloud)
        assertEquals(
            listOf(
                OnDeviceArbiterEvent.Received("cloud"),
                OnDeviceArbiterEvent.Won("cloud", "priority"),
                OnDeviceArbiterEvent.Received("local"),
                OnDeviceArbiterEvent.Lost("local", "cloud_already_won"),
            ),
            events,
        )
        assertEquals("cloud_won", entries.last().reason)
    }

    /** 云端超时后本地赢 → received(local) + won(local, cloud_timeout)。 */
    @Test fun `local win after cloud timeout emits received and won`() = runBlocking {
        val events = mutableListOf<OnDeviceArbiterEvent>()
        val arbiter = OnDeviceRaceArbiter(cloudWaitMs = 50, sink = sink, onEvent = { events.add(it) })
        val cloud = CompletableDeferred<Reply>() // 永不完成
        val local = CompletableDeferred<Intent>().also { it.complete(normalIntent()) }
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Local)
        assertEquals(
            listOf(
                OnDeviceArbiterEvent.Received("local"),
                OnDeviceArbiterEvent.Won("local", "cloud_timeout"),
            ),
            events,
        )
        assertEquals("cloud_timeout_use_local", entries.last().reason)
    }

    /** 云端在超时窗口边缘迟到（本地已赢）→ lost(cloud, command_already_won)。 */
    @Test fun `late cloud semantic loses with command_already_won`() = runBlocking {
        val events = mutableListOf<OnDeviceArbiterEvent>()
        val arbiter = OnDeviceRaceArbiter(cloudWaitMs = 50, localFallbackMs = 2000, sink = sink, onEvent = { events.add(it) })
        val cloud = async { delay(100); TextReply("迟到的云端") }
        val local = async { delay(150); normalIntent() }
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Local)
        assertEquals(
            listOf(
                OnDeviceArbiterEvent.Received("local"),
                OnDeviceArbiterEvent.Won("local", "cloud_timeout"),
                OnDeviceArbiterEvent.Received("cloud"),
                OnDeviceArbiterEvent.Lost("cloud", "command_already_won"),
            ),
            events,
        )
    }

    /**
     * B2（需求 2/4）：非最新 uid 拦截——race 期间 utteranceId 刷新（新一轮 vad start），
     * 到达的云端语义属过期轮 → lost(cloud, not_latest_round) 并返回 Intercepted（丢弃），
     * 不写决策。
     */
    @Test fun `stale round cloud semantic is intercepted with not_latest_round`() = runBlocking {
        val events = mutableListOf<OnDeviceArbiterEvent>()
        var uid = "utt-1"
        val arbiter = OnDeviceRaceArbiter(
            cloudWaitMs = 2000,
            localFallbackMs = 2000,
            sink = sink,
            utteranceId = { uid },
            onEvent = { events.add(it) },
        )
        val cloud = async { delay(100); TextReply("好的") }
        val local = CompletableDeferred<Intent>()
        val race = async { arbiter.race(cloud, local) }
        delay(10) // 让 race 先快照（utt-1）
        uid = "utt-2" // 竞速中：新一轮 vad start 产生新 utteranceId
        val w = race.await()
        assertTrue(w is RaceWinner.Intercepted, "过期轮的云端语义应被拦截")
        assertEquals(
            listOf(
                OnDeviceArbiterEvent.Received("cloud"),
                OnDeviceArbiterEvent.Lost("cloud", "not_latest_round"),
            ),
            events,
        )
        assertEquals(0, entries.size, "拦截不写决策")
    }

    /** 云端超时后本地语义到达时轮已过期 → lost(local, not_latest_round) + Intercepted。 */
    @Test fun `stale round local semantic is intercepted with not_latest_round`() = runBlocking {
        val events = mutableListOf<OnDeviceArbiterEvent>()
        var uid = "utt-1"
        val arbiter = OnDeviceRaceArbiter(
            cloudWaitMs = 50,
            localFallbackMs = 2000,
            sink = sink,
            utteranceId = { uid },
            onEvent = { events.add(it) },
        )
        val cloud = CompletableDeferred<Reply>() // 永不完成 → 云端超时
        val local = async { delay(100); Intent.unknown("t") }
        val race = async { arbiter.race(cloud, local) }
        delay(10) // 云端超时已发生，等待本地中
        uid = "utt-2"
        val w = race.await()
        assertTrue(w is RaceWinner.Intercepted, "过期轮的本地语义应被拦截")
        assertEquals(
            listOf(
                OnDeviceArbiterEvent.Received("local"),
                OnDeviceArbiterEvent.Lost("local", "not_latest_round"),
            ),
            events,
        )
        assertEquals(0, entries.size, "拦截不写决策")
    }
}

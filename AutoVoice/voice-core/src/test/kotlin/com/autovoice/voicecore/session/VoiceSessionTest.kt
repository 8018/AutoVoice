package com.autovoice.voicecore.session

import com.autovoice.voicecore.CloudConfig
import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.LocalConfig
import com.autovoice.voicecore.MockConfig
import com.autovoice.voicecore.SlotValue
import com.autovoice.voicecore.TextReply
import com.autovoice.voicecore.VadConfig
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.arbiter.OnDeviceRaceArbiter
import com.autovoice.voicecore.arbiter.RaceWinner
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * VoiceSession 状态机 + 双路由编排测试（spec §7.1 / §5.1，Task 49 双路改造）。
 *
 * 全真实行为断言：fake runner 是 [LocalChainRunner]/[CloudRunner] 的接口实现，
 * 仲裁用真实 [OnDeviceRaceArbiter]（小 cloudWaitMs），时序用真实 delay 控制先后。
 * 双路喂料顺序（Task 49 契约）：先 [VoiceSession.onCloudSegment]（VAD 段，0..n 个）
 * 再 [VoiceSession.onTurnSegment]（本地整段）；倒序喂料时云端段被防御性丢弃。
 */
class VoiceSessionTest {

    private val segment = ByteArray(16) { it.toByte() }

    private fun cfg(cloudEnabled: Boolean = true, cloudWaitMs: Long = 100): DemoConfig =
        DemoConfig(
            mode = "full",
            vad = VadConfig(),
            ecnr = "",
            local = LocalConfig(asr = "fake", nlu = "fake"),
            cloud = CloudConfig(enabled = cloudEnabled, gatewayUrl = "ws://fake", waitMs = cloudWaitMs),
            mock = MockConfig(),
        )

    private fun arbiter(cloudWaitMs: Long, localFallbackMs: Long, sink: DecisionSink) =
        OnDeviceRaceArbiter(cloudWaitMs = cloudWaitMs, localFallbackMs = localFallbackMs, sink = sink)

    private fun localIntent(): Intent =
        Intent(
            schemaVersion = "1.0",
            domain = "climate",
            intent = "set_temperature",
            slots = mapOf("temperature" to SlotValue.Number(24.0)),
            confidence = 0.98,
            source = "fake.local",
        )

    private data class Turn(
        val session: VoiceSession,
        val states: List<SessionState>,
        val results: List<RaceWinner>,
        val entries: List<DecisionEntry>,
    )

    /**
     * 跑一轮完整话语：onListeningStart → onCloudSegment(×cloudSegments) → onTurnSegment
     * → 竞速收敛 → 结果回调 → IDLE。scope 注入为 runBlocking 自身，runBlocking 等本轮
     * 子协程全部完成后才返回，故返回时结果已回调、状态已回 IDLE，断言可顺序读全。
     */
    private fun turn(
        local: LocalChainRunner,
        cloud: CloudRunner,
        cloudEnabled: Boolean = true,
        cloudWaitMs: Long = 100,
        localFallbackMs: Long = 10_000,
        cloudSegments: Int = 1,
        beforeFeed: (VoiceSession) -> Unit = {},
    ): Turn = runBlocking {
        val entries = mutableListOf<DecisionEntry>()
        val states = mutableListOf<SessionState>()
        val results = mutableListOf<RaceWinner>()
        val session = VoiceSession(
            cfg = cfg(cloudEnabled, cloudWaitMs),
            arbiter = arbiter(cloudWaitMs, localFallbackMs, DecisionSink { entries.add(it) }),
            sink = DecisionSink { entries.add(it) },
            local = local,
            cloud = cloud,
            scope = this,
            resultListener = ResultListener { results.add(it) },
        )
        session.onState { states.add(it) }
        beforeFeed(session)
        session.onListeningStart()
        repeat(cloudSegments) { session.onCloudSegment(segment) }
        session.onTurnSegment(segment)
        Turn(session, states, results, entries)
    }

    @Test
    fun `cloud reachable and fast → Cloud winner, SPEAKING, cloud_won entry`() {
        val reply = TextReply("已为您把空调调到 24 度")
        val t = turn(
            local = LocalChainRunner { delay(200); localIntent() },
            cloud = CloudRunner { delay(10); reply },
        )
        assertEquals(
            listOf(
                SessionState.IDLE, SessionState.LISTENING, SessionState.UNDERSTANDING,
                SessionState.SPEAKING, SessionState.IDLE,
            ),
            t.states,
        )
        assertTrue(t.results.single() is RaceWinner.Cloud)
        assertEquals(reply, (t.results.single() as RaceWinner.Cloud).reply)
        assertEquals(listOf("cloud_won"), t.entries.map { it.reason })
        assertEquals(SessionState.IDLE, t.session.state.value)
    }

    @Test
    fun `cloud reachable but slow → Local winner, EXECUTING, cloud_timeout_use_local entry`() {
        val t = turn(
            local = LocalChainRunner { localIntent() },
            cloud = CloudRunner { delay(500); TextReply("慢") },
        )
        assertEquals(
            listOf(
                SessionState.IDLE, SessionState.LISTENING, SessionState.UNDERSTANDING,
                SessionState.EXECUTING, SessionState.IDLE,
            ),
            t.states,
        )
        assertTrue(t.results.single() is RaceWinner.Local)
        assertEquals(localIntent(), (t.results.single() as RaceWinner.Local).intent)
        assertEquals(listOf("cloud_timeout_use_local"), t.entries.map { it.reason })
        assertEquals(SessionState.IDLE, t.session.state.value)
    }

    @Test
    fun `onCloudUnavailable → local only with cloud_unreachable entry`() {
        val t = turn(
            local = LocalChainRunner { localIntent() },
            cloud = CloudRunner { error("云端链不应被启动") },
            beforeFeed = { it.onCloudUnavailable() },
        )
        assertEquals(
            listOf(
                SessionState.IDLE, SessionState.LISTENING, SessionState.UNDERSTANDING,
                SessionState.EXECUTING, SessionState.IDLE,
            ),
            t.states,
        )
        assertTrue(t.results.single() is RaceWinner.Local)
        assertEquals(listOf("cloud_unreachable"), t.entries.map { it.reason })
        assertEquals(SessionState.IDLE, t.session.state.value)
    }

    @Test
    fun `cloud disabled in config → local only with cloud_unreachable entry`() {
        val t = turn(
            local = LocalChainRunner { localIntent() },
            cloud = CloudRunner { error("云端链不应被启动") },
            cloudEnabled = false,
        )
        assertEquals(
            listOf(
                SessionState.IDLE, SessionState.LISTENING, SessionState.UNDERSTANDING,
                SessionState.EXECUTING, SessionState.IDLE,
            ),
            t.states,
        )
        assertTrue(t.results.single() is RaceWinner.Local)
        assertEquals(listOf("cloud_unreachable"), t.entries.map { it.reason })
        assertEquals(SessionState.IDLE, t.session.state.value)
    }

    /**
     * Task 49 新路径：VAD 未切出任何云端段（无语音/全被最小段阈值过滤）——
     * 跳过竞速，本地整段直接赢，决策 reason = `no_cloud_segment`（区别于链路故障）。
     */
    @Test
    fun `no cloud segment fed → local wins with no_cloud_segment entry`() {
        val t = turn(
            local = LocalChainRunner { localIntent() },
            cloud = CloudRunner { error("云端链不应被启动") },
            cloudSegments = 0,
        )
        assertTrue(t.results.single() is RaceWinner.Local)
        assertEquals(listOf("no_cloud_segment"), t.entries.map { it.reason })
        assertEquals(
            listOf(
                SessionState.IDLE, SessionState.LISTENING, SessionState.UNDERSTANDING,
                SessionState.EXECUTING, SessionState.IDLE,
            ),
            t.states,
        )
        assertEquals(SessionState.IDLE, t.session.state.value)
    }

    @Test
    fun `both routes fail → Failed result, straight back to IDLE`() {
        val t = turn(
            local = LocalChainRunner { awaitCancellation() },
            cloud = CloudRunner { awaitCancellation() },
            cloudWaitMs = 100,
            localFallbackMs = 150,
        )
        assertEquals(
            listOf(
                SessionState.IDLE, SessionState.LISTENING, SessionState.UNDERSTANDING,
                SessionState.IDLE,
            ),
            t.states,
        )
        assertTrue(t.results.single() is RaceWinner.Failed)
        assertEquals(listOf("both_failed"), t.entries.map { it.reason })
        assertEquals(SessionState.IDLE, t.session.state.value)
    }

    /**
     * T7：本地单链决策日志携带本轮真实 utteranceId（VoiceEngine.onListeningStart 写入
     * currentUtteranceId，数据平台据此把端侧决策与云端事件按 utteranceId 汇合）。
     */
    @Test
    fun `local-only decision carries currentUtteranceId`() {
        val t = turn(
            local = LocalChainRunner { localIntent() },
            cloud = CloudRunner { error("云端链不应被启动") },
            cloudSegments = 0,
            beforeFeed = { it.currentUtteranceId = "utt-123" },
        )
        assertTrue(t.results.single() is RaceWinner.Local)
        assertEquals("utt-123", t.entries.single().utteranceId)
    }

    @Test
    fun `onTurnSegment outside LISTENING is ignored`() = runBlocking {
        val entries = mutableListOf<DecisionEntry>()
        val states = mutableListOf<SessionState>()
        val results = mutableListOf<RaceWinner>()
        val session = VoiceSession(
            cfg = cfg(),
            arbiter = arbiter(100, 10_000, DecisionSink { entries.add(it) }),
            sink = DecisionSink { entries.add(it) },
            local = LocalChainRunner { localIntent() },
            cloud = CloudRunner { TextReply("hi") },
            scope = this,
        )
        session.onState { states.add(it) }
        session.onTurnSegment(segment) // IDLE 下调用 → 忽略，不抛
        assertEquals(listOf(SessionState.IDLE), states)
        assertTrue(results.isEmpty())
        assertTrue(entries.isEmpty())
        assertEquals(SessionState.IDLE, session.state.value)
    }

    /**
     * Task 49 顺序契约防御：onTurnSegment 之后（UNDERSTANDING）再喂云端段被丢弃——
     * 云端链不被启动，结果走本地。
     */
    @Test
    fun `cloud segment fed after onTurnSegment is dropped`() = runBlocking {
        val entries = mutableListOf<DecisionEntry>()
        val states = mutableListOf<SessionState>()
        val results = mutableListOf<RaceWinner>()
        val cloudCalls = AtomicInteger(0)
        val signal = CompletableDeferred<Unit>()
        val session = VoiceSession(
            cfg = cfg(),
            arbiter = arbiter(100, 10_000, DecisionSink { entries.add(it) }),
            sink = DecisionSink { entries.add(it) },
            local = LocalChainRunner { localIntent() },
            cloud = CloudRunner { cloudCalls.incrementAndGet(); TextReply("hi") },
            scope = this,
            resultListener = ResultListener { results.add(it); signal.complete(Unit) },
        )
        session.onState { states.add(it) }
        session.onListeningStart()
        session.onTurnSegment(segment)
        session.onCloudSegment(segment) // 倒序喂料：UNDERSTANDING 下忽略
        assertEquals(0, cloudCalls.get(), "云端链不应被启动")
        signal.await() // 等本轮编排收敛（runTurn 协程已入队，尚未执行）
        assertTrue(results.single() is RaceWinner.Local)
        assertEquals(listOf("no_cloud_segment"), entries.map { it.reason })
        assertEquals(SessionState.IDLE, session.state.value)
    }

    /**
     * Task 49 双路：多个云端段严格串行（链式前驱等待），一轮只收敛一个结果。
     * 云端慢 → 本地赢；慢云端段在轮次结束后被取消回收（runBlocking 不挂死）。
     */
    @Test
    fun `multiple cloud segments → one result per turn with serial upload`() {
        val t = turn(
            local = LocalChainRunner { delay(20); localIntent() },
            cloud = CloudRunner { delay(500); TextReply("晚") },
            cloudSegments = 2,
        )
        assertEquals(1, t.results.size)
        assertTrue(t.results.single() is RaceWinner.Local)
        assertEquals(
            listOf(
                SessionState.IDLE, SessionState.LISTENING, SessionState.UNDERSTANDING,
                SessionState.EXECUTING, SessionState.IDLE,
            ),
            t.states,
        )
    }

    /**
     * Task 49 新路径 + Task 15 M1 语义：云端段上传中途抛 CloudUnavailableException →
     * latch 云端不可达 + 转本地兜底（cloud_unreachable 决策），结果本地赢；
     * 下一轮不再启动云端链。
     */
    @Test
    fun `cloud chain exception during upload → local fallback with latch`() = runBlocking {
        val entries = mutableListOf<DecisionEntry>()
        val results = mutableListOf<RaceWinner>()
        val cloudCalls = AtomicInteger(0)
        // var + 每轮重赋值：listener 捕获变量槽，每轮 complete 的都是"当前"那个 deferred
        var signal = CompletableDeferred<Unit>()
        val session = VoiceSession(
            cfg = cfg(),
            arbiter = arbiter(100, 10_000, DecisionSink { entries.add(it) }),
            sink = DecisionSink { entries.add(it) },
            local = LocalChainRunner { localIntent() },
            cloud = CloudRunner {
                cloudCalls.incrementAndGet()
                throw CloudUnavailableException("gateway down")
            },
            scope = this,
            resultListener = ResultListener { results.add(it); signal.complete(Unit) },
        )

        // 第一轮：上传即炸 → 本地兜底
        session.onListeningStart()
        session.onCloudSegment(segment)
        session.onTurnSegment(segment)
        signal.await()
        assertTrue(results.single() is RaceWinner.Local)
        assertEquals(listOf("cloud_unreachable"), entries.map { it.reason })
        assertEquals(SessionState.IDLE, session.state.value)

        // 第二轮：latch 生效，云端链不再启动
        signal = CompletableDeferred()
        session.onCloudAvailable() // 网络恢复（Task 20）：重新启用
        session.onListeningStart()
        session.onCloudSegment(segment)
        session.onTurnSegment(segment)
        signal.await()
        assertEquals(2, cloudCalls.get(), "恢复后云端链应重新启动")
        assertTrue(results.last() is RaceWinner.Local)
        assertEquals(SessionState.IDLE, session.state.value)
    }

    @Test
    fun `onListeningStop returns LISTENING to IDLE`() = runBlocking {
        val states = mutableListOf<SessionState>()
        val session = VoiceSession(
            cfg = cfg(),
            arbiter = arbiter(100, 10_000, DecisionSink {}),
            sink = DecisionSink {},
            local = LocalChainRunner { localIntent() },
            cloud = CloudRunner { TextReply("hi") },
            scope = this,
        )
        session.onState { states.add(it) }
        session.onListeningStart()
        session.onListeningStop()
        assertEquals(
            listOf(SessionState.IDLE, SessionState.LISTENING, SessionState.IDLE),
            states,
        )
        assertEquals(SessionState.IDLE, session.state.value)
    }

    /**
     * Task 14 M1 加固：链内异常（非 CloudUnavailableException）仍要回 IDLE——
     * 状态机绝不冻结在 UNDERSTANDING。异常不吞，按协程语义从 runBlocking 重抛，
     * 测试 catch 后断言状态与阶段序列。
     */
    @Test
    fun `exception in runTurn still returns to IDLE`() {
        val states = mutableListOf<SessionState>()
        val results = mutableListOf<RaceWinner>()
        val sessionRef = AtomicReference<VoiceSession>()
        val thrown = try {
            runBlocking {
                val session = VoiceSession(
                    cfg = cfg(cloudEnabled = true, cloudWaitMs = 100),
                    arbiter = arbiter(100, 10_000, DecisionSink {}),
                    sink = DecisionSink {},
                    local = LocalChainRunner { error("local chain boom") },
                    cloud = CloudRunner { delay(500); TextReply("hi") },
                    scope = this,
                    resultListener = ResultListener { results.add(it) },
                )
                sessionRef.set(session)
                session.onState { states.add(it) }
                session.onListeningStart()
                session.onCloudSegment(segment)
                session.onTurnSegment(segment)
                null
            }
        } catch (t: Throwable) {
            t
        }
        assertNotNull(thrown, "链内异常应按协程语义传播")
        assertEquals("local chain boom", thrown?.message)
        val session = sessionRef.get()!!
        assertEquals(SessionState.IDLE, session.state.value, "异常后必须回 IDLE，不冻结")
        assertEquals(
            listOf(
                SessionState.IDLE, SessionState.LISTENING, SessionState.UNDERSTANDING,
                SessionState.IDLE,
            ),
            states,
        )
        assertTrue(results.isEmpty(), "异常路径不回调结果")
    }

    /**
     * 网络恢复（Task 20）：onCloudUnavailable 后调用 onCloudAvailable()，
     * 云端路由重新启用——下一轮话语回到竞速（cloud_won）。
     */
    @Test
    fun `onCloudAvailable re-enables cloud route after unavailable`() = runBlocking {
        val entries = mutableListOf<DecisionEntry>()
        val results = mutableListOf<RaceWinner>()
        var signal = CompletableDeferred<Unit>()
        val session = VoiceSession(
            cfg = cfg(),
            arbiter = arbiter(100, 10_000, DecisionSink { entries.add(it) }),
            sink = DecisionSink { entries.add(it) },
            local = LocalChainRunner { localIntent() },
            cloud = CloudRunner { delay(10); TextReply("hi") },
            scope = this,
            resultListener = ResultListener { results.add(it); signal.complete(Unit) },
        )

        // 第一轮：云端不可达 → 只跑本地
        session.onCloudUnavailable()
        session.onListeningStart()
        session.onCloudSegment(segment)
        session.onTurnSegment(segment)
        signal.await()
        assertTrue(results.single() is RaceWinner.Local)
        assertEquals(listOf("cloud_unreachable"), entries.map { it.reason })

        // 网络恢复：onCloudAvailable 重新启用云端路由 → 云端赢
        results.clear()
        entries.clear()
        signal = CompletableDeferred()
        session.onCloudAvailable()
        session.onListeningStart()
        session.onCloudSegment(segment)
        session.onTurnSegment(segment)
        signal.await()
        assertTrue(results.single() is RaceWinner.Cloud)
        assertEquals(listOf("cloud_won"), entries.map { it.reason })
        assertEquals(SessionState.IDLE, session.state.value)
    }
}

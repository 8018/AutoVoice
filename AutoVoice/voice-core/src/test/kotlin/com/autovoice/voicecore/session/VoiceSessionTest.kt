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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * VoiceSession 状态机 + 双路由编排测试（spec §7.1 / §5.1，Task 49 双路改造）。
 *
 * 全真实行为断言：fake runner 是 [LocalChainRunner]/[CloudRunner] 的接口实现，
 * 仲裁用真实 [OnDeviceRaceArbiter]（小 cloudWaitMs），时序用真实 delay 控制先后。
 * 双路喂料顺序（Task 49 契约）：先 [VoiceSession.onCloudSegment]（VAD 段，0..n 个）
 * 再 [VoiceSession.onTurnSegment]（本地整段）；多个 VAD 段会按序拼接为一次云端调用，
 * 倒序喂料时云端段被防御性丢弃。
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

    private fun arbiter(cloudWaitMs: Long, sink: DecisionSink) =
        OnDeviceRaceArbiter(cloudWaitMs = cloudWaitMs, sink = sink)

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
     * → 竞速收敛 → 结果回调 → IDLE。编排 scope 注入 runBlocking；候选与之解耦，
     * 因此输家自然完成不会拖住本轮结果。
     */
    private fun turn(
        local: LocalChainRunner,
        cloud: CloudRunner,
        cloudEnabled: Boolean = true,
        cloudWaitMs: Long = 100,
        cloudSegments: Int = 1,
        resultWaitMs: Long = 1_000,
        understandingTimeoutMs: Long = 30_000,
        beforeFeed: (VoiceSession) -> Unit = {},
    ): Turn = runBlocking {
        val entries = mutableListOf<DecisionEntry>()
        val states = mutableListOf<SessionState>()
        val results = mutableListOf<RaceWinner>()
        val session = VoiceSession(
            cfg = cfg(cloudEnabled, cloudWaitMs),
            arbiter = arbiter(cloudWaitMs, DecisionSink { entries.add(it) }),
            local = local,
            cloud = cloud,
            scope = this,
            resultListener = ResultListener { _, winner -> results.add(winner) },
            understandingTimeoutMs = understandingTimeoutMs,
        )
        session.onState { states.add(it) }
        beforeFeed(session)
        session.onListeningStart()
        repeat(cloudSegments) { session.onCloudSegment(segment) }
        session.onTurnSegment(segment)
        withTimeoutOrNull(resultWaitMs) {
            while (results.isEmpty()) delay(5)
        }
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
    fun `local winner does not cancel cloud candidate`() = runBlocking {
        val cloudStarted = CompletableDeferred<Unit>()
        val cloudCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val result = CompletableDeferred<RaceWinner>()
        val session = VoiceSession(
            cfg = cfg(cloudWaitMs = 20),
            arbiter = arbiter(20, DecisionSink {}),
            local = LocalChainRunner { localIntent() },
            cloud = CloudRunner {
                cloudStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cloudCancelled.set(true)
                }
            },
            scope = this,
            resultListener = ResultListener { _, winner -> result.complete(winner) },
        )
        session.currentUtteranceId = "utt-1"
        session.onListeningStart()
        session.onCloudSegment(segment)
        session.onTurnSegment(segment)
        cloudStarted.await()
        assertTrue(result.await() is RaceWinner.Local)
        assertFalse(cloudCancelled.get(), "仲裁收敛不得取消输家")
        session.close()
    }

    @Test
    fun `superseded old turn cannot reset newer listening state`() = runBlocking {
        val oldCloud = CompletableDeferred<TextReply>()
        val result = CompletableDeferred<RaceWinner>()
        val session = VoiceSession(
            cfg = cfg(cloudWaitMs = 2_000),
            arbiter = arbiter(2_000, DecisionSink {}),
            local = LocalChainRunner { awaitCancellation() },
            cloud = CloudRunner { oldCloud.await() },
            scope = this,
            resultListener = ResultListener { _, winner -> result.complete(winner) },
        )
        session.onListeningStart("utt-old")
        session.onCloudSegment(segment)
        session.onTurnSegment(segment)

        // 新 capture 尚未得到 ASR/NLU 准入，也必须先取得采集资源所有权。
        session.onListeningStart("capture-new")
        oldCloud.complete(TextReply("迟到回复"))

        // 编排层不再把“是否当前轮”塞进仲裁；原始赢家下发给 DialogueStateMachine 判断。
        assertTrue(result.await() is RaceWinner.Cloud)
        assertEquals(SessionState.LISTENING, session.state.value)
        assertEquals("capture-new", session.currentUtteranceId)
        session.close()
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
    fun `cloud unavailable and local unknown does not report a local winner`() {
        val t = turn(
            local = LocalChainRunner { Intent.unknown("fake.local") },
            cloud = CloudRunner { error("云端链不应被启动") },
            resultWaitMs = 120,
            understandingTimeoutMs = 60,
            beforeFeed = { it.onCloudUnavailable() },
        )
        assertTrue(t.results.isEmpty())
        assertTrue(t.entries.isEmpty())
        assertEquals(
            listOf(
                SessionState.IDLE, SessionState.LISTENING, SessionState.UNDERSTANDING,
                SessionState.IDLE,
            ),
            t.states,
        )
    }

    @Test
    fun `no cloud segment and local unknown does not report a local winner`() {
        val t = turn(
            local = LocalChainRunner { Intent.unknown("fake.local") },
            cloud = CloudRunner { error("云端链不应被启动") },
            cloudSegments = 0,
            resultWaitMs = 120,
            understandingTimeoutMs = 60,
        )
        assertTrue(t.results.isEmpty())
        assertTrue(t.entries.isEmpty())
    }

    @Test
    fun `both routes silent produce no arbiter result and state timer returns to IDLE`() {
        val t = turn(
            local = LocalChainRunner { awaitCancellation() },
            cloud = CloudRunner { awaitCancellation() },
            cloudWaitMs = 100,
            resultWaitMs = 160,
            understandingTimeoutMs = 80,
        )
        assertEquals(
            listOf(
                SessionState.IDLE, SessionState.LISTENING, SessionState.UNDERSTANDING,
                SessionState.IDLE,
            ),
            t.states,
        )
        assertTrue(t.results.isEmpty())
        assertTrue(t.entries.isEmpty())
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
            arbiter = arbiter(100, DecisionSink { entries.add(it) }),
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
            arbiter = arbiter(100, DecisionSink { entries.add(it) }),
            local = LocalChainRunner { localIntent() },
            cloud = CloudRunner { cloudCalls.incrementAndGet(); TextReply("hi") },
            scope = this,
            resultListener = ResultListener { _, winner -> results.add(winner); signal.complete(Unit) },
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

    /** 多个 VAD 片段必须拼成一个完整输入，只调用一次云端，避免 S2S 流式回复期间 BUSY。 */
    @Test
    fun `multiple cloud segments are concatenated into one cloud call`() {
        val cloudCalls = AtomicInteger(0)
        val cloudAudio = AtomicReference<ByteArray>()
        val t = turn(
            local = LocalChainRunner { delay(20); localIntent() },
            cloud = CloudRunner {
                cloudCalls.incrementAndGet()
                cloudAudio.set(it)
                delay(500)
                TextReply("晚")
            },
            cloudSegments = 2,
        )
        assertEquals(1, cloudCalls.get())
        assertEquals(segment.toList() + segment.toList(), cloudAudio.get().toList())
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
            arbiter = arbiter(100, DecisionSink { entries.add(it) }),
            local = LocalChainRunner { localIntent() },
            cloud = CloudRunner {
                cloudCalls.incrementAndGet()
                throw CloudUnavailableException("gateway down")
            },
            scope = this,
            resultListener = ResultListener { _, winner -> results.add(winner); signal.complete(Unit) },
        )

        // 第一轮：上传即炸 → 本地兜底
        session.onListeningStart("utt-cloud-failure-1")
        session.onCloudSegment(segment)
        session.onTurnSegment(segment)
        signal.await()
        assertTrue(results.single() is RaceWinner.Local)
        assertEquals(listOf("cloud_unreachable"), entries.map { it.reason })
        assertEquals(SessionState.IDLE, session.state.value)

        // 第二轮：latch 生效，云端链不再启动
        signal = CompletableDeferred()
        session.onCloudAvailable() // 网络恢复（Task 20）：重新启用
        session.onListeningStart("utt-cloud-failure-2")
        session.onCloudSegment(segment)
        session.onTurnSegment(segment)
        signal.await()
        assertEquals(2, cloudCalls.get(), "恢复后云端链应重新启动")
        assertTrue(results.last() is RaceWinner.Local)
        assertEquals(SessionState.IDLE, session.state.value)
        session.close()
    }

    @Test
    fun `cloud request error falls back without latching connection unavailable`() = runBlocking {
        val entries = mutableListOf<DecisionEntry>()
        val results = mutableListOf<RaceWinner>()
        val cloudCalls = AtomicInteger(0)
        var signal = CompletableDeferred<Unit>()
        val session = VoiceSession(
            cfg = cfg(),
            arbiter = arbiter(100, DecisionSink { entries.add(it) }),
            local = LocalChainRunner { localIntent() },
            cloud = CloudRunner {
                if (cloudCalls.incrementAndGet() == 1) throw CloudRequestFailedException("BUSY")
                TextReply("recovered")
            },
            scope = this,
            resultListener = ResultListener { _, winner -> results.add(winner); signal.complete(Unit) },
        )

        session.onListeningStart("utt-request-failure-1")
        session.onCloudSegment(segment)
        session.onTurnSegment(segment)
        signal.await()
        assertTrue(results.single() is RaceWinner.Local)
        assertEquals("cloud_request_failed", entries.single().reason)

        results.clear()
        entries.clear()
        signal = CompletableDeferred()
        session.onListeningStart("utt-request-failure-2")
        session.onCloudSegment(segment)
        session.onTurnSegment(segment)
        signal.await()
        assertEquals(2, cloudCalls.get(), "业务错误不能阻止下一轮继续使用现有云端连接")
        assertTrue(results.single() is RaceWinner.Cloud)
        session.close()
    }

    @Test
    fun `onListeningStop returns LISTENING to IDLE`() = runBlocking {
        val states = mutableListOf<SessionState>()
        val session = VoiceSession(
            cfg = cfg(),
            arbiter = arbiter(100, DecisionSink {}),
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
        session.close()
    }

    /** 候选生产者与会话调用栈解耦；异常不击穿调用方，思考定时器负责回 IDLE。 */
    @Test
    fun `candidate exception does not block session timer returning to IDLE`() {
        val states = mutableListOf<SessionState>()
        val results = mutableListOf<RaceWinner>()
        val sessionRef = AtomicReference<VoiceSession>()
        runBlocking {
            val session = VoiceSession(
                cfg = cfg(cloudEnabled = true, cloudWaitMs = 100),
                arbiter = arbiter(100, DecisionSink {}),
                local = LocalChainRunner { error("local chain boom") },
                cloud = CloudRunner { delay(500); TextReply("hi") },
                scope = this,
                resultListener = ResultListener { _, winner -> results.add(winner) },
                understandingTimeoutMs = 60,
            )
            sessionRef.set(session)
            session.onState { states.add(it) }
            session.onListeningStart()
            session.onCloudSegment(segment)
            session.onTurnSegment(segment)
            delay(100)
        }
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
        session.close()
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
            arbiter = arbiter(100, DecisionSink { entries.add(it) }),
            local = LocalChainRunner { localIntent() },
            cloud = CloudRunner { delay(10); TextReply("hi") },
            scope = this,
            resultListener = ResultListener { _, winner -> results.add(winner); signal.complete(Unit) },
        )

        // 第一轮：云端不可达 → 只跑本地
        session.onCloudUnavailable()
        session.onListeningStart("utt-recovery-1")
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
        session.onListeningStart("utt-recovery-2")
        session.onCloudSegment(segment)
        session.onTurnSegment(segment)
        signal.await()
        assertTrue(results.single() is RaceWinner.Cloud)
        assertEquals(listOf("cloud_won"), entries.map { it.reason })
        assertEquals(SessionState.IDLE, session.state.value)
        session.close()
    }
}

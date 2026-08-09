package com.autovoice.app

import com.autovoice.voicecore.AudioReply
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
import com.autovoice.voicecore.session.CloudRunner
import com.autovoice.voicecore.session.CloudUnavailableException
import com.autovoice.voicecore.session.LocalChainRunner
import com.autovoice.voicecore.session.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Task 20 引擎测试：真实 VoiceSession + 真实 OnDeviceRaceArbiter（小 cloudWaitMs），
 * 注入 fake 本地链 / 云端链 / 播放 / 播报 / 车辆 / 网络检查。纯 JVM，无 Android 类型。
 *
 * 时序：engine 的会话 scope 注入 runBlocking，runBlocking 等本轮子协程全部完成才返回，
 * 故返回时竞速已收敛、结果已路由，断言可顺序读全。
 */
class VoiceEngineTest {

    private val segment = ByteArray(960) { 7 }

    private fun cfg(cloudWaitMs: Long = 100): DemoConfig =
        DemoConfig(
            mode = "full",
            vad = VadConfig(),
            ecnr = "rnnoise",
            local = LocalConfig(asr = "iflytek.fake-cmd", nlu = "rule.nlu"),
            cloud = CloudConfig(enabled = true, gatewayUrl = "ws://fake", waitMs = cloudWaitMs),
            mock = MockConfig(),
        )

    private fun powerOnIntent(): Intent =
        Intent(
            schemaVersion = "1.0",
            domain = "climate",
            intent = "power_on",
            slots = emptyMap(),
            confidence = 1.0,
            source = "test.local",
        )

    private fun setTempIntent(temperature: Double): Intent =
        Intent(
            schemaVersion = "1.0",
            domain = "climate",
            intent = "set_temperature",
            slots = mapOf("temperature" to SlotValue.Number(temperature)),
            confidence = 0.98,
            source = "test.local",
        )

    /**
     * 测试装配：真实 VoiceSession + 真实仲裁器，注入 fake 链与出口。
     * JVM 单测里 BuildConfig.DEBUG 恒为 false，弱网延迟 hook 需显式传 debugBuild=true 才生效。
     */
    private fun engine(
        scope: CoroutineScope,
        local: LocalChainRunner,
        cloud: CloudRunner,
        networkAvailable: () -> Boolean = { true },
        cloudWaitMs: Long = 100,
        localFallbackMs: Long = 1000,
        sink: DecisionSink = DecisionSink {},
        player: AudioPlayer = AudioPlayer {},
        speaker: TextSpeaker = TextSpeaker {},
        /** A3 默认 TTS 失败（返回 null）→ speaker 兜底；用例可注入 fake 返回音频。 */
        tts: TtsRequester = TtsRequester { null },
        debugBuild: Boolean = true,
    ): Pair<VoiceEngine, MockVehicleState> {
        val vehicle = MockVehicleState()
        val engine = VoiceEngine(
            cfg = cfg(cloudWaitMs),
            arbiter = OnDeviceRaceArbiter(
                cloudWaitMs = cloudWaitMs,
                localFallbackMs = localFallbackMs,
                clock = System::currentTimeMillis,
                sink = sink,
            ),
            sink = sink,
            networkAvailable = networkAvailable,
            local = local,
            cloud = cloud,
            tts = tts,
            player = player,
            speaker = speaker,
            vehicle = vehicle,
            scope = scope,
            debugBuild = debugBuild,
        )
        return engine to vehicle
    }

    /**
     * 一轮话语（Task 50 双路）：onListeningStart → onCloudSegment（云端路段，0..n 个）
     * → onTurnSegment（本地整段，启动竞速）。在 runBlocking 内调用。
     */
    private fun utter(engine: VoiceEngine, cloudSegments: Int = 1) {
        engine.onListeningStart()
        repeat(cloudSegments) { engine.onCloudSegment(segment) }
        engine.onTurnSegment(segment)
    }

    // ------------------------------------------------------------------ 用例

    @Test
    fun `cloud wins fast → player got AudioReply, vehicle applied intent, cloud_won logged`() {
        val entries = mutableListOf<DecisionEntry>()
        val played = mutableListOf<AudioReply>()
        val audio =
            AudioReply(
                mime = "audio/wav",
                data = ByteArray(8) { it.toByte() },
                speakText = "已为您打开空调",
                intent = powerOnIntent(),
            )
        lateinit var engine: VoiceEngine
        lateinit var vehicle: MockVehicleState
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { delay(300); powerOnIntent() },
                cloud = CloudRunner { delay(10); audio },
                sink = DecisionSink { entries.add(it) },
                player = AudioPlayer { played.add(it) },
            )
            engine = pair.first
            vehicle = pair.second
            utter(engine)
        }
        // 播放出口收到 AudioReply 数据
        assertEquals(1, played.size)
        assertArrayEquals(audio.data, played[0].data)
        assertEquals(audio.speakText, played[0].speakText)
        // 车辆执行了音频回复携带的 intent
        assertTrue(vehicle.isAcOn)
        assertEquals("cloud_won", entries.single().reason)
    }

    @Test
    fun `network unavailable → local only, cloud_unreachable, apply text spoken, cloud never ran`() {
        val entries = mutableListOf<DecisionEntry>()
        val spoken = mutableListOf<String>()
        var cloudRan = false
        lateinit var engine: VoiceEngine
        lateinit var vehicle: MockVehicleState
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { powerOnIntent() },
                cloud = CloudRunner {
                    cloudRan = true
                    error("云端链不应被启动")
                },
                networkAvailable = { false },
                sink = DecisionSink { entries.add(it) },
                speaker = TextSpeaker { spoken.add(it) },
            )
            engine = pair.first
            vehicle = pair.second
            utter(engine)
        }
        assertFalse(cloudRan, "无网络时云端链不得启动")
        assertTrue(vehicle.isAcOn, "本地意图应执行")
        assertEquals(listOf("已为您打开空调"), spoken, "播报本地 apply 的文本")
        assertEquals(listOf("cloud_unreachable"), entries.map { it.reason })
    }

    @Test
    fun `weakNetwork on → cloud delayed past cloudWaitMs → local wins with cloud_timeout_use_local`() {
        val entries = mutableListOf<DecisionEntry>()
        val played = mutableListOf<AudioReply>()
        lateinit var engine: VoiceEngine
        lateinit var vehicle: MockVehicleState
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { powerOnIntent() },
                cloud = CloudRunner { delay(5); TextReply("快") },
                sink = DecisionSink { entries.add(it) },
                player = AudioPlayer { played.add(it) },
            )
            engine = pair.first
            vehicle = pair.second
            engine.weakNetwork = true
            utter(engine)
        }
        assertEquals(listOf("cloud_timeout_use_local"), entries.map { it.reason })
        assertTrue(vehicle.isAcOn, "本地兜底意图应执行")
        assertTrue(played.isEmpty(), "云端迟到，音频不应播放")
    }

    @Test
    fun `both routes fail → Failed → fallback phrase spoken`() {
        val entries = mutableListOf<DecisionEntry>()
        val spoken = mutableListOf<String>()
        lateinit var engine: VoiceEngine
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { awaitCancellation() },
                cloud = CloudRunner { awaitCancellation() },
                localFallbackMs = 150,
                sink = DecisionSink { entries.add(it) },
                speaker = TextSpeaker { spoken.add(it) },
            )
            engine = pair.first
            utter(engine)
        }
        assertEquals(listOf("both_failed"), entries.map { it.reason })
        assertEquals(listOf("网络开小差了，请稍后再试"), spoken)
    }

    @Test
    fun `cloud text reply routes to speaker`() {
        val spoken = mutableListOf<String>()
        lateinit var engine: VoiceEngine
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { powerOnIntent() },
                cloud = CloudRunner { TextReply("已为您把空调调到 24 度") },
                speaker = TextSpeaker { spoken.add(it) },
            )
            engine = pair.first
            utter(engine)
        }
        assertEquals(listOf("已为您把空调调到 24 度"), spoken)
    }

    @Test
    fun `cloud action reply applies intent and speaks its speakText`() {
        val spoken = mutableListOf<String>()
        lateinit var engine: VoiceEngine
        lateinit var vehicle: MockVehicleState
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { powerOnIntent() },
                cloud = CloudRunner {
                    com.autovoice.voicecore.ActionReply(
                        intent = setTempIntent(24.0),
                        speakText = "已为您把空调调到24度",
                    )
                },
                speaker = TextSpeaker { spoken.add(it) },
            )
            engine = pair.first
            vehicle = pair.second
            utter(engine)
        }
        assertEquals(24.0, vehicle.acTemperature, "ActionReply 的 intent 应执行")
        // A3 TTS 解耦：默认 tts 失败（null）→ 系统 TTS 兜底播报 speakText
        assertEquals(listOf("已为您把空调调到24度"), spoken, "TTS 失败时兜底播报 ActionReply 的 speakText")
    }

    @Test
    fun `cloud action reply requests tts audio and plays it instead of speaking`() {
        val requested = mutableListOf<String>()
        val played = mutableListOf<AudioReply>()
        val spoken = mutableListOf<String>()
        val ttsAudio =
            AudioReply(mime = "audio/wav", data = ByteArray(6) { it.toByte() }, speakText = "已为您把空调调到24度")
        lateinit var engine: VoiceEngine
        lateinit var vehicle: MockVehicleState
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { powerOnIntent() },
                cloud = CloudRunner {
                    com.autovoice.voicecore.ActionReply(
                        intent = setTempIntent(24.0),
                        speakText = "已为您把空调调到24度",
                    )
                },
                tts = TtsRequester {
                    requested.add(it)
                    ttsAudio
                },
                player = AudioPlayer { played.add(it) },
                speaker = TextSpeaker { spoken.add(it) },
            )
            engine = pair.first
            vehicle = pair.second
            utter(engine)
        }
        assertEquals(24.0, vehicle.acTemperature, "ActionReply 的 intent 应执行")
        assertEquals(listOf("已为您把空调调到24度"), requested, "TTS 请求文本 = ActionReply 的 speakText")
        assertEquals(1, played.size, "TTS 返回音频 → 播放")
        assertArrayEquals(ttsAudio.data, played[0].data)
        assertTrue(spoken.isEmpty(), "TTS 成功时不得走系统播报兜底")
    }

    @Test
    fun `cloud text reply falls back to speaker when tts times out`() {
        val spoken = mutableListOf<String>()
        var ttsCalled = false
        lateinit var engine: VoiceEngine
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { powerOnIntent() },
                cloud = CloudRunner { TextReply("已为您把空调调到 24 度") },
                tts = TtsRequester {
                    ttsCalled = true
                    null // 模拟超时/合成失败
                },
                speaker = TextSpeaker { spoken.add(it) },
            )
            engine = pair.first
            utter(engine)
        }
        assertTrue(ttsCalled, "TextReply 应先请求 TTS")
        assertEquals(listOf("已为您把空调调到 24 度"), spoken, "TTS 失败 → 系统 TTS 兜底播报文本")
    }

    /**
     * 云端链 ready 后故障（Task 15 M1）：CloudRunner 抛 CloudUnavailableException 且 latch 不可达
     * → 本轮转本地兜底（cloud_unreachable）。故障按【轮次】重试而非跨轮 latch：下一轮
     * onListeningStart 网络可用即重新启用云端路由（onCloudAvailable），云端链再次启动并再次失败
     * → 两轮都记录 cloud_unreachable、云端链共被调用 2 次。
     */
    @Test
    fun `mid-turn cloud failure falls back to local per turn and retries cloud next utterance`() {
        val entries = mutableListOf<DecisionEntry>()
        var cloudCalls = 0
        lateinit var engine: VoiceEngine
        lateinit var vehicle: MockVehicleState
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { powerOnIntent() },
                cloud = CloudRunner {
                    cloudCalls++
                    // 与生产 GatewayCloudRunner 相同语义：ready 后故障先 latch 再抛
                    engine.session.onCloudUnavailable()
                    throw CloudUnavailableException("网关中途断开")
                },
                sink = DecisionSink { entries.add(it) },
            )
            engine = pair.first
            vehicle = pair.second

            // 第一句：云端故障 → 本轮转本地兜底
            utter(engine)
            awaitIdle(engine)

            // 第二句：重新尝试云端路由（不继承上一轮的 latch），故障再次回落本地
            utter(engine)
            awaitIdle(engine)
        }
        assertEquals(2, cloudCalls, "第二句应重新尝试云端路由（按轮次重试，非跨轮 latch）")
        assertEquals(listOf("cloud_unreachable", "cloud_unreachable"), entries.map { it.reason })
        assertTrue(vehicle.isAcOn, "两轮都应由本地链执行")
    }

    @Test
    fun `weakNetwork hook is inert in release build`() {
        // release 构建（debugBuild=false）：weakNetwork=true 也不人为延迟，云端照常先赢
        val entries = mutableListOf<DecisionEntry>()
        val played = mutableListOf<AudioReply>()
        val audio =
            AudioReply(
                mime = "audio/wav",
                data = ByteArray(4),
                speakText = "已为您打开空调",
                intent = powerOnIntent(),
            )
        lateinit var engine: VoiceEngine
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { delay(300); powerOnIntent() },
                cloud = CloudRunner { delay(5); audio },
                debugBuild = false,
                sink = DecisionSink { entries.add(it) },
                player = AudioPlayer { played.add(it) },
            )
            engine = pair.first
            engine.weakNetwork = true
            utter(engine)
        }
        assertEquals(listOf("cloud_won"), entries.map { it.reason }, "release 下 weakNetwork 不应改变竞速")
        assertEquals(1, played.size, "云端音频应正常播放")
    }

    /** 等一轮话语收敛完毕（状态回到 IDLE）——多轮用例在 runBlocking 内串行推进。 */
    private suspend fun awaitIdle(engine: VoiceEngine) {
        while (engine.session.state.value != SessionState.IDLE) {
            delay(10)
        }
    }

    // ------------------------------------------------------------------ Task 21：close()

    /**
     * Task 21 模式切换释放语义：close() 取消引擎协程作用域（会话竞速/网关桥接收集全部
     * 终止），在途竞速被取消后状态回 IDLE、不发结果回调。引擎用独立 scope 装配（生产由
     * MainViewModel 每次重建时新建专属 scope），close() 不殃及外部作用域。
     */
    @Test
    fun `close cancels engine scope and returns in-flight turn to IDLE without result`() {
        val spoken = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cloudStarted = CompletableDeferred<Unit>()
        val engine = VoiceEngine(
            cfg = cfg(cloudWaitMs = 10_000),
            arbiter = OnDeviceRaceArbiter(
                cloudWaitMs = 10_000,
                localFallbackMs = 10_000,
                clock = System::currentTimeMillis,
                sink = DecisionSink {},
            ),
            sink = DecisionSink {},
            networkAvailable = { true },
            local = LocalChainRunner { awaitCancellation() },
            cloud = CloudRunner {
                cloudStarted.complete(Unit)
                awaitCancellation()
            },
            player = AudioPlayer {},
            tts = TtsRequester { null },
            speaker = TextSpeaker { spoken.add(it) },
            vehicle = MockVehicleState(),
            scope = scope,
        )
        runBlocking {
            engine.onListeningStart()
            engine.onCloudSegment(segment)
            engine.onTurnSegment(segment)
            withTimeout(2_000) { cloudStarted.await() } // 确保竞速已启动后才 close
        }
        assertEquals(SessionState.UNDERSTANDING, engine.session.state.value)

        engine.close()

        assertFalse(scope.coroutineContext.isActive, "close 应取消引擎协程作用域")
        runBlocking {
            withTimeout(2_000) { engine.session.state.first { it == SessionState.IDLE } }
        }
        assertTrue(spoken.isEmpty(), "取消的竞速不得产生播报")
    }
}

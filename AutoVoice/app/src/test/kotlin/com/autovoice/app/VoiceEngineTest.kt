package com.autovoice.app

import com.autovoice.app.audio.TtsCache
import com.autovoice.app.telemetry.TelemetryClient
import com.autovoice.app.telemetry.TelemetryStages
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.AudioStreamEnd
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.voicecore.CloudConfig
import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.LocalConfig
import com.autovoice.voicecore.MockConfig
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.SlotValue
import com.autovoice.voicecore.TextReply
import com.autovoice.voicecore.VadConfig
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.arbiter.OnDeviceArbiterEvent
import com.autovoice.voicecore.arbiter.OnDeviceRaceArbiter
import com.autovoice.voicecore.session.CloudRunner
import com.autovoice.voicecore.session.CloudUnavailableException
import com.autovoice.voicecore.session.LocalChainRunner
import com.autovoice.voicecore.session.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

    /** 导航意图（spec §4.2：navigation/navigate {poiname, lat, lon}；waypointsJson 可选，多目的地）。 */
    private fun navigateIntent(
        poiname: String,
        lat: Double,
        lon: Double,
        waypointsJson: String? = null,
    ): Intent {
        val slots = mutableMapOf(
            NavigationExecutor.SLOT_POINAME to SlotValue.StringValue(poiname),
            NavigationExecutor.SLOT_LAT to SlotValue.Number(lat),
            NavigationExecutor.SLOT_LON to SlotValue.Number(lon),
        )
        if (waypointsJson != null) {
            slots[NavigationExecutor.SLOT_WAYPOINTS] = SlotValue.StringValue(waypointsJson)
        }
        return Intent(
            schemaVersion = "1.0",
            domain = NavigationExecutor.DOMAIN_NAVIGATION,
            intent = NavigationExecutor.INTENT_NAVIGATE,
            slots = slots,
            confidence = 0.98,
            source = "test.cloud",
        )
    }

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
        /** 默认 enabled=false 全 no-op 实例（T6），用例可注入 enabled=true + MockWebServer。 */
        telemetry: TelemetryClient = TelemetryClient(
            okHttp = OkHttpClient(),
            baseUrl = "",
            deviceId = null,
            scope = scope,
            enabled = false,
        ),
        player: AudioPlayer = AudioPlayer {},
        /** 2026-08-15：统一网络 TTS（不用系统 TTS）。默认 TTS 失败（返回 null）→ 静默记
         *  失败事件；用例可注入 fake 返回音频断言播放。 */
        tts: TtsRequester = TtsRequester { null },
        /** 架构变更（缓存移回端侧）：默认空缓存，用例可注入预置/可查验实例。 */
        ttsCache: TtsCache = TtsCache(null),
        /** 导航执行器（spec §4.2）：默认未装配（导航意图记 skipped），用例注入 fake opener。 */
        navigation: NavigationExecutor? = null,
        debugBuild: Boolean = true,
        /** B5：云端 pending 占位回调（生产 create() 装配 UI 状态；默认 no-op）。 */
        onCloudPending: (Boolean) -> Unit = {},
        onRecognized: (String?) -> Unit = {},
        /** B5：pending 信号通道（生产 create() 由桥注入；默认空通道，窗口不延长）。
         *  Channel 同时是 Send+Receive：桥写、仲裁器读。 */
        pending: Channel<Unit> = Channel(),
    ): Pair<VoiceEngine, MockVehicleState> {
        val vehicle = MockVehicleState()
        // B2：仲裁器 utteranceId 延迟绑定引擎会话（生产 create() 同款 engineRef 模式，
        // 非最新 uid 拦截在测试里与生产语义一致）
        var engineRef: VoiceEngine? = null
        val engine = VoiceEngine(
            cfg = cfg(cloudWaitMs),
            arbiter = OnDeviceRaceArbiter(
                cloudWaitMs = cloudWaitMs,
                localFallbackMs = localFallbackMs,
                clock = System::currentTimeMillis,
                sink = sink,
                utteranceId = { engineRef?.session?.currentUtteranceId ?: "" },
                // B2：仲裁过程事件 → telemetry 插桩（生产 create() 同款映射）
                onEvent = { event ->
                    when (event) {
                        is OnDeviceArbiterEvent.Received -> telemetry.record(
                            TelemetryStages.DEVICE_ARBITER_RECEIVED,
                            "info",
                            mapOf("route" to event.route),
                        )
                        is OnDeviceArbiterEvent.Won -> telemetry.record(
                            TelemetryStages.DEVICE_ARBITER_WON,
                            "info",
                            mapOf("route" to event.route, "reason" to event.reason),
                        )
                        is OnDeviceArbiterEvent.Lost -> telemetry.record(
                            TelemetryStages.DEVICE_ARBITER_LOST,
                            "warn",
                            mapOf("route" to event.route, "reason" to event.reason),
                        )
                        // B5：pending 占位（非收敛事件）→ device_arbiter_pending 插桩
                        is OnDeviceArbiterEvent.Pending -> telemetry.record(
                            TelemetryStages.DEVICE_ARBITER_PENDING,
                            "info",
                            mapOf("route" to event.route, "reason" to "llm_pending"),
                        )
                    }
                },
                pending = pending,
            ),
            sink = sink,
            telemetry = telemetry,
            networkAvailable = networkAvailable,
            local = local,
            cloud = cloud,
            tts = tts,
            player = player,
            ttsCache = ttsCache,
            vehicle = vehicle,
            navigation = navigation,
            scope = scope,
            debugBuild = debugBuild,
            onLocalRecognized = onRecognized,
            onCloudPending = onCloudPending,
        )
        engineRef = engine
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

    /**
     * 从 MockWebServer 取请求直到命中目标 path（上限 5 个；异步 POST 顺序不保证，
     * 例如 uploadAudio 的 /audio 可能晚于 round 到达，需跳过）。
     */
    private fun takeRequestUntil(server: MockWebServer, path: String): RecordedRequest? {
        repeat(5) {
            val r = server.takeRequest(5, TimeUnit.SECONDS) ?: return null
            if (r.path == path) return r
        }
        return null
    }

    /**
     * 聚合全部 /api/telemetry/events 直传事件（T7 recordFor 单事件直传：每次 POST 一条，
     * 异步顺序不保证；取到超时为止）。cache 事件在 speakViaTts 的 launch 内产生，
     * 晚于 round 收包 → 全部经此通道。
     */
    private fun collectLateEvents(server: MockWebServer): List<JSONObject> {
        val events = mutableListOf<JSONObject>()
        while (true) {
            val r = server.takeRequest(2, TimeUnit.SECONDS) ?: break
            if (r.path != "/api/telemetry/events") continue
            val arr = JSONObject(r.body.readUtf8()).getJSONArray("events")
            for (i in 0 until arr.length()) events.add(arr.getJSONObject(i))
        }
        return events
    }

    /**
     * T7 评审 C1 修复证明：tts_play 事件在轮收包前后都不丢、不串轮——
     * 轮内到达（routeCloudReply 同步播放回调）→ 经 recordFor 并入本轮 round events，
     * 随 end() 一并 POST；轮已关闭后到达（MediaPlayer 异步完成回调）→ 单事件直传
     * /api/telemetry/events，utteranceId 仍属本轮（不并入下一轮）。
     */
    @Test
    fun `tts_play before and after round end both land on their utterance`() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200)) // uploadAudio multipart POST
        server.enqueue(MockResponse().setResponseCode(200)) // round POST
        server.enqueue(MockResponse().setResponseCode(200)) // /events POST
        val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var engine: VoiceEngine
        try {
            runBlocking {
                val telemetry = TelemetryClient(
                    okHttp = OkHttpClient(),
                    baseUrl = "http://localhost:${server.port}",
                    deviceId = "demo-1",
                    scope = telemetryScope,
                    enabled = true,
                )
                val pair = engine(
                    scope = this,
                    local = LocalChainRunner { powerOnIntent() },
                    cloud = CloudRunner {
                        AudioReply(
                            mime = "audio/wav",
                            data = ByteArray(8) { it.toByte() },
                            speakText = "好的",
                            intent = null,
                        )
                    },
                    telemetry = telemetry,
                    player = AudioPlayer {
                        // 模拟 TtsPlayer 真实回调路径：routeCloudReply 内 play 同步发 start（轮未收包）
                        engine.onTtsPlayEvent("start", "info", mapOf("bytes" to 8, "mime" to "audio/wav"))
                    },
                )
                engine = pair.first
                utter(engine)
            }
            // 轮收包：onTurnSegment 的 uploadAudio（/audio）与 end 的 round POST 异步到达
            // （顺序不保证），跳过非目标请求取 round——round events 应含轮内到达的
            // tts_play（start 落入本轮）
            val roundReq = takeRequestUntil(server, "/api/telemetry/round")
            assertNotNull(roundReq, "end 应 POST /api/telemetry/round")
            assertEquals("/api/telemetry/round", roundReq!!.path)
            val roundBody = JSONObject(roundReq.body.readUtf8())
            val utteranceId = roundBody.getString("utteranceId")
            val roundEvents = roundBody.getJSONArray("events")
            val playInRound = (0 until roundEvents.length()).any { i ->
                val e = roundEvents.getJSONObject(i)
                e.getString("stage") == TelemetryStages.TTS_PLAY_START &&
                    e.getString("level") == "info" &&
                    e.getJSONObject("payload").getString("source") == "network" &&
                    e.getJSONObject("payload").getString("event") == "start"
            }
            assertTrue(playInRound, "轮未收包时到达的 tts_play 应并入本轮 round events")

            // 轮已关闭后的迟到完成回调 → 单事件直传 /events，utteranceId 仍属本轮
            // （迟到的 /audio 请求若晚到会被 takeRequestUntil 跳过）
            engine.onTtsPlayEvent("completed", "info", mapOf("bytes" to 8))
            val eventsReq = takeRequestUntil(server, "/api/telemetry/events")
            assertNotNull(eventsReq, "轮关闭后的迟到 tts_play 应 POST /api/telemetry/events")
            assertEquals("/api/telemetry/events", eventsReq!!.path)
            val eventsBody = JSONObject(eventsReq.body.readUtf8())
            assertEquals(utteranceId, eventsBody.getString("utteranceId"), "迟到事件应归属本轮 utteranceId")
            val late = eventsBody.getJSONArray("events").getJSONObject(0)
            assertEquals(TelemetryStages.TTS_PLAY_END, late.getString("stage"))
            assertEquals("info", late.getString("level"))
            assertEquals("network", late.getJSONObject("payload").getString("source"))
            assertEquals("completed", late.getJSONObject("payload").getString("event"))
            assertTrue(late.getLong("tsMs") > 0)
        } finally {
            telemetryScope.cancel()
            server.shutdown()
        }
    }

    // ------------------------------------------------------------------ B5：pending 占位（LLM 处理中）

    /**
     * B5（协议 §4.8）：pending 占位只改 UI 状态（onCloudPending true → false 序列），
     * 不触发执行/播报；窗口延长后最终语义到达照常云端胜出并播报。
     * 时序要点：cloudWaitMs=100 是硬窗——pending 未生效时 150ms 处已走阶段 2 兜底
     * （本地 unknown → Failed → 兜底话术），tts 非空即证明窗口未延长。
     */
    @Test
    fun `pending placeholder only toggles cloud pending then final plays normally`() {
        val pendingStates = mutableListOf<Boolean>()
        val pendingSignals = Channel<Unit>(Channel.BUFFERED)
        val cloudReply = CompletableDeferred<Reply>()
        val ttsTexts = mutableListOf<String>()
        lateinit var engine: VoiceEngine
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { Intent.unknown("rule.nlu") }, // 本地拒识：不参与胜出
                cloud = CloudRunner { cloudReply.await() },
                tts = TtsRequester { text ->
                    ttsTexts.add(text)
                    null // 只验证请求发出，不验证播放
                },
                onCloudPending = { pendingStates.add(it) },
                pending = pendingSignals,
            )
            engine = pair.first
            val vehicle = pair.second
            utter(engine) // 竞速启动（cloudWaitMs=100）
            // B5 pending 占位到达（生产路径：桥对账 → 信号延长窗口 + onPendingReceived → setCloudPending(true)）
            pendingSignals.trySend(Unit)
            engine.setCloudPending(true)
            delay(150) // 越过原 cloudWaitMs=100：pending 未延长窗口时此处已兜底播报
            // 序列：utter() 起始置 false（onListeningStart）→ pending 置 true
            assertEquals(listOf(false, true), pendingStates.toList(), "pending 期间 UI 状态应为 true")
            assertEquals(0, ttsTexts.size, "pending 本身不播报；窗口延长后仍在等云端（未走兜底）")
            assertFalse(vehicle.isAcOn, "pending 无执行")
            assertFalse(vehicle.isWindowsOpen, "pending 无执行")
            // 最终语义到达 → 云端胜出：清除 pending + 正常播报
            cloudReply.complete(TextReply("已为您打开车窗"))
            // complete() 只安排协程恢复：让出事件循环等竞速收敛 + speakViaTts 子协程执行
            delay(100)
            assertEquals(listOf(false, true, false), pendingStates.toList(), "final 到达应清除 pending（置 false）")
            assertEquals(listOf("已为您打开车窗"), ttsTexts, "final 照常播报")
        }
    }

    // ------------------------------------------------------------------ B1：vad_start / vad_end（需求 2）

    /** 从 events 数组找指定 stage 的第一条（无则 null）。 */
    private fun findEvent(events: JSONArray, stage: String): JSONObject? {
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            if (e.getString("stage") == stage) return e
        }
        return null
    }

    /** 统计 events 数组中指定 stage 的出现次数。 */
    private fun countStage(events: JSONArray, stage: String): Int {
        var n = 0
        for (i in 0 until events.length()) {
            if (events.getJSONObject(i).getString("stage") == stage) n++
        }
        return n
    }

    /** 指定 stage 全部事件的 tsMs 最小值（无该 stage 时返回 Long.MAX_VALUE）。 */
    private fun minTsOf(events: JSONArray, stage: String): Long {
        var min = Long.MAX_VALUE
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            if (e.getString("stage") == stage) min = minOf(min, e.getLong("tsMs"))
        }
        return min
    }

    /**
     * B1（需求 2 修订）：vad start 产生 uuid——utteranceId 由首个 SpeechStart 产生（单一
     * id 贯穿全轮，vadId 与 utteranceId 是同一个），vad_start/vad_end 配对落库并随 end()
     * 一并 POST。守卫断言：录音外（非 LISTENING）的杂散 SpeechStart 忽略（不产生 id 不
     * 记录）；同轮后续段不重复产生 utteranceId（一个 utterance 一轮）。
     */
    @Test
    fun `onVadStart produces utteranceId once and pairs vad_start with vad_end`() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200)) // uploadAudio multipart POST
        server.enqueue(MockResponse().setResponseCode(200)) // round POST
        val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                val telemetry = TelemetryClient(
                    okHttp = OkHttpClient(),
                    baseUrl = "http://localhost:${server.port}",
                    deviceId = "demo-1",
                    scope = telemetryScope,
                    enabled = true,
                )
                val pair = engine(
                    scope = this,
                    local = LocalChainRunner { powerOnIntent() },
                    cloud = CloudRunner { TextReply("好的") },
                    telemetry = telemetry,
                )
                val engine = pair.first
                engine.onVadStart() // 录音外（IDLE）的杂散 SpeechStart → 忽略，不产生 utteranceId
                engine.onListeningStart()
                engine.onVadStart() // 首个段：产生 utteranceId + utterance_start + vad_start
                engine.onVadStart() // 同轮第二段：不重复产生 id，只记 vad_start
                engine.onVadEnd() // 段 2 结束
                engine.onVadEnd() // 段 1 结束（SpeechEnd 配对不校验顺序，同轮即可）
                engine.onTurnSegment(segment)
            }
            val roundReq = takeRequestUntil(server, "/api/telemetry/round")
            assertNotNull(roundReq, "end 应 POST /api/telemetry/round")
            val events = JSONObject(roundReq!!.body.readUtf8()).getJSONArray("events")
            val start = findEvent(events, TelemetryStages.VAD_START)
            val end = findEvent(events, TelemetryStages.VAD_END)
            assertNotNull(start, "应记录 vad_start")
            assertNotNull(end, "应记录 vad_end")
            assertTrue(end!!.getLong("tsMs") >= start!!.getLong("tsMs"), "vad_end 不得早于 vad_start")
            // 两个段：2 条 vad_start + 2 条 vad_end，但只 1 个 utterance_start（单 id 一轮）
            assertEquals(2, countStage(events, TelemetryStages.VAD_START), "两个语音段应有 2 条 vad_start")
            assertEquals(2, countStage(events, TelemetryStages.VAD_END), "两个语音段应有 2 条 vad_end")
            assertEquals(1, countStage(events, TelemetryStages.UTTERANCE_START), "同轮 utteranceId 只产生一次")
            // utterance_start 产生于首个 vad start（不晚于第一条 vad_start）
            assertTrue(
                findEvent(events, TelemetryStages.UTTERANCE_START)!!.getLong("tsMs") <= minTsOf(events, TelemetryStages.VAD_START),
                "utteranceId 应产生于首个 vad start（utterance_start 不晚于第一条 vad_start）",
            )
        } finally {
            telemetryScope.cancel()
            server.shutdown()
        }
    }

    /**
     * B1：onListeningStart 清空 utteranceId——新一轮开始后，旧轮的迟到 vad_end 被忽略
     * （utteranceId 为空），新轮首个 SpeechStart 重新产生 id 并配对落库。
     */
    @Test
    fun `onListeningStart resets utteranceId so stale vad_end is ignored`() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200)) // uploadAudio multipart POST
        server.enqueue(MockResponse().setResponseCode(200)) // round POST
        val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                val telemetry = TelemetryClient(
                    okHttp = OkHttpClient(),
                    baseUrl = "http://localhost:${server.port}",
                    deviceId = "demo-1",
                    scope = telemetryScope,
                    enabled = true,
                )
                val pair = engine(
                    scope = this,
                    local = LocalChainRunner { powerOnIntent() },
                    cloud = CloudRunner { TextReply("好的") },
                    telemetry = telemetry,
                )
                val engine = pair.first
                engine.onListeningStart() // utt 轮 1
                engine.onVadStart() // 产生 utt-1 + vad_start
                engine.onListeningStart() // utt 轮 2：utteranceId 清空
                engine.onVadEnd() // 旧轮残留 SpeechEnd → 忽略（utteranceId 为空）
                engine.onVadStart() // 轮 2 首个段：重新产生 id
                engine.onVadEnd()
                engine.onTurnSegment(segment)
            }
            val roundReq = takeRequestUntil(server, "/api/telemetry/round")
            assertNotNull(roundReq, "end 应 POST /api/telemetry/round")
            val events = JSONObject(roundReq!!.body.readUtf8()).getJSONArray("events")
            assertEquals(1, countStage(events, TelemetryStages.VAD_START), "新轮只应有 1 条 vad_start")
            assertEquals(1, countStage(events, TelemetryStages.VAD_END), "旧轮残留 vad_end 不得落库")
            assertEquals(1, countStage(events, TelemetryStages.UTTERANCE_START), "新轮 utteranceId 只产生一次")
        } finally {
            telemetryScope.cancel()
            server.shutdown()
        }
    }

    /**
     * B2（需求 4）：云端快赢——round 内 device_arbiter_received(route=cloud) +
     * device_arbiter_won(route=cloud, reason=priority) 事件；本地未到 → 无 lost 事件。
     */
    @Test
    fun `device arbiter emits received and won events when cloud wins fast`() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200)) // uploadAudio multipart POST
        server.enqueue(MockResponse().setResponseCode(200)) // round POST
        val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                val telemetry = TelemetryClient(
                    okHttp = OkHttpClient(),
                    baseUrl = "http://localhost:${server.port}",
                    deviceId = "demo-1",
                    scope = telemetryScope,
                    enabled = true,
                )
                val pair = engine(
                    scope = this,
                    local = LocalChainRunner { delay(300); powerOnIntent() },
                    cloud = CloudRunner { delay(5); TextReply("好的") },
                    telemetry = telemetry,
                )
                utter(pair.first)
            }
            val roundReq = takeRequestUntil(server, "/api/telemetry/round")
            assertNotNull(roundReq, "end 应 POST /api/telemetry/round")
            val events = JSONObject(roundReq!!.body.readUtf8()).getJSONArray("events")
            val received = findEvent(events, TelemetryStages.DEVICE_ARBITER_RECEIVED)
            assertNotNull(received, "应记录 device_arbiter_received")
            assertEquals("cloud", received!!.getJSONObject("payload").getString("route"))
            val won = findEvent(events, TelemetryStages.DEVICE_ARBITER_WON)
            assertNotNull(won, "应记录 device_arbiter_won")
            assertEquals("cloud", won!!.getJSONObject("payload").getString("route"))
            assertEquals("priority", won.getJSONObject("payload").getString("reason"))
            assertNull(findEvent(events, TelemetryStages.DEVICE_ARBITER_LOST), "本地未到不得有 lost 事件")
        } finally {
            telemetryScope.cancel()
            server.shutdown()
        }
    }

    /**
     * B2（需求 2/4）：非最新 uid 的会话语义被拦截——云端链返回前 utteranceId 已刷新
     * （新一轮 vad start），语义丢弃：不播报不执行、不写决策，round 内记录
     * device_arbiter_lost(route=cloud, reason=not_latest_round)。
     */
    @Test
    fun `stale round semantic is intercepted with not_latest_round lost event`() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200)) // uploadAudio multipart POST
        server.enqueue(MockResponse().setResponseCode(200)) // round POST
        val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entries = mutableListOf<DecisionEntry>()
        lateinit var engine: VoiceEngine
        try {
            runBlocking {
                val telemetry = TelemetryClient(
                    okHttp = OkHttpClient(),
                    baseUrl = "http://localhost:${server.port}",
                    deviceId = "demo-1",
                    scope = telemetryScope,
                    enabled = true,
                )
                val pair = engine(
                    scope = this,
                    local = LocalChainRunner { delay(600); powerOnIntent() },
                    cloud = CloudRunner {
                        delay(200) // 云端语义返回前，模拟新一轮 vad start 已刷新 utteranceId
                        engine.session.currentUtteranceId = "utt-new"
                        TextReply("好的")
                    },
                    cloudWaitMs = 1000,
                    localFallbackMs = 2000,
                    telemetry = telemetry,
                    sink = DecisionSink { entries.add(it) },
                )
                engine = pair.first
                utter(engine)
            }
            assertEquals(emptyList<String>(), entries.map { it.reason }, "拦截不写决策")
            val roundReq = takeRequestUntil(server, "/api/telemetry/round")
            assertNotNull(roundReq, "end 应 POST /api/telemetry/round")
            val events = JSONObject(roundReq!!.body.readUtf8()).getJSONArray("events")
            val lost = findEvent(events, TelemetryStages.DEVICE_ARBITER_LOST)
            assertNotNull(lost, "应记录 device_arbiter_lost(not_latest_round)")
            assertEquals("cloud", lost!!.getJSONObject("payload").getString("route"))
            assertEquals("not_latest_round", lost.getJSONObject("payload").getString("reason"))
            assertNull(findEvent(events, TelemetryStages.DEVICE_ARBITER_WON), "拦截的语义不得有 won 事件")
        } finally {
            telemetryScope.cancel()
            server.shutdown()
        }
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
    fun `network unavailable → local only, cloud_unreachable, apply text via network tts, cloud never ran`() {
        val entries = mutableListOf<DecisionEntry>()
        val ttsRequests = mutableListOf<String>()
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
                // 2026-08-15：本地胜出播报统一走网络 TTS（不再用系统 TTS）——
                // 生产上 ready 未建立时 tts.request 返回 null（记失败事件），fake 记录请求文本
                tts = TtsRequester { ttsRequests.add(it); null },
            )
            engine = pair.first
            vehicle = pair.second
            utter(engine)
        }
        assertFalse(cloudRan, "无网络时云端链不得启动")
        assertTrue(vehicle.isAcOn, "本地意图应执行")
        assertEquals(listOf("已为您打开空调"), ttsRequests, "本地 apply 的文本应走网络 TTS 请求")
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
    fun `both routes fail → Failed → fallback phrase via network tts`() {
        val entries = mutableListOf<DecisionEntry>()
        val ttsRequests = mutableListOf<String>()
        lateinit var engine: VoiceEngine
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { awaitCancellation() },
                cloud = CloudRunner { awaitCancellation() },
                localFallbackMs = 150,
                sink = DecisionSink { entries.add(it) },
                // 2026-08-15：全败兜底话术同样走网络 TTS（不再用系统 TTS）
                tts = TtsRequester { ttsRequests.add(it); null },
            )
            engine = pair.first
            utter(engine)
        }
        assertEquals(listOf("both_failed"), entries.map { it.reason })
        assertEquals(listOf("网络开小差了，请稍后再试"), ttsRequests)
    }

    @Test
    fun `cloud text reply requests tts audio and plays it`() {
        val requested = mutableListOf<String>()
        val played = mutableListOf<AudioReply>()
        val audio =
            AudioReply(mime = "audio/wav", data = ByteArray(6) { it.toByte() }, speakText = "已为您把空调调到 24 度")
        lateinit var engine: VoiceEngine
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { powerOnIntent() },
                cloud = CloudRunner { TextReply("已为您把空调调到 24 度") },
                tts = TtsRequester { requested.add(it); audio },
                player = AudioPlayer { played.add(it) },
            )
            engine = pair.first
            utter(engine)
        }
        assertEquals(listOf("已为您把空调调到 24 度"), requested, "TextReply 应先请求 TTS 合成")
        assertEquals(1, played.size, "合成音频应播放")
    }

    @Test
    fun `cloud action reply applies intent and requests tts for its speakText`() {
        val requested = mutableListOf<String>()
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
                tts = TtsRequester { requested.add(it); null },
            )
            engine = pair.first
            vehicle = pair.second
            utter(engine)
        }
        assertEquals(24.0, vehicle.acTemperature, "ActionReply 的 intent 应执行")
        // 2026-08-15：ActionReply 的 speakText 走网络 TTS（不再用系统 TTS 兜底）
        assertEquals(listOf("已为您把空调调到24度"), requested, "应请求 TTS 合成 speakText")
    }

    @Test
    fun `cloud action reply requests tts audio and plays it`() {
        val requested = mutableListOf<String>()
        val played = mutableListOf<AudioReply>()
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
            )
            engine = pair.first
            vehicle = pair.second
            utter(engine)
        }
        assertEquals(24.0, vehicle.acTemperature, "ActionReply 的 intent 应执行")
        assertEquals(listOf("已为您把空调调到24度"), requested, "TTS 请求文本 = ActionReply 的 speakText")
        assertEquals(1, played.size, "TTS 返回音频 → 播放")
        assertArrayEquals(ttsAudio.data, played[0].data)
    }

    @Test
    fun `cloud navigate action reply opens amap navi uri and speaks speakText`() {
        val opened = mutableListOf<String>()
        val requested = mutableListOf<String>()
        lateinit var engine: VoiceEngine
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { powerOnIntent() },
                cloud = CloudRunner {
                    com.autovoice.voicecore.ActionReply(
                        intent = navigateIntent("杭州东站", 30.2896, 120.2108),
                        speakText = "好的，已为您规划去杭州东站的导航",
                    )
                },
                tts = TtsRequester { requested.add(it); null },
                navigation = NavigationExecutor { uri -> opened.add(uri); true },
            )
            engine = pair.first
            utter(engine)
        }
        // spec §4.2 URI 形状：androidamap://navi?sourceApplication=autovoice&poiname=<编码>&lat=<纬度>&lon=<经度>
        assertEquals(1, opened.size, "应拉起一次高德导航")
        assertEquals(
            "androidamap://navi?sourceApplication=autovoice" +
                "&poiname=%E6%9D%AD%E5%B7%9E%E4%B8%9C%E7%AB%99&lat=30.2896&lon=120.2108",
            opened[0],
        )
        assertEquals(listOf("好的，已为您规划去杭州东站的导航"), requested, "speakText 走网络 TTS")
    }

    @Test
    fun `cloud navigate with waypoints opens amap route plan uri`() {
        val opened = mutableListOf<String>()
        val requested = mutableListOf<String>()
        lateinit var engine: VoiceEngine
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { powerOnIntent() },
                cloud = CloudRunner {
                    com.autovoice.voicecore.ActionReply(
                        intent = navigateIntent(
                            poiname = "大旗杆",
                            lat = 38.8731,
                            lon = 115.4737,
                            waypointsJson = """[{"poiname":"爱情广场","lat":38.8654,"lon":115.4696}]""",
                        ),
                        speakText = "好的，已为您规划先去爱情广场再去大旗杆的导航",
                    )
                },
                tts = TtsRequester { requested.add(it); null },
                navigation = NavigationExecutor { uri -> opened.add(uri); true },
            )
            engine = pair.first
            utter(engine)
        }
        // 多目的地 URI 形状：amapuri://route/plan?…&dlat/dlon/dname=<终点>&vian=N&vialons/vialats/vianames=<途经|分隔>&t=0&dev=0
        assertEquals(1, opened.size, "应拉起一次高德路线规划")
        assertEquals(
            "amapuri://route/plan?sourceApplication=autovoice" +
                "&dlat=38.8731&dlon=115.4737&dname=%E5%A4%A7%E6%97%97%E6%9D%86" +
                "&vian=1&vialons=115.4696&vialats=38.8654&vianames=%E7%88%B1%E6%83%85%E5%B9%BF%E5%9C%BA" +
                "&t=0&dev=0",
            opened[0],
        )
        assertEquals(listOf("好的，已为您规划先去爱情广场再去大旗杆的导航"), requested, "speakText 走网络 TTS")
    }

    @Test
    fun `navigate with malformed waypoints json does not open amap`() {
        val opened = mutableListOf<String>()
        lateinit var engine: VoiceEngine
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { powerOnIntent() },
                cloud = CloudRunner {
                    com.autovoice.voicecore.ActionReply(
                        intent = navigateIntent(
                            poiname = "大旗杆",
                            lat = 38.8731,
                            lon = 115.4737,
                            waypointsJson = "不是JSON",
                        ),
                        speakText = "好的，已为您打开导航",
                    )
                },
                tts = TtsRequester { null },
                navigation = NavigationExecutor { uri -> opened.add(uri); true },
            )
            engine = pair.first
            utter(engine)
        }
        assertEquals(0, opened.size, "waypoints JSON 非法不拉起高德（静默回退会误导用户）")
    }

    @Test
    fun `navigate intent with missing slots is skipped and does not open amap`() {
        val opened = mutableListOf<String>()
        lateinit var engine: VoiceEngine
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { powerOnIntent() },
                cloud = CloudRunner {
                    com.autovoice.voicecore.ActionReply(
                        intent = navigateIntent("", 0.0, 0.0), // poiname 空白 = 槽位缺失
                        speakText = "好的，已为您打开导航",
                    )
                },
                tts = TtsRequester { null },
                navigation = NavigationExecutor { uri -> opened.add(uri); true },
            )
            engine = pair.first
            utter(engine)
        }
        assertEquals(0, opened.size, "缺 poiname 不拉起高德")
    }

    @Test
    fun `cloud text reply stays silent and records failure when tts times out`() {
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
            )
            engine = pair.first
            utter(engine)
        }
        assertTrue(ttsCalled, "TextReply 应先请求 TTS")
        // 2026-08-15：不再有系统 TTS 兜底——合成失败/超时静默（记 tts_play_end failed 事件，
        // 由 recordFor 直传 /events；本用例 telemetry disabled，事件进 no-op 不断言）
    }

    // --------------------------------------------------- 架构变更：TTS 缓存移回端侧（TtsCache）

    /** 缓存命中：直接播缓存音频，不发 tts_request（counting fake 断言 0 次），
     *  事件记 cache_check + cache_hit（带 bytes），不记 cache_miss。
     *  注：cache 事件在 speakViaTts 的 scope.launch 内记录，晚于 round 收包 →
     *  经 /events 直传（T7 同机制），故从 /events 断言。 */
    @Test
    fun `tts cache hit plays cached audio without network request`() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200)) // uploadAudio multipart POST
        server.enqueue(MockResponse().setResponseCode(200)) // round POST
        server.enqueue(MockResponse().setResponseCode(200)) // /events POST（轮关闭后的 cache 事件）
        val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cachedWav = ByteArray(32) { 7 }
        var requested = 0
        val played = mutableListOf<AudioReply>()
        try {
            runBlocking {
                val telemetry = TelemetryClient(
                    okHttp = OkHttpClient(),
                    baseUrl = "http://localhost:${server.port}",
                    deviceId = "demo-1",
                    scope = telemetryScope,
                    enabled = true,
                )
                // 事件桥同生产 create()：recordFor 快照通道（launch 晚于收口，record 会丢弃）
                var cacheEngineRef: VoiceEngine? = null
                val cache = TtsCache(null, onEvent = { s, l, p ->
                    val e = cacheEngineRef ?: return@TtsCache
                    telemetry.recordFor(e.session.currentUtteranceId, s, l, p)
                })
                cache.put("好的，车窗已打开", cachedWav)
                val pair = engine(
                    scope = this,
                    local = LocalChainRunner { powerOnIntent() },
                    cloud = CloudRunner { TextReply("好的，车窗已打开") },
                    telemetry = telemetry,
                    tts = TtsRequester { requested++; null },
                    player = AudioPlayer { played.add(it) },
                    ttsCache = cache,
                )
                cacheEngineRef = pair.first
                utter(pair.first)
            }
            assertEquals(0, requested, "缓存命中不应发 tts_request")
            assertEquals(1, played.size, "缓存音频应直接播放")
            assertArrayEquals(cachedWav, played[0].data, "播放的应是缓存音频字节")
            val events = collectLateEvents(server)
            val check = events.find { it.getString("stage") == TelemetryStages.TTS_CACHE_CHECK }
            assertNotNull(check, "缓存命中应记 tts_cache_check")
            assertEquals(
                "好的，车窗已打开",
                check!!.getJSONObject("payload").getString("text"),
                "cache_check payload 应带原文文本",
            )
            val hit = events.find { it.getString("stage") == TelemetryStages.TTS_CACHE_HIT }
            assertNotNull(hit, "缓存命中应记 tts_cache_hit")
            assertEquals(32, hit!!.getJSONObject("payload").getInt("bytes"), "cache_hit payload 应带字节数")
            assertTrue(events.none { it.getString("stage") == TelemetryStages.TTS_CACHE_MISS }, "命中时不得记 cache_miss")
        } finally {
            telemetryScope.cancel()
            server.shutdown()
        }
    }

    /** 缓存未命中：发 tts.request，返回音频 → 播放并写入缓存（put 后再次 get 命中），
     *  事件记 cache_check + cache_miss（轮关闭后直传 /events，见命中用例注释）。 */
    @Test
    fun `tts cache miss requests audio writes cache and plays`() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200)) // uploadAudio multipart POST
        server.enqueue(MockResponse().setResponseCode(200)) // round POST
        server.enqueue(MockResponse().setResponseCode(200)) // /events POST（轮关闭后的 cache 事件）
        val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requested = mutableListOf<String>()
        val played = mutableListOf<AudioReply>()
        lateinit var cache: TtsCache
        val ttsAudio =
            AudioReply(mime = "audio/wav", data = ByteArray(6) { it.toByte() }, speakText = "好的，车窗已打开")
        try {
            runBlocking {
                val telemetry = TelemetryClient(
                    okHttp = OkHttpClient(),
                    baseUrl = "http://localhost:${server.port}",
                    deviceId = "demo-1",
                    scope = telemetryScope,
                    enabled = true,
                )
                // 事件桥同生产 create()：recordFor 快照通道（launch 晚于收口，record 会丢弃）
                var cacheEngineRef: VoiceEngine? = null
                cache = TtsCache(null, onEvent = { s, l, p ->
                    val e = cacheEngineRef ?: return@TtsCache
                    telemetry.recordFor(e.session.currentUtteranceId, s, l, p)
                })
                val pair = engine(
                    scope = this,
                    local = LocalChainRunner { powerOnIntent() },
                    cloud = CloudRunner { TextReply("好的，车窗已打开") },
                    telemetry = telemetry,
                    tts = TtsRequester { requested.add(it); ttsAudio },
                    player = AudioPlayer { played.add(it) },
                    ttsCache = cache,
                )
                cacheEngineRef = pair.first
                utter(pair.first)
            }
            assertEquals(listOf("好的，车窗已打开"), requested, "未命中应先请求 TTS")
            assertEquals(1, played.size, "网络音频应播放")
            assertArrayEquals(ttsAudio.data, played[0].data)
            assertArrayEquals(ttsAudio.data, cache.get("好的，车窗已打开"), "收到音频应写入缓存")
            // launch 内 check+miss 直传 /events（断言处的 get 会再产生 check+hit，聚合后只断言存在性）
            val events = collectLateEvents(server)
            assertTrue(
                events.any { it.getString("stage") == TelemetryStages.TTS_CACHE_CHECK },
                "未命中也应先记 tts_cache_check",
            )
            assertTrue(
                events.any { it.getString("stage") == TelemetryStages.TTS_CACHE_MISS },
                "未命中应记 tts_cache_miss",
            )
        } finally {
            telemetryScope.cancel()
            server.shutdown()
        }
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

    @Test
    fun `streaming cloud winner feeds audio player then applies end intent`() {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        chunks.trySend(byteArrayOf(1, 2))
        chunks.trySend(byteArrayOf(3, 4))
        chunks.close()
        val completion = CompletableDeferred(
            AudioStreamEnd("The air conditioner is on", powerOnIntent(), "Turn on the air conditioner"),
        )
        val stream = StreamingAudioReply(
            mime = "audio/pcm",
            sampleRate = 24_000,
            channels = 1,
            encoding = "pcm_s16le",
            chunks = chunks,
            completion = completion,
        )
        val played = mutableListOf<Byte>()
        lateinit var engine: VoiceEngine
        lateinit var vehicle: MockVehicleState
        val recognized = mutableListOf<String?>()
        runBlocking {
            val pair = engine(
                scope = this,
                local = LocalChainRunner { delay(300); Intent.unknown("local") },
                cloud = CloudRunner { stream },
                player = object : AudioPlayer {
                    override fun play(reply: AudioReply) = Unit
                    override suspend fun playStream(reply: StreamingAudioReply) {
                        for (chunk in reply.chunks) played.addAll(chunk.toList())
                    }
                },
                onRecognized = recognized::add,
            )
            engine = pair.first
            vehicle = pair.second
            utter(engine)
        }
        assertEquals(listOf<Byte>(1, 2, 3, 4), played)
        assertEquals(listOf("Turn on the air conditioner"), recognized)
        assertTrue(vehicle.isAcOn, "流结束携带的 intent 应只在云端胜出后执行")
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
    }
}

package com.autovoice.app

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.autovoice.adapteriflytek.FakeCommandAsrProvider
import com.autovoice.adapteriflytek.IflytekOfflineCommandAsrStage
import com.autovoice.adapteriflytek.RuleNluProvider
import com.autovoice.app.audio.TtsCache
import com.autovoice.app.telemetry.TelemetryClient
import com.autovoice.app.telemetry.TelemetryStages
import com.autovoice.gatewayclient.GatewayClient
import com.autovoice.gatewayclient.GatewayException
import com.autovoice.voicecore.ActionReply
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.CloudConfig
import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.GatewayMessage
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.TextReply
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.voicecore.AudioStreamEnd
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.arbiter.OnDeviceArbiterEvent
import com.autovoice.voicecore.arbiter.OnDeviceRaceArbiter
import com.autovoice.voicecore.arbiter.RaceWinner
import com.autovoice.voicecore.session.CloudRunner
import com.autovoice.voicecore.session.CloudUnavailableException
import com.autovoice.voicecore.session.LocalChainRunner
import com.autovoice.voicecore.session.ResultListener
import com.autovoice.voicecore.session.SessionState
import com.autovoice.voicecore.session.VoiceSession
import com.google.gson.JsonObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/** 云端音频回复播放出口（应用层实现：TtsPlayer）。JVM 测试可注入 fake。 */
fun interface AudioPlayer {
    fun play(reply: AudioReply)

    /** 默认实现累积后复用完整音频播放器；生产 TtsPlayer 覆盖为 AudioTrack 边收边播。 */
    suspend fun playStream(reply: StreamingAudioReply) {
        val pcm = ByteArrayOutputStream()
        for (chunk in reply.chunks) pcm.write(chunk)
        val end = reply.completion.await()
        play(AudioReply("audio/pcm", pcm.toByteArray(), end.speakText, end.intent, end.asrText))
    }
}

/**
 * 独立 TTS 播报请求（TTS 解耦 v1.1）：设备执行 intent 后按 speakText 向服务端
 * 请求合成音频；返回 null = 失败/超时（调用方静默处理并记失败事件，不重试）。
 * 生产实现：GatewayCloudRunner（tts_request/tts_response 独立槽）。
 */
fun interface TtsRequester {
    suspend fun request(text: String): AudioReply?
}

/** 云端音频分块大小（gateway 协议 16KB/帧）。 */
private const val CLOUD_CHUNK_BYTES = 16_384

/** 网关事件桥日志 TAG。 */
private const val GATEWAY_BRIDGE_TAG = "GatewayBridge"

/**
 * 端侧全局装配点（Task 20）：双链路竞速引擎 + 播报/执行路由。
 *
 * 持有装配好的 [VoiceSession]（本地链 + 云端链 + [OnDeviceRaceArbiter]，见 voice-core
 * §5.1 编排语义）与两个出口：[player]（音频播放）、[vehicle]（车控执行）。
 * 播报统一走网络 TTS（2026-08-15：不用系统 TTS，所有路径经 [speakViaTts]）。结果路由
 * （Task 20 交付物）：
 *  - [RaceWinner.Cloud]：AudioReply → 播放 + 附 intent 执行；TextReply → 播报；
 *    ActionReply → 执行 intent + 播报自带 speakText；
 *  - [RaceWinner.Local]：`vehicle.apply(intent)` 成功 → 播报其返回文本（未知意图不播报）；
 *  - [RaceWinner.Failed]：播报兜底话术「[FALLBACK_PHRASE]」（按钮录音模式下全败
 *    需要明确反馈；决策日志已记录失败原因）。
 *
 * 弱网调试 hook（仅 debug 构建暴露）：[weakNetwork] 为 true 时云端链启动前人为 delay 3000ms，
 * 云端赶不上 cloudWaitMs → 仲裁回落到本地（reason `cloud_timeout_use_local`）。
 *
 * 生产装配走 [create]（真实本地链 + GatewayClient 云端链 + ConnectivityManager 网络检查）；
 * 构造器直接注入链/仲裁器/出口（JVM 测试用 fake）。
 */
class VoiceEngine(
    cfg: DemoConfig,
    arbiter: OnDeviceRaceArbiter,
    sink: DecisionSink,
    /**
     * 链路数据上报客户端（T6）：生产装配由 [create] 注入（telemetry 未配置 → enabled=false
     * 的全 no-op 实例）；JVM 测试不传时用默认 disabled 实例，行为不变。
     */
    private val telemetry: TelemetryClient = TelemetryClient(
        okHttp = OkHttpClient(),
        baseUrl = "",
        deviceId = null,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        enabled = false,
    ),
    private val networkAvailable: () -> Boolean,
    local: LocalChainRunner,
    cloud: CloudRunner,
    private val tts: TtsRequester,
    private val player: AudioPlayer,
    /**
     * 端侧 TTS 缓存（架构变更：缓存从服务器移回端侧）：speakViaTts 先查缓存，
     * 命中直接播（不请求服务器）；未命中走 [tts] 网络合成，回传写缓存再播。
     * 默认仅内存（JVM 测试注入预置缓存/fake）；生产装配由 [create] 注入。
     */
    private val ttsCache: TtsCache = TtsCache(null),
    val vehicle: MockVehicleState,
    /**
     * 导航执行器（spec §4.2）：navigation/navigate 意图不走 vehicle，转高德 URI 拉起高德 App。
     * null（测试/未装配）时导航意图记 skipped。
     */
    private val navigation: NavigationExecutor? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onVehicleApplied: () -> Unit = {},
    /** 本地 ASR 识别文本回调（Task 34：UI 显示识别结果，未检出时为 null）。 */
    private val onLocalRecognized: (String?) -> Unit = {},
    private val debugBuild: Boolean = BuildConfig.DEBUG,
    /** 释放钩子（生产装配注册网关断开；Task 21 模式切换）。 */
    private val onClose: () -> Unit = {},
    /**
     * B5：云端 pending 占位回调（LLM 处理中，协议 §4.8）：收到 pending 帧 → true，
     * 最终语义到达 / 新一轮开始 → false。UI 据此显示"处理中…"徽标，无执行无播报。
     */
    private val onCloudPending: (Boolean) -> Unit = {},
) {

    /** 弱网调试 hook（调试构建的 UI 开关）：true 且 [debugBuild] 时云端链人为延迟 [WEAK_NETWORK_DELAY_MS]。 */
    @Volatile
    var weakNetwork: Boolean = false

    /**
     * 当前话语的链路追踪 ID（T6）：**由 VAD 段开始（[onVadStart]，SpeechStart）产生**——
     * vad start 的 uuid 就是 utteranceId，单一 id 贯穿本轮全部插桩
     * （audio_start/tts_request 上行 + telemetry 事件）；云端链在 IO 线程读取，
     * volatile 可见。无 VAD 场景（vadUnavailable）由 [onTurnSegment] 兜底产生。
     */
    @Volatile
    private var currentUtteranceId = ""

    /**
     * TTS 网络播放事件入口（T7）：TtsPlayer（MainViewModel 装配）的 onPlayEvent 接到这里，
     * 构造时（init）已绑定 telemetry.recordFor（T7 评审 C1，见下）——播放线程/主线程回调
     * 内部 @Synchronized 串行；enabled=false 时 recordFor no-op，零影响。
     */
    val onTtsPlayEvent: (stage: String, level: String, payload: Map<String, Any?>) -> Unit

    /**
     * 发起播报时快照的 utteranceId（T7 评审 C1）：onTurnResult 收口处（本轮所有 player.play
     * / speakViaTts 的源头）取当前 utteranceId。播放完成/失败的异步回调
     * 晚于 [telemetry.end] 收包，必须用这个快照而非 current（后者可能已被下一轮覆盖）；
     * 回调经 [recordFor] 归属到快照轮（轮已关闭 → 单事件直传 /events，不并入下一轮）。
     */
    @Volatile
    private var playUtteranceId = ""

    /** 本轮云端 VAD 段计数（T7 vad 聚合统计；onListeningStart 清零，onCloudSegment 累加）。 */
    private var turnSegmentCount = 0

    /** 本轮云端 VAD 段总时长 ms（T7 vad 聚合统计；16k 单声道 16bit，bytes/32 = ms）。 */
    private var turnSegmentsTotalMs = 0L

    /** 装配好的会话：状态机 + 双路由竞速编排。 */
    val session: VoiceSession

    init {
        // T7 评审 C1：网络播放事件绑定 telemetry（用 playUtteranceId 快照走 recordFor——
        // 异步回调晚于 end() 收包；轮已关闭/跨轮时单事件直传 /api/telemetry/events）。
        // B4 需求 1 事件细分：TtsPlayer 的 stage（start/completed/failed/interrupted）→
        // tts_play_start / tts_play_interrupted / tts_play_end（completed 与 failed
        // 都是播放结束，level 由 TtsPlayer 给出 info/error/warn），原始 stage 以
        // event 字段进 payload
        onTtsPlayEvent = { stage, level, payload ->
            val ttsStage = when (stage) {
                "start" -> TelemetryStages.TTS_PLAY_START
                "interrupted" -> TelemetryStages.TTS_PLAY_INTERRUPTED
                else -> TelemetryStages.TTS_PLAY_END
            }
            telemetry.recordFor(
                playUtteranceId,
                ttsStage,
                level,
                payload + mapOf("source" to "network", "event" to stage),
            )
        }
        session = VoiceSession(
            cfg = cfg,
            arbiter = arbiter,
            sink = sink,
            local = local,
            cloud = CloudRunner { segment ->
                // 弱网调试（仅 debug 构建）：云端链启动前人为延迟，让云端错过 cloudWaitMs
                if (weakNetwork && debugBuild) delay(WEAK_NETWORK_DELAY_MS)
                cloud.run(segment)
            },
            scope = scope,
            resultListener = ResultListener { onTurnResult(it) },
        )
    }

    /**
     * 释放引擎（Task 21 模式切换 / ViewModel 销毁）：断开网关连接（[onClose] 钩子）
     * + 取消引擎协程作用域（在途竞速、网关事件桥收集全部终止）。幂等；关闭后
     * [session] 不再产生状态回调/结果路由，装配时传入的 [scope] 不可复用
     * （生产装配每次重建引擎时新建专属 scope，不复用 viewModelScope）。
     */
    fun close() {
        runCatching { onClose() }.onFailure { Log.w(TAG, "引擎释放钩子失败", it) }
        scope.cancel()
    }

    // ------------------------------------------------------------------ 话语入口（MainViewModel 接线）

    /**
     * 录音开始：清空上一轮状态（utteranceId 由首个 VAD 段开始产生——vad start 的
     * uuid 就是 utteranceId，需求 2 单一 id）；网络可用则重新启用云端路由（断网恢复
     * 场景），否则立即挂起云端（本轮起只跑本地链，reason `cloud_unreachable`），
     * 再进入 LISTENING。
     */
    fun onListeningStart() {
        // 新一轮开始：utteranceId 清空，首个 SpeechStart（onVadStart）重新产生
        currentUtteranceId = ""
        session.currentUtteranceId = ""
        // B5：新一轮开始清除上一轮的"处理中"占位状态
        setCloudPending(false)
        // T7 vad 聚合统计：本轮从零开始
        turnSegmentCount = 0
        turnSegmentsTotalMs = 0L
        if (networkAvailable()) session.onCloudAvailable() else session.onCloudUnavailable()
        session.onListeningStart()
    }

    /**
     * B5：云端 pending 占位状态（true = LLM 处理中，仅 UI 状态；false = 已清除）。
     * 由 [GatewayCloudRunner.onPendingReceived]（收到 pending 帧）与
     * [onTurnResult]（最终语义到达）调用。
     */
    fun setCloudPending(v: Boolean) {
        onCloudPending(v)
    }

    /**
     * VAD 语音段开始（录音实时，SpeechStart 触发，需求 2）：
     *  - 本轮首个段：**产生 utteranceId**（vad start 的 uuid，单一 id 贯穿全轮）——
     *    开启 telemetry 轮并记录话语开始；会话与云端链同步该 id（仲裁器 provider 读它，
     *    非最新 uid 的会话语义被拦截，B2）；
     *  - 同轮后续段：不重复产生，只记 vad_start。
     * 守卫：非录音中（LISTENING）的杂散 SpeechStart 忽略。
     */
    fun onVadStart() {
        if (session.state.value != SessionState.LISTENING) return
        if (currentUtteranceId.isBlank()) {
            currentUtteranceId = UUID.randomUUID().toString()
            // T7：会话也持有本轮 utteranceId——on-device 决策日志（仲裁器 provider / localOnly）带真实值
            session.currentUtteranceId = currentUtteranceId
            telemetry.begin(currentUtteranceId)
            telemetry.record(TelemetryStages.UTTERANCE_START, "info", mapOf("source" to "button"))
        }
        telemetry.record(TelemetryStages.VAD_START, "info", emptyMap())
    }

    /**
     * VAD 语音段结束（录音实时，SpeechEnd 触发，需求 2）：记 vad_end 事件（与
     * [onVadStart] 配对；无话语时静默跳过——防杂散事件）。
     */
    fun onVadEnd() {
        if (currentUtteranceId.isBlank()) return
        telemetry.record(TelemetryStages.VAD_END, "info", emptyMap())
    }

    /**
     * 云端路段（Task 49 双路：VAD 切出的语音段，按住期间每切出一段喂一段，
     * 0..n 个）：入队串行上云，回复挂在会话的云端收敛点。
     * 必须在 [onTurnSegment] 之前全部喂完（会话防御丢弃倒序段）。
     * T7：累加本轮 VAD 聚合统计（段数/总时长，随 onTurnSegment 的 vad 事件上报）。
     */
    fun onCloudSegment(segment: ByteArray) {
        if (session.state.value == SessionState.LISTENING) {
            turnSegmentCount += 1
            turnSegmentsTotalMs += durationMs(segment.size)
        }
        session.onCloudSegment(segment)
    }

    /**
     * 本地路整段音频（Task 49 双路：抬手后完整降噪段）：无 VAD 场景（vadUnavailable /
     * 未切出语音段）兜底产生 utteranceId 并开启 telemetry 轮（首个 SpeechStart 缺席，
     * 否则本轮事件全丢）；再记录 VAD 事件（含本轮聚合统计：段数/总时长；maxProb 在
     * VadSegmenter 内、AudioRecorder 持有，此处不可得）+ 上传 VAD 后 PCM 到数据平台
     * （T6），最后启动双路竞速收敛。
     */
    fun onTurnSegment(segment: ByteArray) {
        if (currentUtteranceId.isBlank()) {
            currentUtteranceId = UUID.randomUUID().toString()
            session.currentUtteranceId = currentUtteranceId
            telemetry.begin(currentUtteranceId)
            telemetry.record(TelemetryStages.UTTERANCE_START, "info", mapOf("source" to "button"))
        }
        telemetry.record(
            TelemetryStages.VAD,
            "info",
            mapOf(
                "bytes" to segment.size,
                "durationMs" to durationMs(segment.size),
                // 聚合：云端段数 + 本地整段 = 本轮 VAD 切段总数；总时长为云端段 + 本地段之和
                "segmentCount" to turnSegmentCount + 1,
                "totalMs" to turnSegmentsTotalMs + durationMs(segment.size),
            ),
        )
        telemetry.uploadAudio(currentUtteranceId, segment)
        session.onTurnSegment(segment)
    }

    /** 16k 单声道 16bit PCM 字节数 → 毫秒（与 AudioRecorder/TtsPlayer 同口径：32000B/s）。 */
    private fun durationMs(bytes: Int): Long = bytes * 1000L / 32_000

    /** 录音中止（用户抬手/放弃）：回 IDLE；进行中的竞速不受影响（会话防御）。 */
    fun onListeningStop() {
        session.onListeningStop()
    }

    // ------------------------------------------------------------------ 结果路由

    private fun onTurnResult(winner: RaceWinner) {
        // T7 评审 C1：本轮所有播报（player.play / speakViaTts）由此发起，
        // 先快照 utteranceId——播放的异步结果回调在 end() 收包之后才到，凭快照归属本轮
        playUtteranceId = currentUtteranceId
        when (winner) {
            is RaceWinner.Cloud -> routeCloudReply(winner.reply)
            is RaceWinner.Local -> {
                val applied = vehicle.apply(winner.intent)
                // T7 execute：本地意图执行结果（未知意图 apply 返回 null → skipped，静默不播报）
                telemetry.record(
                    TelemetryStages.EXECUTE,
                    "info",
                    mapOf(
                        "intent" to intentSummary(winner.intent),
                        "result" to if (applied != null) "applied" else "skipped",
                        "speakText" to (applied ?: ""),
                    ),
                )
                applied?.let { text ->
                    onVehicleApplied()
                    speakViaTts(text)
                }
            }
            // 全败：播报兜底话术（按钮录音模式下需要明确反馈；2026-08-15 起同样走网络 TTS，
            // 不再用系统 TTS；决策日志已记录失败原因 cloud_timeout_use_local / both_failed 等）
            is RaceWinner.Failed -> {
                telemetry.record(TelemetryStages.EXECUTE, "warn", mapOf("result" to "failed"))
                speakViaTts(FALLBACK_PHRASE)
            }
            // B2：非最新轮语义被拦截——不播报不执行（轮照常收包，事件已含 lost 原因）
            is RaceWinner.Intercepted -> Unit
        }
        // B5：最终语义到达（任一收敛结果）→ 清除"处理中"占位状态
        setCloudPending(false)
        // T6：每轮结束收包（事件已按当前 utterance 聚合完毕）
        telemetry.end(currentUtteranceId)
    }

    /**
     * 云端回复路由（Task 61 + A3 TTS 解耦）：云端胜出时把语义结果携带的识别文本写进
     * 识别区（reply.asrText 非空才写，本地胜出/未携带时不覆盖本地识别文本）。
     * 之后按 kind 分发（v1.1 语义——回复不带音频，播报走独立 tts_request）：
     *  - Audio → 播放（协议层防御保留：旧服务端/兼容下行）；
     *  - Text → 按文本请求 TTS，失败/超时静默（记失败事件）；
     *  - Action → 执行 intent + 按 speakText 请求 TTS（失败同上）。
     */
    private fun routeCloudReply(reply: Reply) {
        if (reply.asrText.isNotBlank()) onLocalRecognized(reply.asrText)
        when (reply) {
            is AudioReply -> {
                player.play(reply)
                reply.intent?.let(::applyAndNotify)
            }
            is StreamingAudioReply -> scope.launch {
                player.playStream(reply)
                val end = reply.completion.await()
                if (end.asrText.isNotBlank()) onLocalRecognized(end.asrText)
                end.intent?.let(::applyAndNotify)
            }
            is TextReply -> speakViaTts(reply.text)
            is ActionReply -> {
                applyAndNotify(reply.intent)
                speakViaTts(reply.speakText)
            }
        }
    }

    /**
     * 统一网络 TTS 播报（A3 + 2026-08-15：所有路径共用，不用系统 TTS）：后台请求服务端
     * 合成音频 → 播放；失败/超时（null）静默，记 tts_play_end 失败事件（不静默到无痕）。
     * T7 插桩：tts_request（合成请求）+ tts_play（network 播放/失败；播放事件由 TtsPlayer
     * 经 [onTtsPlayEvent] 上报）。
     */
    private fun speakViaTts(text: String) {
        if (text.isBlank()) return
        // B4 需求 1：tts 播报请求（端侧发出播报请求）→ tts_play_request
        telemetry.record(TelemetryStages.TTS_PLAY_REQUEST, "info", mapOf("text" to text))
        scope.launch {
            // 架构变更（缓存移回端侧）：先查端侧缓存（tts_cache_check + 命中/未命中），
            // 命中直接播（不请求服务器）；未命中走网络合成，回传写缓存再播
            val cached = ttsCache.get(text)
            if (cached != null) {
                player.play(AudioReply(mime = "audio/wav", data = cached, speakText = text))
            } else {
                tts.request(text)?.let {
                    ttsCache.put(text, it.data) // 网络合成音频写缓存（下次同文本直接命中）
                    player.play(it)
                } ?: recordTtsPlayFailed()
            }
        }
    }

    /**
     * 意图执行（spec §4.2 起按域分发）：navigation 域 → [NavigationExecutor] 拉起高德 App
     * （成功记 applied，未安装/失败记 skipped）；其余 → vehicle.apply（apply 成功即非未知
     * 意图，通知应用层刷新车辆面板快照）。两路均记 T7 execute 事件。
     */
    private fun applyAndNotify(intent: Intent) {
        val applied = if (intent.domain == NavigationExecutor.DOMAIN_NAVIGATION) {
            navigation?.execute(intent) ?: false
        } else {
            vehicle.apply(intent) != null
        }
        telemetry.record(
            TelemetryStages.EXECUTE,
            "info",
            mapOf("intent" to intentSummary(intent), "result" to if (applied) "applied" else "skipped"),
        )
        if (applied && intent.domain != NavigationExecutor.DOMAIN_NAVIGATION) {
            onVehicleApplied()
        }
    }

    /**
     * B4 tts_play_end：网络 TTS 合成失败/超时（不再有系统 TTS 兜底，2026-08-15）。
     * 用 [playUtteranceId] 快照走 [TelemetryClient.recordFor]（T7 评审 C1）：launch 内
     * 的失败回调晚于 end() 收包，plain record 会丢事件或并进下一轮；轮已关闭 →
     * 单事件直传 /events。
     */
    private fun recordTtsPlayFailed() {
        telemetry.recordFor(
            playUtteranceId,
            TelemetryStages.TTS_PLAY_END,
            "error",
            mapOf("source" to "network", "result" to "failed"),
        )
    }

    /** T7：意图摘要（数据平台 execute 事件的 intent 字段，与本地 NLU 日志同格式）。 */
    private fun intentSummary(intent: Intent): String = "${intent.domain}/${intent.intent}"

    companion object {
        private const val TAG = "VoiceEngine"

        /** 本地兜底超时（spec §5.1）：云端超时后等本地 10s，仍无结果 → 全败。 */
        private const val LOCAL_FALLBACK_MS = 10_000L

        /** 全败兜底话术（按钮录音模式：双路都失败时播报，不再静默）。 */
        private const val FALLBACK_PHRASE = "网络开小差了，请稍后再试"

        /** 弱网调试 hook 的云端人为延迟（晚于 cloudWaitMs 即本地赢）。 */
        private const val WEAK_NETWORK_DELAY_MS = 3_000L

        /**
         * 生产装配：
         *  - 本地链：`local.asr=iflytek.offline` → [IflytekOfflineCommandAsrStage]
         *    （SDK 未配置/授权未就绪抛 NOT_CONFIGURED → Log.w 后本次降级
         *    [FakeCommandAsrProvider]）；`iflytek.fake-cmd`（或未识别值）→ 直接 fake。
         *    之后 [RuleNluProvider.understand]；任何 SDK 异常 → [Intent.unknown]("vehicle")，
         *    本地链绝不抛出。
         *  - 云端链：[GatewayCloudRunner]（GatewayClient + 事件桥，决策事件透传 sink）。
         *  - 网络检查：ConnectivityManager active network != null，可注入。
         */
        fun create(
            cfg: DemoConfig,
            context: Context,
            networkAvailable: () -> Boolean = { context.hasActiveNetwork() },
            sink: DecisionSink,
            player: AudioPlayer,
            vehicle: MockVehicleState,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            onVehicleApplied: () -> Unit = {},
            onLocalRecognized: (String?) -> Unit = {},
            /** 导航执行器（spec §4.2）：null → 导航意图记 skipped。 */
            navigation: NavigationExecutor? = null,
            /** B5：云端 LLM 处理中占位回调（收到 pending 帧 → true；最终语义/新一轮 → false）。 */
            onCloudPending: (Boolean) -> Unit = {},
        ): VoiceEngine {
            // 时钟同步：telemetry 先于 cloudRunner 创建，offset 提供者延迟绑定（仿
            // engineRef 模式；AtomicReference 保证跨线程可见性——握手在线程池，打戳在 IO）
            val clockOffsetProvider = AtomicReference<() -> Long>({ 0L })
            // T6 遥测装配：telemetry 段未配置（enabled 缺省 false）→ enabled=false 全 no-op 实例；
            // clock 注入偏移（ready.serverTime 握手估算），设备端事件统一换算服务器时钟
            val telemetry = TelemetryClient(
                okHttp = OkHttpClient(),
                baseUrl = cfg.cloud.telemetry?.url ?: telemetryBaseUrl(cfg.cloud.gatewayUrl),
                deviceId = cfg.cloud.deviceId,
                scope = scope,
                enabled = cfg.cloud.telemetry?.enabled ?: false,
                clock = { System.currentTimeMillis() + clockOffsetProvider.get().invoke() },
            )
            // T6 决策插桩：sink 收到端云两端的决策事件——on-device → device_arbiter，cloud → cloud_arbiter。
            // 在装配点包裹，仲裁器 / 会话 / 网关桥三条来源的决策都经此记录
            val telemetrySink = DecisionSink { entry ->
                telemetry.record(
                    if (entry.arbiter == "on-device") {
                        TelemetryStages.DEVICE_ARBITER
                    } else {
                        TelemetryStages.CLOUD_ARBITER
                    },
                    "info",
                    mapOf("route" to entry.route, "reason" to entry.reason),
                )
                sink.onDecision(entry)
            }
            // B5：云端 pending 占位信号（LLM 处理中）——桥收到 pending 帧 → 此通道 →
            // 端侧仲裁器阶段 1 窗口延长（pendingWaitMs）。BUFFERED：pending 帧到达时若
            // 仲裁不在等待（如阶段 2 / 轮已结束），信号进缓冲区无接收方也绝不挂起发送方。
            val pendingSignals = Channel<Unit>(Channel.BUFFERED)
            val cloudRunner = GatewayCloudRunner(cfg.cloud, telemetrySink, scope, pendingSignals)
            // 时钟同步：握手估算的时钟偏移（ready.serverTime）注入 telemetry 打戳
            clockOffsetProvider.set(cloudRunner::clockOffsetMs)
            // TTS 缓存（架构变更：缓存从服务器移回端侧）：filesDir 持久目录（重启后仍命中）
            // + 事件桥（T7 recordFor 通道：speakViaTts 的 launch 晚于收口，current 已 null，
            // record 会静默丢弃；用 playUtteranceId 快照走 recordFor——轮已关闭 → /events 直传）
            var ttsCacheEngineRef: VoiceEngine? = null
            val ttsCache = TtsCache(
                File(context.filesDir, "tts_cache"),
                onEvent = { stage, level, payload ->
                    val engine = ttsCacheEngineRef ?: return@TtsCache
                    telemetry.recordFor(engine.playUtteranceId, stage, level, payload)
                },
            )
            // Task 34：模式切换/销毁时释放离线 stage（unLoadData + engineUnInit）——
            // AiHelper 同能力 ID 单例，旧实例 FSA 残留会导致新实例 loadData 报 15114
            val offlineStageRef = AtomicReference<IflytekOfflineCommandAsrStage?>(null)
            // T7：仲裁器 utteranceId provider 延迟读装配后 engine 的会话成员（session 在
            // VoiceEngine init 里由本 arbiter 装配，构造时序上后者先于前者，用可空引用桥接）
            var engineRef: VoiceEngine? = null
            val engine = VoiceEngine(
                cfg = cfg,
                arbiter = OnDeviceRaceArbiter(
                    cloudWaitMs = cfg.cloud.waitMs,
                    localFallbackMs = LOCAL_FALLBACK_MS,
                    clock = System::currentTimeMillis,
                    sink = telemetrySink,
                    // T7：on-device 决策日志携带本轮真实 utteranceId（vad start 写入会话）
                    utteranceId = { engineRef?.session?.currentUtteranceId ?: "" },
                    // B2：仲裁过程事件（收到/胜出/失败）→ device_arbiter_received/won/lost 插桩
                    // B5：pending 占位 → device_arbiter_pending 插桩
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
                            is OnDeviceArbiterEvent.Pending -> telemetry.record(
                                TelemetryStages.DEVICE_ARBITER_PENDING,
                                "info",
                                mapOf("route" to event.route, "reason" to "llm_pending"),
                            )
                        }
                    },
                    // B5：pending 信号 → 阶段 1 窗口延长（默认 50s，覆盖 Omni 45s safety）
                    pending = pendingSignals,
                ),
                sink = telemetrySink,
                telemetry = telemetry,
                networkAvailable = networkAvailable,
                local = buildLocalChain(cfg, context, scope, onLocalRecognized, offlineStageRef, telemetry),
                cloud = cloudRunner,
                tts = cloudRunner, // TTS 解耦：播报走独立 tts_request/tts_response（同一网关连接）
                ttsCache = ttsCache, // 缓存移回端侧：查缓存命中直接播，未命中才走网络
                player = player,
                vehicle = vehicle,
                navigation = navigation,
                scope = scope,
                onVehicleApplied = onVehicleApplied,
                onLocalRecognized = onLocalRecognized,
                onClose = {
                    cloudRunner.close() // Task 21：模式切换时断开网关
                    offlineStageRef.get()?.release() // Task 34：释放离线 stage，防新实例 FSA 残留（15114）
                },
                onCloudPending = onCloudPending,
            )
            engineRef = engine
            ttsCacheEngineRef = engine // TTS 缓存事件桥：recordFor 需要 playUtteranceId 快照
            // T7 评审 C1 注：onTtsPlayEvent 的网络事件绑定已在 VoiceEngine init 完成
            // （telemetry 为构造参数，构造即绑定），此处无需再装配
            // ready 后故障才 latch（连接前故障不 latch，Task 15 M1 裁定）
            cloudRunner.onCloudUnavailable = { engine.session.onCloudUnavailable() }
            // B5：收到 pending 帧 → 端侧"处理中…"UI 状态（清除由 onTurnResult / onListeningStart 收口）
            cloudRunner.onPendingReceived = { engine.setCloudPending(true) }
            // T6：云端链发帧时读取引擎当前话语的 utteranceId
            cloudRunner.utteranceIdProvider = { engine.currentUtteranceId }
            // T6 评审 C1：ready 的 sessionId 转发给遥测（与 utteranceIdProvider 同款绑定时机）
            cloudRunner.onReadySessionId = telemetry::onSessionId
            return engine
        }

        /**
         * 遥测 HTTP 基址推导（T6）：显式 telemetry.url 未配时由网关地址推导——
         * `ws://h:p/ws` → `http://h:p`；已是 http 前缀时仅去掉尾部 `/ws` 路径。
         */
        private fun telemetryBaseUrl(gatewayUrl: String): String =
            when {
                gatewayUrl.startsWith("ws://") ->
                    "http://" + gatewayUrl.removePrefix("ws://").removeSuffix("/ws")
                else -> gatewayUrl.removeSuffix("/ws")
            }

        /**
         * 本地链装配：命令词 ASR（真实/降级 fake）→ 规则 NLU；绝不抛出。
         *
         * Task 34 接线：`local.asr=iflytek.offline` 时构造真实 [IflytekOfflineCommandAsrStage]
         * 并在引擎后台协程里 [IflytekOfflineCommandAsrStage.init]（首次联网授权 + 引擎初始化 +
         * 命令词加载）。init 阻塞最长 20s（授权超时），放后台不卡装配；就绪前 recognize 抛
         * NOT_CONFIGURED → 本次降级 [FakeCommandAsrProvider]（runbook §5.1），授权完成后自动
         * 切换真实引擎，无需重启。
         */
        private fun buildLocalChain(
            cfg: DemoConfig,
            context: Context,
            scope: CoroutineScope,
            onLocalRecognized: (String?) -> Unit,
            /** 装载离线 stage 引用，供 [VoiceEngine.close] 释放（模式切换防 15114 残留）。 */
            offlineStageRef: AtomicReference<IflytekOfflineCommandAsrStage?>,
            /** T7 插桩：local_asr 事件（识别文本/意图/耗时；enabled=false 时 no-op）。 */
            telemetry: TelemetryClient,
        ): LocalChainRunner {
            val offlineStage = if (cfg.local.asr == "iflytek.offline") {
                // 凭据来自 local.properties（BuildConfig 注入，不入库）
                IflytekOfflineCommandAsrStage(
                    appId = BuildConfig.XFYUN_APPID,
                    apiKey = BuildConfig.XFYUN_API_KEY,
                    apiSecret = BuildConfig.XFYUN_API_SECRET,
                ).also { offlineStageRef.set(it) }
            } else {
                null
            }
            if (offlineStage != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        offlineStage.init(context)
                        Log.i(TAG, "讯飞离线命令词初始化完成（授权通过）")
                    } catch (t: Throwable) {
                        // 授权失败/资源缺失等：本次及后续识别保持 fake 降级（§5.1 预期内）
                        Log.w(TAG, "讯飞离线命令词初始化失败，保持 FakeCommandAsrProvider 降级", t)
                    }
                }
            }
            return LocalChainRunner { segment ->
                val startMs = System.currentTimeMillis()
                try {
                    val command = when {
                        offlineStage != null -> try {
                            offlineStage.recognize(segment)
                        } catch (e: IllegalStateException) {
                            if (e.message?.contains(IflytekOfflineCommandAsrStage.NOT_CONFIGURED_MSG) == true) {
                                Log.w(TAG, "讯飞离线命令词未就绪（授权中/失败），本次降级 FakeCommandAsrProvider", e)
                                FakeCommandAsrProvider.recognize(segment)
                            } else {
                                throw e
                            }
                        }
                        else -> FakeCommandAsrProvider.recognize(segment) // local.asr=iflytek.fake-cmd
                    }
                    Log.i(TAG, "本地 ASR 识别文本: ${command ?: "(无结果)"}")
                    onLocalRecognized(command)
                    val intent = RuleNluProvider.understand(command ?: "")
                    Log.i(TAG, "本地 NLU 意图: ${intent.domain}/${intent.intent} (${intent.slots})")
                    // T7 local_asr：识别文本/意图/耗时（unknown 意图同样落库，供拒识分析）
                    telemetry.record(
                        TelemetryStages.LOCAL_ASR,
                        "info",
                        mapOf(
                            "text" to (command ?: ""),
                            "intent" to "${intent.domain}/${intent.intent}",
                            "durationMs" to (System.currentTimeMillis() - startMs),
                        ),
                    )
                    intent
                } catch (t: Throwable) {
                    // 本地链绝不抛出：任何 SDK 异常 → unknown 意图（不执行、不播报）
                    Log.w(TAG, "本地链路异常，降级 unknown 意图", t)
                    telemetry.record(
                        TelemetryStages.LOCAL_ASR,
                        "warn",
                        mapOf(
                            "intent" to "unknown/vehicle",
                            "durationMs" to (System.currentTimeMillis() - startMs),
                        ),
                    )
                    Intent.unknown("vehicle")
                }
            }
        }

        /** ConnectivityManager 网络检查：active network 非空即认为网络可用。 */
        private fun Context.hasActiveNetwork(): Boolean {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null) {
                Log.w(TAG, "hasActiveNetwork: ConnectivityManager 服务不可用")
                return false
            }
            val active = cm.activeNetwork
            Log.d(TAG, "hasActiveNetwork: active=$active all=${cm.allNetworks.toList()}")
            return active != null
        }
    }
}

/**
 * 云端链路实现（GatewayClient 装配）：首次/故障后重连 → 分块发送 PCM（16KB/帧）→
 * 收 reply。网关 decision 事件（type=decision）透传进 [DecisionSink]（UI 决策日志）。
 *
 * 故障语义（Task 15 M1 裁定）：ready 前失败（连接/重试耗尽）→ 抛 [CloudUnavailableException]
 * 且不 latch；ready 后失败（发送中断/收包错误）→ 先调 [onCloudUnavailable] latch
 * 后续话语只跑本地，再抛 [CloudUnavailableException]。两种故障本轮都回落到本地链。
 */
private class GatewayCloudRunner(
    private val cfg: CloudConfig,
    private val sink: DecisionSink,
    scope: CoroutineScope,
    /** B5：云端 pending 占位信号（LLM 处理中）→ 透传给桥，桥对账后发出。 */
    private val pendingSignals: SendChannel<Unit> = Channel(Channel.BUFFERED),
) : CloudRunner, TtsRequester {

    private val client = GatewayClient(
        url = cfg.gatewayUrl,
        okHttp = OkHttpClient(),
        // M5 鉴权：hello 注入设备凭据（auth-enabled 网关必填；未配置则保持老 hello）
        deviceId = cfg.deviceId,
        authToken = cfg.authToken,
    )
    private val bridge = GatewayBridge(client, sink, scope, pendingSignals) { onPendingReceived() }

    /** 是否已收到 ready（含 sessionId）——据此区分 ready 前/后故障。 */
    @Volatile
    private var readyReceived = false

    @Volatile
    private var sessionId = ""

    /** 由 [VoiceEngine.create] 在 engine 装配完成后绑定到 session.onCloudUnavailable()。 */
    lateinit var onCloudUnavailable: () -> Unit

    /**
     * B5：收到云端 pending 帧的回调（由 [VoiceEngine.create] 装配后绑定 →
     * engine.setCloudPending(true)，UI 显示"处理中…"）。清除由 onTurnResult /
     * onListeningStart 收口。
     */
    @Volatile
    var onPendingReceived: () -> Unit = {}

    /**
     * 当前话语 utteranceId 读取器（T6）：由 [VoiceEngine.create] 在 engine 装配完成后
     * 绑定到 `engine.currentUtteranceId`；空串时发帧不携带 utteranceId（服务端视为未提供）。
     */
    @Volatile
    var utteranceIdProvider: () -> String = { "" }

    /**
     * ready 回执的 sessionId 回调（T6 评审 C1）：由 [VoiceEngine.create] 绑定到
     * `telemetry::onSessionId`——round body 按会话关联，缺此转发服务端
     * recordDeviceRound 会把 session_id="" 落库，轮次无法按会话查询。
     */
    @Volatile
    var onReadySessionId: (String) -> Unit = {}

    /** 时钟同步：委托网关客户端的时钟偏移（ready.serverTime 握手估算，每次握手刷新）。 */
    fun clockOffsetMs(): Long = client.clockOffsetMs()

    /** 释放：断开网关连接（幂等）；引擎 close() 时调用（Task 21 模式切换）。 */
    fun close() {
        client.disconnect()
    }

    override suspend fun run(segment: ByteArray): Reply {
        // 每轮话语唯一 ID：先于发送注册，reply/error 凭它关联到本话语（丢弃上一轮迟到的消息）
        val segmentId = UUID.randomUUID().toString()
        val replySlot = bridge.newReplySlot(segmentId)
        try {
            if (!readyReceived) {
                client.connect() // 失败（重试耗尽）抛 GatewayException
                // connect 返回后 ready 事件已进流（replay=1），补拉出 sessionId；
                // 桥接收集器可能尚未处理该事件，这里直接读流，避免时序竞态
                val ready = client.messages.first { it.type == "ready" }
                sessionId = ready.payload.get("sessionId")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: throw GatewayException("ready 事件缺少 sessionId")
                readyReceived = true
                // T6 评审 C1：ready 的 sessionId 转发给遥测（round body 按会话关联）
                onReadySessionId(sessionId)
            }
            // T6：utteranceId 空串不发送（服务端视为未提供，保持旧协议形态）
            client.sendAudioStart(sessionId, segmentId, utteranceIdProvider().takeIf { it.isNotBlank() })
            var offset = 0
            while (offset < segment.size) {
                val end = minOf(offset + CLOUD_CHUNK_BYTES, segment.size)
                client.sendAudioChunk(segment.copyOfRange(offset, end))
                offset = end
            }
            client.sendAudioEnd(sessionId)
            return replySlot.await()
        } catch (e: GatewayException) {
            val wasReady = readyReceived
            readyReceived = false
            sessionId = ""
            client.disconnect() // 断开失效连接，下次话语可干净重连
            if (wasReady) onCloudUnavailable()
            throw CloudUnavailableException("云端链路故障：${e.message}", e)
        } catch (e: CancellationException) {
            runCatching { client.sendCancelTurn(segmentId) }
            bridge.cancelStream(segmentId)
            throw e
        } finally {
            bridge.clearReplySlot(replySlot)
        }
    }

    /**
     * 独立 TTS 播报（A3，TTS 解耦）：发 tts_request 等 tts_response，5s 超时返回 null
     * （调用方静默处理并记失败事件，2026-08-15 起不再有系统 TTS 兜底），**不重试**。
     * ready 未建立（会话从未连上）直接 null。
     * 与 [run] 的 reply 槽互不干扰（bridge 独立 tts 槽，各自按 segmentId 对账）。
     */
    override suspend fun request(text: String): AudioReply? {
        if (!readyReceived) return null
        val ttsId = UUID.randomUUID().toString()
        val ttsSlot = bridge.newTtsSlot(ttsId)
        return try {
            // T6：关联当前话语 utteranceId（空串不发送，保持旧协议形态）
            client.sendTtsRequest(text, ttsId, utteranceIdProvider().takeIf { it.isNotBlank() })
            withTimeoutOrNull(TTS_TIMEOUT_MS) { ttsSlot.await() }
        } catch (e: GatewayException) {
            null // 连接未就绪等发送失败：本次播报直接兜底
        } finally {
            bridge.clearTtsSlot(ttsSlot)
        }
    }
}

/** 独立 TTS 请求超时（A3）：超过即放弃合成音频，本次播报静默（记失败事件）。 */
private const val TTS_TIMEOUT_MS = 5_000L

/**
 * 网关事件桥：构造时一次性订阅 [GatewayClient.messages]（SharedFlow replay=1），
 * 把 decision 事件透传进 sink、reply 事件投递到当前话语的等待槽、
 * error 事件让等待中的回复立即失败（提前暴露故障，不必等仲裁超时）。
 *
 * 消息关联（protocol.md §3.2）：reply / error 按 payload 中的 `segmentId` 与当前话语的等待槽对账——
 * 携带的 segmentId 与本轮不一致（上一轮迟到的消息）→ 丢弃并 Log.d；未携带 segmentId（服务端
 * 合成的传输错误 / 旧版服务端）→ 无法对账，按当前槽处理（保留快速失败语义）。同一时刻至多一个
 * 等待槽，跨轮消息天然按 segmentId 隔离。
 *
 * TTS 槽（A3）：tts_response 走独立的 [pendingTts] 槽，与 [pendingReply] 互不干扰
 * （tts 播报与话语回复是两条独立时间线），各自按 segmentId 对账。
 */
internal class GatewayBridge(
    private val client: GatewayClient,
    private val sink: DecisionSink,
    scope: CoroutineScope,
    /** B5：云端 pending 占位信号（LLM 处理中）→ 端侧仲裁器阶段 1 窗口延长。 */
    private val pendingSignals: SendChannel<Unit> = Channel(Channel.BUFFERED),
    /** B5：pending 帧已对账通过的回调（装配方绑定 → UI"处理中…"状态）。 */
    private val onPendingReceived: () -> Unit = {},
) {

    private class PendingSlot<T>(val segmentId: String, val deferred: CompletableDeferred<T>)
    private class ActiveStream(
        val segmentId: String,
        val chunks: Channel<ByteArray>,
        val completion: CompletableDeferred<AudioStreamEnd>,
    )

    private val pendingReply = AtomicReference<PendingSlot<Reply>?>(null)
    private val pendingTts = AtomicReference<PendingSlot<AudioReply>?>(null)
    private val activeStream = AtomicReference<ActiveStream?>(null)

    init {
        scope.launch {
            client.messages.collect { msg -> handle(msg) }
        }
    }

    /** 注册当前话语的回复等待槽（先于发送注册，避免 reply 先到被丢）。 */
    fun newReplySlot(segmentId: String): CompletableDeferred<Reply> {
        val deferred = CompletableDeferred<Reply>()
        pendingReply.set(PendingSlot<Reply>(segmentId, deferred))
        return deferred
    }

    fun clearReplySlot(deferred: CompletableDeferred<Reply>) {
        pendingReply.get()?.takeIf { it.deferred === deferred }?.let { pendingReply.compareAndSet(it, null) }
    }

    fun cancelStream(segmentId: String) {
        val stream = activeStream.get() ?: return
        if (stream.segmentId != segmentId || !activeStream.compareAndSet(stream, null)) return
        val error = CancellationException("audio stream cancelled: $segmentId")
        stream.chunks.close(error)
        stream.completion.completeExceptionally(error)
    }

    /** 注册独立 TTS 播报槽（tts_response 对账用，与 reply 槽隔离）。 */
    fun newTtsSlot(segmentId: String): CompletableDeferred<AudioReply> {
        val deferred = CompletableDeferred<AudioReply>()
        pendingTts.set(PendingSlot<AudioReply>(segmentId, deferred))
        return deferred
    }

    fun clearTtsSlot(deferred: CompletableDeferred<AudioReply>) {
        pendingTts.get()?.takeIf { it.deferred === deferred }?.let { pendingTts.compareAndSet(it, null) }
    }

    private fun handle(msg: GatewayMessage) {
        when (msg.type) {
            "decision" -> parseDecision(msg.payload)?.let(sink::onDecision)
            "reply" -> {
                val slot = pendingReply.get() ?: return
                if (!isForCurrentUtterance(msg.payload, slot)) return
                client.parseReply(msg.payload)?.let { slot.deferred.complete(it) }
            }
            "audio_reply_start" -> {
                val slot = pendingReply.get() ?: return
                if (!isForCurrentUtterance(msg.payload, slot)) return
                val chunks = Channel<ByteArray>(Channel.UNLIMITED)
                val completion = CompletableDeferred<AudioStreamEnd>()
                val reply = client.parseAudioStreamStart(msg.payload, chunks, completion) ?: return
                val stream = ActiveStream(slot.segmentId, chunks, completion)
                activeStream.getAndSet(stream)?.let { previous ->
                    previous.chunks.close(CancellationException("replaced by a newer stream"))
                    previous.completion.completeExceptionally(
                        CancellationException("replaced by a newer stream"),
                    )
                }
                slot.deferred.complete(reply)
            }
            "audio_reply_chunk" -> {
                val stream = activeStream.get() ?: return
                msg.binary?.let { bytes -> stream.chunks.trySend(bytes) }
            }
            "audio_reply_end" -> {
                val stream = activeStream.get() ?: return
                val msgSegmentId = msg.payload.get("segmentId")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: return
                if (msgSegmentId != stream.segmentId) return
                if (activeStream.compareAndSet(stream, null)) {
                    stream.completion.complete(client.parseAudioStreamEnd(msg.payload))
                    stream.chunks.close()
                }
            }
            "tts_response" -> {
                val slot = pendingTts.get() ?: return
                if (!isForCurrentUtterance(msg.payload, slot)) return
                client.parseTtsResponse(msg.payload)?.let { slot.deferred.complete(it) }
            }
            "error" -> {
                val code = msg.payload.get("code")?.takeIf { it.isJsonPrimitive }?.asString ?: "UNKNOWN"
                val slot = pendingReply.get()
                if (slot != null && isForCurrentUtterance(msg.payload, slot)) {
                    slot.deferred.completeExceptionally(GatewayException("网关传输错误：$code"))
                }
                activeStream.getAndSet(null)?.let { stream ->
                    val error = GatewayException("网关传输错误：$code")
                    stream.chunks.close(error)
                    stream.completion.completeExceptionally(error)
                }
            }
            "pending" -> {
                // B5：pending 占位（LLM 处理中，协议 §4.8）——独立于 reply kind 的 S→C
                // 消息：不能走 reply（会 complete replySlot 吞掉 final），只发信号改 UI
                // 状态 + 延长仲裁等待窗口。对账同 reply：segmentId 不一致 → 他轮迟到丢弃；
                // 无槽（无话语在途）→ 丢弃。trySend 幂等缓冲（BUFFERED 通道不挂起）。
                val slot = pendingReply.get() ?: return
                if (!isForCurrentUtterance(msg.payload, slot)) return
                pendingSignals.trySend(Unit)
                onPendingReceived()
            }
            else -> Unit // ready / asr_partial / bye 当前不消费
        }
    }

    /**
     * 按 segmentId 对账（protocol.md §3.2）：消息携带的 segmentId 与当前话语不一致 → 他轮迟到的
     * 消息，丢弃（Log.d）；未携带（服务端合成错误 / 旧版服务端）→ 无从对账，按当前话语处理。
     */
    private fun isForCurrentUtterance(payload: JsonObject, slot: PendingSlot<*>): Boolean {
        val msgSegmentId = payload.get("segmentId")?.takeIf { it.isJsonPrimitive }?.asString
        if (msgSegmentId == null) return true
        if (msgSegmentId != slot.segmentId) {
            Log.d(GATEWAY_BRIDGE_TAG, "丢弃不属于当前话语的消息（segmentId=$msgSegmentId, 期望=${slot.segmentId}）")
            return false
        }
        return true
    }

    /** 网关 decision 事件 → DecisionEntry（字段缺失则忽略该条，防御）。 */
    private fun parseDecision(payload: JsonObject): DecisionEntry? {
        val arbiter = payload.get("arbiter")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val route = payload.get("route")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val reason = payload.get("reason")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val utteranceId = payload.get("utteranceId")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val timestampMs = payload.get("timestampMs")?.takeIf { it.isJsonPrimitive }?.asLong
            ?: System.currentTimeMillis()
        return DecisionEntry(arbiter, route, reason, utteranceId, timestampMs)
    }
}

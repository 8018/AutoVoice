package com.autovoice.app

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.autovoice.adapteriflytek.FakeCommandAsrProvider
import com.autovoice.adapteriflytek.IflytekOfflineCommandAsrStage
import com.autovoice.adapteriflytek.RuleNluProvider
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
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.arbiter.OnDeviceRaceArbiter
import com.autovoice.voicecore.arbiter.RaceWinner
import com.autovoice.voicecore.session.CloudRunner
import com.autovoice.voicecore.session.CloudUnavailableException
import com.autovoice.voicecore.session.LocalChainRunner
import com.autovoice.voicecore.session.ResultListener
import com.autovoice.voicecore.session.VoiceSession
import com.google.gson.JsonObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** 云端音频回复播放出口（应用层实现：TtsPlayer）。JVM 测试可注入 fake。 */
fun interface AudioPlayer {
    fun play(reply: AudioReply)
}

/** 本地播报出口（应用层实现：SystemTtsFallback）。JVM 测试可注入 fake。 */
fun interface TextSpeaker {
    fun speak(text: String)
}

/** 云端音频分块大小（gateway 协议 16KB/帧）。 */
private const val CLOUD_CHUNK_BYTES = 16_384

/** 网关事件桥日志 TAG。 */
private const val GATEWAY_BRIDGE_TAG = "GatewayBridge"

/**
 * 端侧全局装配点（Task 20）：双链路竞速引擎 + 播报/执行路由。
 *
 * 持有装配好的 [VoiceSession]（本地链 + 云端链 + [OnDeviceRaceArbiter]，见 voice-core
 * §5.1 编排语义）与三个出口：[player]（云端音频播放）、[speaker]（本地播报）、
 * [vehicle]（车控执行）。结果路由（Task 20 交付物）：
 *  - [RaceWinner.Cloud]：AudioReply → 播放 + 附 intent 执行；TextReply → 播报；
 *    ActionReply → 执行 intent + 播报自带 speakText；
 *  - [RaceWinner.Local]：`vehicle.apply(intent)` 成功 → 播报其返回文本（未知意图不播报）；
 *  - [RaceWinner.Failed]：播报兜底话术 [FALLBACK_PHRASE]。
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
    private val networkAvailable: () -> Boolean,
    local: LocalChainRunner,
    cloud: CloudRunner,
    private val player: AudioPlayer,
    private val speaker: TextSpeaker,
    val vehicle: MockVehicleState,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onVehicleApplied: () -> Unit = {},
    /** 本地 ASR 识别文本回调（Task 34：UI 显示识别结果，未检出时为 null）。 */
    private val onLocalRecognized: (String?) -> Unit = {},
    private val debugBuild: Boolean = BuildConfig.DEBUG,
    /** 释放钩子（生产装配注册网关断开；Task 21 模式切换）。 */
    private val onClose: () -> Unit = {},
) {

    /** 弱网调试 hook（调试构建的 UI 开关）：true 且 [debugBuild] 时云端链人为延迟 [WEAK_NETWORK_DELAY_MS]。 */
    @Volatile
    var weakNetwork: Boolean = false

    /** 装配好的会话：状态机 + 双路由竞速编排。 */
    val session: VoiceSession

    init {
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
     * 录音开始：网络可用则重新启用云端路由（断网恢复场景），否则立即挂起云端
     * （本轮起只跑本地链，reason `cloud_unreachable`），再进入 LISTENING。
     */
    fun onListeningStart() {
        if (networkAvailable()) session.onCloudAvailable() else session.onCloudUnavailable()
        session.onListeningStart()
    }

    /** 话语边界（VAD end）：装配好的段 PCM 交给会话并发竞速。 */
    fun onVadSegment(segment: ByteArray) = session.onVadSegment(segment)

    /** 录音中止（用户抬手/放弃）：回 IDLE；进行中的竞速不受影响（会话防御）。 */
    fun onListeningStop() {
        session.onListeningStop()
    }

    // ------------------------------------------------------------------ 结果路由

    private fun onTurnResult(winner: RaceWinner) {
        when (winner) {
            is RaceWinner.Cloud -> routeCloudReply(winner.reply)
            is RaceWinner.Local -> vehicle.apply(winner.intent)?.let { text ->
                onVehicleApplied()
                speaker.speak(text)
            }
            is RaceWinner.Failed -> speaker.speak(FALLBACK_PHRASE)
        }
    }

    /** 云端回复路由：Audio → 播放 + 可选执行；Text → 播报；Action → 执行 + 播报。 */
    private fun routeCloudReply(reply: Reply) {
        when (reply) {
            is AudioReply -> {
                player.play(reply)
                reply.intent?.let(::applyAndNotify)
            }
            is TextReply -> speaker.speak(reply.text)
            is ActionReply -> {
                applyAndNotify(reply.intent)
                speaker.speak(reply.speakText)
            }
        }
    }

    /** 车控执行：apply 成功（非未知意图）后通知应用层刷新车辆面板快照。 */
    private fun applyAndNotify(intent: Intent) {
        if (vehicle.apply(intent) != null) onVehicleApplied()
    }

    companion object {
        private const val TAG = "VoiceEngine"

        /** 本地兜底超时（spec §5.1）：云端超时后等本地 10s，仍无结果 → 全败。 */
        private const val LOCAL_FALLBACK_MS = 10_000L

        /** 弱网调试 hook 的云端人为延迟（晚于 cloudWaitMs 即本地赢）。 */
        private const val WEAK_NETWORK_DELAY_MS = 3_000L

        /** 双败兜底话术（brief 明文）。 */
        const val FALLBACK_PHRASE = "网络开小差了，请稍后再试"

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
            speaker: TextSpeaker,
            vehicle: MockVehicleState,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            onVehicleApplied: () -> Unit = {},
            onLocalRecognized: (String?) -> Unit = {},
        ): VoiceEngine {
            val cloudRunner = GatewayCloudRunner(cfg.cloud, sink, scope)
            // Task 34：模式切换/销毁时释放离线 stage（unLoadData + engineUnInit）——
            // AiHelper 同能力 ID 单例，旧实例 FSA 残留会导致新实例 loadData 报 15114
            val offlineStageRef = AtomicReference<IflytekOfflineCommandAsrStage?>(null)
            val engine = VoiceEngine(
                cfg = cfg,
                arbiter = OnDeviceRaceArbiter(
                    cloudWaitMs = cfg.cloud.waitMs,
                    localFallbackMs = LOCAL_FALLBACK_MS,
                    clock = System::currentTimeMillis,
                    sink = sink,
                ),
                sink = sink,
                networkAvailable = networkAvailable,
                local = buildLocalChain(cfg, context, scope, onLocalRecognized, offlineStageRef),
                cloud = cloudRunner,
                player = player,
                speaker = speaker,
                vehicle = vehicle,
                scope = scope,
                onVehicleApplied = onVehicleApplied,
                onLocalRecognized = onLocalRecognized,
                onClose = {
                    cloudRunner.close() // Task 21：模式切换时断开网关
                    offlineStageRef.get()?.release() // Task 34：释放离线 stage，防新实例 FSA 残留（15114）
                },
            )
            // ready 后故障才 latch（连接前故障不 latch，Task 15 M1 裁定）
            cloudRunner.onCloudUnavailable = { engine.session.onCloudUnavailable() }
            return engine
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
                    intent
                } catch (t: Throwable) {
                    // 本地链绝不抛出：任何 SDK 异常 → unknown 意图（不执行、不播报）
                    Log.w(TAG, "本地链路异常，降级 unknown 意图", t)
                    Intent.unknown("vehicle")
                }
            }
        }

        /** ConnectivityManager 网络检查：active network 非空即认为网络可用。 */
        private fun Context.hasActiveNetwork(): Boolean {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            return cm.activeNetwork != null
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
) : CloudRunner {

    private val client = GatewayClient(url = cfg.gatewayUrl, okHttp = OkHttpClient())
    private val bridge = GatewayBridge(client, sink, scope)

    /** 是否已收到 ready（含 sessionId）——据此区分 ready 前/后故障。 */
    @Volatile
    private var readyReceived = false

    @Volatile
    private var sessionId = ""

    /** 由 [VoiceEngine.create] 在 engine 装配完成后绑定到 session.onCloudUnavailable()。 */
    lateinit var onCloudUnavailable: () -> Unit

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
            }
            client.sendAudioStart(sessionId, segmentId)
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
        } finally {
            bridge.clearReplySlot(replySlot)
        }
    }
}

/**
 * 网关事件桥：构造时一次性订阅 [GatewayClient.messages]（SharedFlow replay=1），
 * 把 decision 事件透传进 sink、reply 事件投递到当前话语的等待槽、
 * error 事件让等待中的回复立即失败（提前暴露故障，不必等仲裁超时）。
 *
 * 消息关联（protocol.md §3.2）：reply / error 按 payload 中的 `segmentId` 与当前话语的等待槽对账——
 * 携带的 segmentId 与本轮不一致（上一轮迟到的消息）→ 丢弃并 Log.d；未携带 segmentId（服务端
 * 合成的传输错误 / 旧版服务端）→ 无法对账，按当前槽处理（保留快速失败语义）。同一时刻至多一个
 * 等待槽，跨轮消息天然按 segmentId 隔离。
 */
internal class GatewayBridge(
    private val client: GatewayClient,
    private val sink: DecisionSink,
    scope: CoroutineScope,
) {

    private class PendingSlot(val segmentId: String, val deferred: CompletableDeferred<Reply>)

    private val pendingReply = AtomicReference<PendingSlot?>(null)

    init {
        scope.launch {
            client.messages.collect { msg -> handle(msg) }
        }
    }

    /** 注册当前话语的回复等待槽（先于发送注册，避免 reply 先到被丢）。 */
    fun newReplySlot(segmentId: String): CompletableDeferred<Reply> {
        val deferred = CompletableDeferred<Reply>()
        pendingReply.set(PendingSlot(segmentId, deferred))
        return deferred
    }

    fun clearReplySlot(deferred: CompletableDeferred<Reply>) {
        pendingReply.get()?.takeIf { it.deferred === deferred }?.let { pendingReply.compareAndSet(it, null) }
    }

    private fun handle(msg: GatewayMessage) {
        when (msg.type) {
            "decision" -> parseDecision(msg.payload)?.let(sink::onDecision)
            "reply" -> {
                val slot = pendingReply.get() ?: return
                if (!isForCurrentUtterance(msg.payload, slot)) return
                client.parseReply(msg.payload)?.let { slot.deferred.complete(it) }
            }
            "error" -> {
                val slot = pendingReply.get() ?: return
                if (!isForCurrentUtterance(msg.payload, slot)) return
                val code = msg.payload.get("code")?.takeIf { it.isJsonPrimitive }?.asString ?: "UNKNOWN"
                slot.deferred.completeExceptionally(GatewayException("网关传输错误：$code"))
            }
            else -> Unit // ready / asr_partial / bye 当前不消费
        }
    }

    /**
     * 按 segmentId 对账（protocol.md §3.2）：消息携带的 segmentId 与当前话语不一致 → 他轮迟到的
     * 消息，丢弃（Log.d）；未携带（服务端合成错误 / 旧版服务端）→ 无从对账，按当前话语处理。
     */
    private fun isForCurrentUtterance(payload: JsonObject, slot: PendingSlot): Boolean {
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

package com.autovoice.voicecore.session

import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.NluResult
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.arbiter.OnDeviceRaceArbiter
import com.autovoice.voicecore.arbiter.RaceWinner
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 端侧本地 NLU 链路由（spec §5.1 本地兜底链路）：话语段 → 语义候选。
 * ASR 在链内先独立输出到 UI；这里只把 NLU 结果交给仲裁器。
 */
fun interface LocalChainRunner {
    suspend fun run(segment: ByteArray): NluResult

    /** 独立 ASR 回调需要保留所属 capture/turn，避免迟到文本覆盖另一轮 UI。 */
    suspend fun run(segment: ByteArray, utteranceId: String): NluResult = run(segment)
}

/** 兼容只返回 Intent 的旧装配/测试；新 2C 适配器应直接返回带文本的 [NluResult]。 */
@Suppress("FunctionName")
fun LocalChainRunner(block: suspend (ByteArray) -> Intent): LocalChainRunner =
    object : LocalChainRunner {
        override suspend fun run(segment: ByteArray): NluResult = NluResult(block(segment))
    }

/**
 * 云端链路由（spec §5.1 云端优先链路）：话语段 → 网关回复。
 * gateway-client 装配由 Task 20 注入实现。
 */
fun interface CloudRunner {
    suspend fun run(segment: ByteArray): Reply

    /** 并发轮次使用显式快照 ID，避免迟启动的旧音频被标成新轮。 */
    suspend fun run(segment: ByteArray, utteranceId: String): Reply = run(segment)
}

/**
 * 收敛结果通道（Task 18/19/20 消费）：每轮话语恰好回调一次，
 * 含 [RaceWinner.Failed]（应用层据此播兜底话术"网络开小差了"）。
 * 回调在会话协程内同步发出，之后立即回 IDLE；demo 播报时长由应用层管理。
 */
fun interface ResultListener {
    fun onResult(utteranceId: String, winner: RaceWinner)
}

/**
 * 云端链路故障（Task 15 M1）：连接失败 / ready 后中途断开时由云端链实现抛出。
 * 会话捕获后转本地单链兜底路径（写 `cloud_unreachable` 决策 + [RaceWinner.Local]），
 * 不外抛、不中断状态机。是否 latch 不可达由云端链实现决定（见 [VoiceSession.onCloudUnavailable]）。
 */
class CloudUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 云端已连接但拒绝/处理失败；本轮转本地，不把健康连接误判为断网并重连。 */
class CloudRequestFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 语音会话：状态机（spec §7.1）+ 本地/云端双路由编排（spec §5.1）。
 *
 * 一轮话语的编排（Task 49 双路：录音音频分两路，一路 VAD 切段上云，一路整段本地识别）：
 *  1. [onListeningStart]：IDLE → LISTENING（Task 18 录音开始）；
 *  2. 录音期间：按顺序调 [onCloudSegment]（每个 VAD 语音段，调用方必须先于
 *     [onTurnSegment] 全部喂完）——会话只缓存片段，不把一轮话拆成多次模型调用；
 *  3. 抬手/话语结束：调 [onTurnSegment]（本地路整段音频）——LISTENING → UNDERSTANDING，
 *     按时间顺序拼接本轮 VAD 片段并只启动一次云端链，同时启动本地链（整段），
 *     Deferred 交 [OnDeviceRaceArbiter] 收敛（云端优先，cloudWaitMs 兜底本地）；
 *  4. 收敛后按 winner 置 EXECUTING（[RaceWinner.Local]）/ SPEAKING（[RaceWinner.Cloud]），
 *     [ResultListener.onResult] 发出后立即回 IDLE；[RaceWinner.Failed] 不置执行/播报，
 *     直接回调后回 IDLE（兜底话术归应用层）。
 *
 * 云端链启动条件：`cfg.cloud.enabled && cloudAvailable`；[onCloudUnavailable] 置
 * cloudAvailable=false 后只跑本地链，且每次话语写一条 `cloud_unreachable` 决策日志；
 * [onCloudAvailable] 恢复（Task 20 engine 在话语开始、网络可用时调用）。
 * 本轮没有任何云端段（VAD 未切出 / 云端未启用）时跳过竞速，本地链直接赢，
 * 决策 reason = `no_cloud_segment`（区别于链路故障的 `cloud_unreachable`）。
 *
 * 防御：所有入口在非法状态调用时忽略并返回，不抛；runTurn 以 try/finally 收尾，
 * 任何异常/取消下状态都回 IDLE（不冻结在 UNDERSTANDING，Task 14 M1）。
 *
 * 协程语义：链内异常不吞，按协程语义传播；唯一特例是云端链的 [CloudUnavailableException]
 * ——会话捕获后转本地单链兜底路径（`cloud_unreachable` 决策 + [RaceWinner.Local]，Task 15 M1）。
 * 候选在独立 Supervisor scope 并发启动；仲裁收敛后输家自然完成，
 * 迟到结果仍下发；是否为当前轮由下游 DialogueStateMachine 判断。
 */
class VoiceSession(
    private val cfg: DemoConfig,
    private val arbiter: OnDeviceRaceArbiter,
    private val sink: DecisionSink,
    private val local: LocalChainRunner,
    private val cloud: CloudRunner,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val resultListener: ResultListener = ResultListener { _, _ -> },
) {
    /**
     * 候选计算与仲裁等待解耦：仲裁返回后输家可继续自然完成，不阻塞轮次收口。
     * 只有整个会话销毁时 [close] 才停止剩余任务，不属于仲裁胜者驱动取消。
     */
    private val candidateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(SessionState.IDLE)

    /** 当前会话状态，可订阅（flow 收集天然携带初始值）。 */
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * 本轮话语 utteranceId（T7）：由应用层 [VoiceSession.onListeningStart] 调用前设置
     * （VoiceEngine.onListeningStart 同步写入）；本会话写出的 on-device 决策日志携带
     * 真实值（[localOnly]），装配时注入 [OnDeviceRaceArbiter] 的 provider 也读它——
     * 数据平台按 utteranceId 汇合端云事件。默认空串（未接 telemetry 时零影响）。
     */
    @Volatile
    var currentUtteranceId: String = ""

    private val stateListeners = CopyOnWriteArrayList<(SessionState) -> Unit>()

    /** 云端可达性（spec §5.1）：`onCloudUnavailable()` 置 false 后不再启动云端链；
     *  `onCloudAvailable()` 恢复（Task 20 engine 在话语开始时按网络状态调用）。
     *  @Volatile：会话协程写（onCloudUnavailable/onCloudAvailable），
     *  runTurn 协程读（cloudRouteActive），跨线程可见（Task 14 M2）。 */
    @Volatile
    private var cloudAvailable = true

    /**
     * 本轮云端 VAD 片段。S2S 在首个音频 chunk 到达时就返回 StreamingAudioReply，若把
     * 每个 VAD 片段分别调用 cloud.run，下一片段会在上一回复仍播放/生成时撞上网关 BUSY，
     * 且模型看不到完整话语。这里只收集片段，在 [onTurnSegment] 原子取快照并整轮调用一次。
     */
    private val cloudSegments = CopyOnWriteArrayList<ByteArray>()

    /**
     * 注册状态监听：立即以当前状态回调一次，此后每次状态切换同步回调
     * （与 [state] flow 的"先给当前值再给变更"语义一致）。
     */
    fun onState(listener: (SessionState) -> Unit) {
        stateListeners.add(listener)
        listener(_state.value)
    }

    /**
     * 录音开始：普通轮 IDLE → LISTENING；用户在理解/播报期间发起新轮时，
     * 也允许直接转 LISTENING。旧轮候选不取消，结果交给下游状态机判断。
     */
    fun onListeningStart() {
        if (_state.value == SessionState.LISTENING) return
        cloudSegments.clear()
        transition(SessionState.LISTENING)
    }

    /** 录音中止（Task 18 调用）：LISTENING → IDLE；非 LISTENING 时忽略（防御）。 */
    fun onListeningStop() {
        if (_state.value != SessionState.LISTENING) return
        cloudSegments.clear()
        transition(SessionState.IDLE)
    }

    /**
     * 云端路段入队（Task 49 双路：VAD 切出的一段语音，调用方按时间顺序喂入，
     * 且必须在 [onTurnSegment] 之前全部喂完）。LISTENING 且云端启用时缓存片段；
     * 抬手后拼接为一个完整模型输入，经典/S2S 后端都只处理一次本轮话语。
     * 非 LISTENING / 云端未启用时忽略并返回（防御，不抛）。
     */
    fun onCloudSegment(segment: ByteArray) {
        if (!cloudRouteActive() || _state.value != SessionState.LISTENING) return
        if (segment.isNotEmpty()) cloudSegments.add(segment.copyOf())
    }

    /**
     * 本地路整段音频（Task 49 双路：录音抬手后的完整降噪段）：LISTENING → UNDERSTANDING，
     * 启动本轮编排——本地链跑整段，云端收敛 Deferred 取本轮在途/已完成上传，
     * 收敛后置 EXECUTING/SPEAKING，结果回调后立即回 IDLE。
     * 非 LISTENING 状态调用忽略并返回（防御，不抛）。
     */
    fun onTurnSegment(segment: ByteArray) = onTurnSegment(segment, currentUtteranceId)

    /** 显式 capture/turn 关联；状态机的当前轮可晚于 VAD capture 建立。 */
    fun onTurnSegment(segment: ByteArray, utteranceId: String) {
        if (_state.value != SessionState.LISTENING) return
        val cloudAudio = takeCloudAudio()
        transition(SessionState.UNDERSTANDING)
        scope.launch { runTurn(utteranceId, segment, cloudAudio) }
    }

    /** 云端不可达（断网/认证失效等，spec §5.1 可达性检查）：此后只跑本地链。 */
    fun onCloudUnavailable() {
        cloudAvailable = false
    }

    /** 云端可达性恢复（Task 20）：engine 在话语开始且网络可用时调用，重新启用云端链。 */
    fun onCloudAvailable() {
        cloudAvailable = true
    }

    /**
     * 一轮话语编排。防御（Task 14 M1）：try/finally 保证无论链内发生什么
     * （含 [CloudUnavailableException]、意外异常、取消），状态都回到 IDLE——
     * 状态机绝不冻结在 UNDERSTANDING。意外异常不吞，按协程语义继续传播；
     * [CloudUnavailableException]（云端链路故障）转本地单链兜底路径。
     */
    private suspend fun runTurn(utteranceId: String, segment: ByteArray, cloudAudio: ByteArray?) {
        try {
            val winner = if (cloudAudio != null) {
                try {
                    raceCloudVsLocal(utteranceId, segment, cloudAudio)
                } catch (e: CloudUnavailableException) {
                    // 云端链故障（连接失败/ready 后中断，Task 15 M1）：转本地兜底路径
                    onCloudUnavailable()
                    localOnly(utteranceId, segment)
                } catch (e: CloudRequestFailedException) {
                    // BUSY / provider error 等服务端业务错误不重连、不 latch 连接不可达。
                    localOnly(utteranceId, segment, reason = "cloud_request_failed")
                }
            } else {
                // 本轮没有任何云端段（VAD 未切出 / 云端未启用）：跳过竞速，本地直接赢
                localOnly(
                    utteranceId,
                    segment,
                    reason = if (cloudRouteActive()) "no_cloud_segment" else "cloud_unreachable",
                )
            }

            if (isCurrent(utteranceId)) {
                when (winner) {
                    is RaceWinner.Cloud -> transition(SessionState.SPEAKING)
                    is RaceWinner.Local -> transition(SessionState.EXECUTING)
                    is RaceWinner.Failed, is RaceWinner.Intercepted -> Unit
                }
            }
            // 仲裁结果一律下发；是否属于状态机当前轮由下游 DialogueStateMachine 判断。
            resultListener.onResult(utteranceId, winner)
        } finally {
            // 旧轮自然完成时不得把新轮 LISTENING/UNDERSTANDING 状态踩回 IDLE。
            if (isCurrent(utteranceId)) {
                cloudSegments.clear()
                transition(SessionState.IDLE)
            }
        }
    }

    /** 云端链不启动（配置关闭/不可达/链路故障/无云端段）：写一条决策日志，只跑本地链。 */
    private suspend fun localOnly(
        utteranceId: String,
        segment: ByteArray,
        reason: String = "cloud_unreachable",
    ): RaceWinner {
        val nlu = local.run(segment, utteranceId)
        if (!arbiter.claimSemantic(utteranceId, "local")) return RaceWinner.Intercepted
        sink.onDecision(
            DecisionEntry(
                arbiter = "on-device",
                route = "local",
                reason = reason,
                // T7：决策日志携带本轮真实 utteranceId（空串=未接 telemetry，语义不变）
                utteranceId = utteranceId,
                timestampMs = System.currentTimeMillis(),
            ),
        )
        return RaceWinner.Local(nlu)
    }

    private suspend fun raceCloudVsLocal(
        utteranceId: String,
        segment: ByteArray,
        cloudAudio: ByteArray,
    ): RaceWinner {
            // 云端只接收一次本轮拼接后的 VAD 音频。注意：异常（CloudUnavailableException）
            // 从 await 原样上抛，经 race 到 runTurn 的
            // catch 转本地兜底路径；取消语义同样不被 withTimeoutOrNull 吞掉（Task 14 M2）。
        // 候选挂在会话 Supervisor scope，仲裁收敛不会等待输家，也不会取消输家。
        // 候选自然完成；仲裁只做按轮单输出，是否采用交给下游状态机。
        val cloudD = candidateScope.async { cloud.run(cloudAudio, utteranceId) }
        val localD = candidateScope.async { local.run(segment, utteranceId) }
        return arbiter.race(utteranceId, cloudD, localD)
    }

    fun close() {
        candidateScope.cancel()
        cloudSegments.clear()
        transition(SessionState.IDLE)
    }

    private fun isCurrent(utteranceId: String): Boolean =
        utteranceId.isBlank() || utteranceId == currentUtteranceId

    /** 原子取走本轮 VAD 片段并按时间顺序拼接；空片段轮返回 null，走本地链。 */
    private fun takeCloudAudio(): ByteArray? {
        val snapshot = cloudSegments.toList()
        cloudSegments.clear()
        if (snapshot.isEmpty()) return null
        val total = snapshot.sumOf { it.size }
        val joined = ByteArray(total)
        var offset = 0
        snapshot.forEach { part ->
            part.copyInto(joined, offset)
            offset += part.size
        }
        return joined
    }

    /** 云端链启动条件（spec §5.1 可达性）：配置开启且云端可达。 */
    private fun cloudRouteActive(): Boolean = cfg.cloud.enabled && cloudAvailable

    private fun transition(next: SessionState) {
        _state.value = next
        stateListeners.forEach { it(next) }
    }
}

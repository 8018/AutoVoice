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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
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
}

/**
 * 收敛结果通道（Task 18/19/20 消费）：每轮话语恰好回调一次，
 * 含 [RaceWinner.Failed]（应用层据此播兜底话术"网络开小差了"）。
 * 回调在会话协程内同步发出，之后立即回 IDLE；demo 播报时长由应用层管理。
 */
fun interface ResultListener {
    fun onResult(winner: RaceWinner)
}

/**
 * 云端链路故障（Task 15 M1）：连接失败 / ready 后中途断开时由云端链实现抛出。
 * 会话捕获后转本地单链兜底路径（写 `cloud_unreachable` 决策 + [RaceWinner.Local]），
 * 不外抛、不中断状态机。是否 latch 不可达由云端链实现决定（见 [VoiceSession.onCloudUnavailable]）。
 */
class CloudUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 语音会话：状态机（spec §7.1）+ 本地/云端双路由编排（spec §5.1）。
 *
 * 一轮话语的编排（Task 49 双路：录音音频分两路，一路 VAD 切段上云，一路整段本地识别）：
 *  1. [onListeningStart]：IDLE → LISTENING（Task 18 录音开始）；
 *  2. 录音期间：按顺序调 [onCloudSegment]（每个 VAD 语音段，调用方必须先于
 *     [onTurnSegment] 全部喂完）——云端链串行上传：段按 [onCloudSegment] 调用顺序
 *     排队（前一段完成后才启动后一段），最新在途上传挂在 [uploadTail] 上；
 *  3. 抬手/话语结束：调 [onTurnSegment]（本地路整段音频）——LISTENING → UNDERSTANDING，
 *     并发启动本地链（整段）+ 云端收敛 Deferred（等待在途上传或直接取已完成回复），
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
 * 轮次内用 coroutineScope + async 并发启动，输家 Deferred 在收敛后立即取消。
 */
class VoiceSession(
    private val cfg: DemoConfig,
    private val arbiter: OnDeviceRaceArbiter,
    private val sink: DecisionSink,
    private val local: LocalChainRunner,
    private val cloud: CloudRunner,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val resultListener: ResultListener = ResultListener {},
) {
    private val _state = MutableStateFlow(SessionState.IDLE)

    /** 当前会话状态，可订阅（flow 收集天然携带初始值）。 */
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * 本轮话语 utteranceId（T7）：由应用层 [VoiceSession.onListeningStart] 调用前设置
     * （VoiceEngine.onListeningStart 同步写入）；本会话写出的 on-device 决策日志携带
     * 真实值（[localOnly]），装配时注入 [OnDeviceRaceArbiter] 的 provider 也读它——
     * 数据平台按 utteranceId 汇合端云事件。默认空串（未接 telemetry 时零影响）。
     */
    var currentUtteranceId: String = ""

    private val stateListeners = CopyOnWriteArrayList<(SessionState) -> Unit>()

    /** 云端可达性（spec §5.1）：`onCloudUnavailable()` 置 false 后不再启动云端链；
     *  `onCloudAvailable()` 恢复（Task 20 engine 在话语开始时按网络状态调用）。
     *  @Volatile：会话协程写（onCloudUnavailable/onCloudAvailable），
     *  runTurn 协程读（cloudRouteActive），跨线程可见（Task 14 M2）。 */
    @Volatile
    private var cloudAvailable = true

    /**
     * 云端段串行上传链尾（Task 49）：每个 [onCloudSegment] 在调用线程同步挂一个新
     * Deferred 到链尾（旧尾成为"前驱"），上传协程先 await 前驱再跑云端链——多段
     * 严格按调用顺序串行（网关单 pendingReply slot，不能并发）。
     * @Volatile：调用线程（Main）写，runTurn 协程（Default）读，跨线程可见。
     */
    @Volatile
    private var uploadTail: CompletableDeferred<Reply>? = null

    /** 本会话全部云端上传协程（Task 49）：轮次收敛后立即取消——输家回复已无用，
     *  且防止永不返回的云端链（挂死协程/测试挂死）在会话内泄漏。跨线程（调用线程
     *  add / runTurn 协程 clear），CopyOnWriteArrayList 保迭代安全。 */
    private val uploadJobs = CopyOnWriteArrayList<Job>()

    /**
     * 注册状态监听：立即以当前状态回调一次，此后每次状态切换同步回调
     * （与 [state] flow 的"先给当前值再给变更"语义一致）。
     */
    fun onState(listener: (SessionState) -> Unit) {
        stateListeners.add(listener)
        listener(_state.value)
    }

    /** 录音开始（Task 18 调用）：IDLE → LISTENING；非 IDLE 时忽略（防御）。 */
    fun onListeningStart() {
        if (_state.value != SessionState.IDLE) return
        transition(SessionState.LISTENING)
    }

    /** 录音中止（Task 18 调用）：LISTENING → IDLE；非 LISTENING 时忽略（防御）。 */
    fun onListeningStop() {
        if (_state.value != SessionState.LISTENING) return
        transition(SessionState.IDLE)
    }

    /**
     * 云端路段入队（Task 49 双路：VAD 切出的一段语音，调用方按时间顺序喂入，
     * 且必须在 [onTurnSegment] 之前全部喂完）。LISTENING 且云端启用时串行上传：
     * 前一段完成后才启动后一段，回复挂在链尾 [uploadTail]，供本轮竞速取用。
     * 非 LISTENING / 云端未启用时忽略并返回（防御，不抛）。
     */
    fun onCloudSegment(segment: ByteArray) {
        if (!cloudRouteActive() || _state.value != SessionState.LISTENING) return
        val prev = uploadTail
        val d = CompletableDeferred<Reply>()
        uploadTail = d
        val job = scope.launch {
            try {
                prev?.await() // 串行：等前一段上传完成（含其失败传播）
                val reply = cloud.run(segment)
                d.complete(reply)
            } catch (e: CloudUnavailableException) {
                onCloudUnavailable()
                d.completeExceptionally(e)
            }
        }
        uploadJobs.add(job)
    }

    /**
     * 本地路整段音频（Task 49 双路：录音抬手后的完整降噪段）：LISTENING → UNDERSTANDING，
     * 启动本轮编排——本地链跑整段，云端收敛 Deferred 取本轮在途/已完成上传，
     * 收敛后置 EXECUTING/SPEAKING，结果回调后立即回 IDLE。
     * 非 LISTENING 状态调用忽略并返回（防御，不抛）。
     */
    fun onTurnSegment(segment: ByteArray) {
        if (_state.value != SessionState.LISTENING) return
        transition(SessionState.UNDERSTANDING)
        scope.launch { runTurn(segment) }
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
    private suspend fun runTurn(segment: ByteArray) {
        try {
            val winner = if (uploadTail != null) {
                try {
                    raceCloudVsLocal(segment)
                } catch (e: CloudUnavailableException) {
                    // 云端链故障（连接失败/ready 后中断，Task 15 M1）：转本地兜底路径
                    localOnly(segment)
                }
            } else {
                // 本轮没有任何云端段（VAD 未切出 / 云端未启用）：跳过竞速，本地直接赢
                localOnly(segment, reason = if (cloudRouteActive()) "no_cloud_segment" else "cloud_unreachable")
            }

            when (winner) {
                is RaceWinner.Cloud -> transition(SessionState.SPEAKING)
                is RaceWinner.Local -> transition(SessionState.EXECUTING)
                is RaceWinner.Failed -> Unit // 全败：不置执行/播报，直接回调后回 IDLE
                // B2：非最新轮语义被拦截——结果已过期，不执行不播报，静默回 IDLE
                is RaceWinner.Intercepted -> Unit
            }
            resultListener.onResult(winner)
        } finally {
            // 轮次收敛：本轮在途云端上传立即取消（回复已无消费者，且防永不返回的云端
            // 链挂死/泄漏），链尾清空，下一轮从零开始
            uploadJobs.forEach { it.cancel() }
            uploadJobs.clear()
            uploadTail = null
            transition(SessionState.IDLE)
        }
    }

    /** 云端链不启动（配置关闭/不可达/链路故障/无云端段）：写一条决策日志，只跑本地链。 */
    private suspend fun localOnly(segment: ByteArray, reason: String = "cloud_unreachable"): RaceWinner {
        sink.onDecision(
            DecisionEntry(
                arbiter = "on-device",
                route = "local",
                reason = reason,
                // T7：决策日志携带本轮真实 utteranceId（空串=未接 telemetry，语义不变）
                utteranceId = currentUtteranceId,
                timestampMs = System.currentTimeMillis(),
            ),
        )
        return RaceWinner.Local(local.run(segment))
    }

    private suspend fun raceCloudVsLocal(segment: ByteArray): RaceWinner =
        coroutineScope {
            // 云端收敛 Deferred：本轮最后一个在途上传完成 → 取其回复；无上传则永不完成
            // （awaitCancellation，让仲裁器 cloudWaitMs 超时 → 本地兜底）。
            // 注意：异常（CloudUnavailableException）从 await 原样上抛，经 race 到 runTurn 的
            // catch 转本地兜底路径；取消语义同样不被 withTimeoutOrNull 吞掉（Task 14 M2）。
            val cloudD = async { uploadTail?.await() ?: awaitCancellation() }
            val localD = async { local.run(segment) }
            val winner = arbiter.race(cloudD, localD)
            // 单赢家原则（spec §5.3）：收敛后立即取消输家，不占资源
            when (winner) {
                is RaceWinner.Cloud -> localD.cancel()
                is RaceWinner.Local -> cloudD.cancel()
                is RaceWinner.Failed -> {
                    cloudD.cancel()
                    localD.cancel()
                }
                // B2：非最新轮拦截——语义已过期，两端结果全部作废
                is RaceWinner.Intercepted -> {
                    cloudD.cancel()
                    localD.cancel()
                }
            }
            winner
        }

    /** 云端链启动条件（spec §5.1 可达性）：配置开启且云端可达。 */
    private fun cloudRouteActive(): Boolean = cfg.cloud.enabled && cloudAvailable

    private fun transition(next: SessionState) {
        _state.value = next
        stateListeners.forEach { it(next) }
    }
}

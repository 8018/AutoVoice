package com.autovoice.voicecore.session

import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.arbiter.OnDeviceRaceArbiter
import com.autovoice.voicecore.arbiter.RaceWinner
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 端侧本地链路由（spec §5.1 本地兜底链路）：话语段 → 规范化意图。
 * ASR/NLU 装配由 Task 16/17 注入实现，[VoiceSession] 不直接依赖适配器。
 */
fun interface LocalChainRunner {
    suspend fun run(segment: ByteArray): Intent
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
 * 语音会话：状态机（spec §7.1）+ 本地/云端双路由编排（spec §5.1）。
 *
 * 一轮话语的编排：
 *  1. [onListeningStart]：IDLE → LISTENING（Task 18 录音开始）；
 *  2. [onVadSegment]（VAD end）：LISTENING → UNDERSTANDING，并发启动本地链 + 云端链，
 *     Deferred 交 [OnDeviceRaceArbiter] 收敛（云端优先，cloudWaitMs 兜底本地）；
 *  3. 收敛后按 winner 置 EXECUTING（[RaceWinner.Local]）/ SPEAKING（[RaceWinner.Cloud]），
 *     [ResultListener.onResult] 发出后立即回 IDLE；[RaceWinner.Failed] 不置执行/播报，
 *     直接回调后回 IDLE（兜底话术归应用层）。
 *
 * 云端链启动条件：`cfg.cloud.enabled && cloudAvailable`；[onCloudUnavailable] 置
 * cloudAvailable=false 后只跑本地链，且每次话语写一条 `cloud_unreachable` 决策日志。
 *
 * 防御：所有入口在非法状态调用时忽略并返回，不抛。
 *
 * 协程语义：链内异常不吞，按协程语义传播（demo 阶段链实现自身不抛）。
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

    private val stateListeners = CopyOnWriteArrayList<(SessionState) -> Unit>()

    /** 云端可达性（spec §5.1）：`onCloudUnavailable()` 置 false 后不再启动云端链。 */
    private var cloudAvailable = true

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
     * 一句话语边界（VAD end）：LISTENING → UNDERSTANDING，并发启动本地链 + 云端链，
     * 收敛后置 EXECUTING/SPEAKING，结果回调后立即回 IDLE。
     * 非 LISTENING 状态调用忽略并返回（防御，不抛）。
     */
    fun onVadSegment(segment: ByteArray) {
        if (_state.value != SessionState.LISTENING) return
        transition(SessionState.UNDERSTANDING)
        scope.launch { runTurn(segment) }
    }

    /** 云端不可达（断网/认证失效等，spec §5.1 可达性检查）：此后只跑本地链。 */
    fun onCloudUnavailable() {
        cloudAvailable = false
    }

    private suspend fun runTurn(segment: ByteArray) {
        val winner = if (cloudRouteActive()) {
            raceCloudVsLocal(segment)
        } else {
            // 云端链不启动（配置关闭或不可达）：每次话语写一条 cloud_unreachable 决策日志，只跑本地链
            sink.onDecision(cloudUnreachable())
            RaceWinner.Local(local.run(segment))
        }

        when (winner) {
            is RaceWinner.Cloud -> transition(SessionState.SPEAKING)
            is RaceWinner.Local -> transition(SessionState.EXECUTING)
            is RaceWinner.Failed -> Unit // 全败：不置执行/播报，直接回调后回 IDLE
        }
        resultListener.onResult(winner)
        transition(SessionState.IDLE)
    }

    private suspend fun raceCloudVsLocal(segment: ByteArray): RaceWinner =
        coroutineScope {
            val cloudD = async { cloud.run(segment) }
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
            }
            winner
        }

    /** 云端链启动条件（spec §5.1 可达性）：配置开启且云端可达。 */
    private fun cloudRouteActive(): Boolean = cfg.cloud.enabled && cloudAvailable

    private fun cloudUnreachable(): DecisionEntry =
        DecisionEntry(
            arbiter = "on-device",
            route = "local",
            reason = "cloud_unreachable",
            utteranceId = "",
            timestampMs = System.currentTimeMillis(),
        )

    private fun transition(next: SessionState) {
        _state.value = next
        stateListeners.forEach { it(next) }
    }
}

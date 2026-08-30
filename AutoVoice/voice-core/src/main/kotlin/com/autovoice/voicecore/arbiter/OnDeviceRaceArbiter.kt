package com.autovoice.voicecore.arbiter

import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.NluResult
import com.autovoice.voicecore.Reply
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 端侧竞速仲裁结果（spec §5.1）：云端回复 / 本地意图 / 双败 / 语义拦截。
 */
sealed class RaceWinner {
    /** 云端回复在 cloudWaitMs 内到达。 */
    data class Cloud(val reply: Reply) : RaceWinner()

    /** 云端超时后，本地意图在 localFallbackMs 内到达。 */
    data class Local(val nlu: NluResult) : RaceWinner() {
        val intent: Intent get() = nlu.intent
        val recognizedText: String? get() = nlu.recognizedText
    }

    /** 云端与本地均超时。 */
    data object Failed : RaceWinner()

    /** 同一 turn 已经输出过语义；后续候选在仲裁层被拦截。 */
    data object Intercepted : RaceWinner()
}

/**
 * 端侧仲裁过程事件（B2 需求 4）：收到候选 / 胜出 / 失败——装配方转 telemetry 插桩
 * （device_arbiter_received / device_arbiter_won / device_arbiter_lost）。
 */
sealed class OnDeviceArbiterEvent {
    /** 收到候选语义：route=cloud 收到云端语义；route=local 收到本地 ASR 命令词。 */
    data class Received(val route: String) : OnDeviceArbiterEvent()

    /**
     * 仲裁胜出（原因与现有决策语义一致）：priority 优先（云端先到/策略优先）、
     * cloud_timeout 超时未收到云端（云端超时后本地兜底胜出）、
     * local_command 本地车窗开关直接胜出（能力分级 2026-08-15，不等云端）。
     */
    data class Won(val route: String, val reason: String) : OnDeviceArbiterEvent()

    /**
     * 仲裁失败：cloud_already_won 已有云端语义胜出、command_already_won 已有命令词胜出、
     * turn_already_output 表示该轮已经输出过语义。
     */
    data class Lost(val route: String, val reason: String) : OnDeviceArbiterEvent()

    /**
     * B5：云端 pending 占位（LLM 处理中，协议 §4.8）——非收敛事件：收到后阶段 1
     * 窗口延长至 pendingWaitMs 继续等最终语义；执行侧只改 UI 状态，无执行无播报。
     */
    data class Pending(val route: String) : OnDeviceArbiterEvent()
}

/**
 * 端侧竞速仲裁器（spec §5.1）：**能力分级（2026-08-15）**——本地车窗开关命令
 * 到达直接胜出（不等云端）；其余云端优先，超时后本地兜底。
 *
 * 收敛规则（两阶段）：
 *  - **阶段 1（cloudWaitMs 窗口，并发等两候选，结果经 [Channel] 汇合）**：
 *    - 本地车窗开关（[Intent.isWindowPower]）到达 → [RaceWinner.Local] 立即胜出
 *      （reason = `local_command_won`，胜出事件原因 `local_command`——不等云端）；
 *      本地与云端同时完成时本地分支先注册先送结果，本地车窗优先；
 *    - 本地其他候选（unknown 拒识 / 非车窗防御）到达 → 不参与胜出，只等云端
 *      （云端优先）；
 *    - 云端语义到达 → [RaceWinner.Cloud]（reason = `cloud_won`，胜出事件原因
 *      `priority` 优先）；
 *    - 云端 pending 占位（B5，LLM 处理中，协议 §4.8）到达 → 不收敛：窗口延长至
 *      `pendingWaitMs` 继续等最终语义（执行侧只改 UI 状态，无执行无播报）；
 *  - **阶段 2（云端超时）**：`withTimeoutOrNull(localFallbackMs) { local.await() }`
 *    非空 → [RaceWinner.Local]（reason = `cloud_timeout_use_local`，胜出事件原因
 *    `cloud_timeout` 超时未收到云端）；本地 unknown → [RaceWinner.Failed] 拒识；
 *  - 两者皆超时 → [RaceWinner.Failed]（reason = `both_failed`）。
 *
 * B2 事件与拦截：
 *  - 候选到达记 [OnDeviceArbiterEvent.Received]（route=cloud 收到云端语义 /
 *    route=local 收到本地 ASR 命令词）；
 *  - 胜出记 [OnDeviceArbiterEvent.Won]；胜出瞬间输家若已完成，记
 *    [OnDeviceArbiterEvent.Lost]（`cloud_already_won` 已有云端语义胜出 /
 *    `command_already_won` 已有命令词胜出——云端在超时窗口边缘迟到，但本地已赢）；
 *  - **B5 pending 占位**：收到云端 pending 记 [OnDeviceArbiterEvent.Pending]（route=cloud）
 *    ——非收敛事件，窗口延长后继续等最终语义；
 *  - **按轮单输出**：仲裁器不知道状态机当前轮；同一 turn 已经输出过语义时记录
 *    `turn_already_output` 并返回 [RaceWinner.Intercepted]。是否采用由下游状态机判断。
 *
 * 决策日志经 [DecisionSink] 写出：arbiter = `on-device`，
 * 新调用显式传入 turnId；无显式 ID 的兼容调用才读取 [utteranceId] provider。
 * timestampMs 使用注入的 [clock]。
 *
 * 协程语义：`withTimeoutOrNull` 只把自身超时转换为 null；块内抛出的
 * [kotlinx.coroutines.CancellationException]（如父协程取消导致 `await` 中断）
 * 会原样向上传播。本实现不做任何 catch，绝不吞掉取消。
 */
class OnDeviceRaceArbiter(
    private val cloudWaitMs: Long = 2000,
    private val localFallbackMs: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sink: DecisionSink,
    /** 旧调用的 turnId 读取器；生产编排使用 race(turnId, ...) 显式传入。 */
    private val utteranceId: () -> String = { "" },
    /** B2：仲裁过程事件（收到候选/胜出/失败）→ 装配方转 telemetry 插桩。默认 no-op。 */
    private val onEvent: (OnDeviceArbiterEvent) -> Unit = {},
    /**
     * B5：云端 pending 占位信号（LLM 处理中，协议 §4.8）。用 Channel 而非 Deferred：
     * Deferred 只完成一次，relaunch 循环里已完成 Deferred 会立刻再发 Pending → 死循环。
     */
    private val pending: ReceiveChannel<Unit> = Channel(),
    /** 生产链路按 turn 隔离 pending；null 时沿用 [pending] 兼容测试/旧装配。 */
    private val pendingByTurn: PendingSignalRegistry? = null,
    /**
     * B5：收到 pending 后阶段 1 的扩展窗口。LLM 工具循环最长约 15s（服务端
     * Classic 工具循环约 15s；qwen3.5-omni-plus HTTP 文件模式由服务端保证至少 45s。
     * 端侧使用 50s，确保 pending 后不会先于任一构建变体的服务端 safety 收敛。
     */
    private val pendingWaitMs: Long = 50_000,
    /** 每轮最多向下游输出一次语义；不包含、也不查询“当前轮”概念。 */
    private val emissionLedger: SemanticEmissionLedger = SemanticEmissionLedger(),
) {
    /** 阶段 1 收敛结果（能力分级 2026-08-15 + B5）：本地车窗开关 / 云端语义 / pending 占位。 */
    private sealed interface Outcome {
        /** 本地车窗开关到达（能力分级：直接胜出，不等云端）。 */
        data class LocalWin(val nlu: NluResult) : Outcome

        /** 云端语义到达（云端优先胜出）。 */
        data class Cloud(val reply: Reply) : Outcome

        /** B5：云端 pending 占位（不收敛——窗口延长后继续等最终语义）。 */
        data object Pending : Outcome
    }

    suspend fun race(cloud: Deferred<Reply>, local: Deferred<NluResult>): RaceWinner =
        race(utteranceId(), cloud, local)

    suspend fun race(
        turnId: String,
        cloud: Deferred<Reply>,
        local: Deferred<NluResult>,
    ): RaceWinner {
        val turnPending = pendingByTurn?.channel(turnId) ?: pending

        // 阶段 1（cloudWaitMs 窗口，并发等 cloud 与 local，能力分级 2026-08-15）：
        // 两端结果经 Channel 汇合，先注册本地分支（同时完成时本地车窗优先）。
        // 本地车窗开关 → 送 LocalWin 立即胜出（不等云端）；本地非车窗（unknown 拒识 /
        // misc 防御）→ 不发结果，不参与胜出只等云端；云端语义 → 送 Cloud 胜出。
        // B5：云端 pending 占位信号 → 送 Pending——非收敛：窗口延长至 pendingWaitMs
        // 继续等最终语义（LLM 工具循环最长约 8s 超过 cloudWaitMs=3000，占位把窗口撑到
        // pendingWaitMs，推理完成后最终语义照常直接胜出）。
        // 首个真实候选即收敛；协程语义同旧实现：超时只把 withTimeoutOrNull 转换为
        // null，取消原样向上传播，不吞 CancellationException。
        var waitMs = cloudWaitMs
        while (true) {
            val outcome = withTimeoutOrNull(waitMs) {
                coroutineScope {
                    val results = Channel<Outcome>(capacity = 3)
                    val localJob = launch {
                        local.await().takeIf { it.intent.isWindowPower() }
                            ?.let { results.send(Outcome.LocalWin(it)) }
                    }
                    val cloudJob = launch { results.send(Outcome.Cloud(cloud.await())) }
                    val pendingJob = launch {
                        turnPending.receive()
                        results.send(Outcome.Pending)
                    }
                    val winner = results.receive()
                    // 已收敛：取消仍挂在 await 上的输家子协程，coroutineScope 才会返回
                    // （await 的取消不影响 deferred 本身——deferred 归调用方所有）
                    localJob.cancel()
                    cloudJob.cancel()
                    pendingJob.cancel()
                    winner
                }
            }

            when (outcome) {
                // 本地车窗开关先到 → 能力分级直接胜出（reason = local_command_won，不等云端）
                is Outcome.LocalWin -> {
                    onEvent(OnDeviceArbiterEvent.Received("local"))
                    if (!claimSemantic(turnId, "local")) {
                        return RaceWinner.Intercepted
                    }
                    onEvent(OnDeviceArbiterEvent.Won("local", "local_command"))
                    sink.onDecision(decision(turnId, route = "local", reason = "local_command_won"))
                    // 单赢家：云端语义若已同时到达（本地先赢）→ 记失败（已有命令词胜出）
                    if (completedValue(cloud) != null) {
                        onEvent(OnDeviceArbiterEvent.Received("cloud"))
                        onEvent(OnDeviceArbiterEvent.Lost("cloud", "command_already_won"))
                    }
                    return RaceWinner.Local(outcome.nlu)
                }

                // 云端语义先到 → 云端胜出（reason = cloud_won，现逻辑）
                is Outcome.Cloud -> {
                    onEvent(OnDeviceArbiterEvent.Received("cloud"))
                    if (!claimSemantic(turnId, "cloud")) {
                        return RaceWinner.Intercepted
                    }
                    onEvent(OnDeviceArbiterEvent.Won("cloud", "priority"))
                    sink.onDecision(decision(turnId, route = "cloud", reason = "cloud_won"))
                    // 单赢家：本地命令词若已到达（云端先赢）→ 记失败（已有云端语义胜出）
                    if (completedValue(local) != null) {
                        onEvent(OnDeviceArbiterEvent.Received("local"))
                        onEvent(OnDeviceArbiterEvent.Lost("local", "cloud_already_won"))
                    }
                    return RaceWinner.Cloud(outcome.reply)
                }

                // B5：云端 pending 占位（LLM 处理中）→ 非收敛：窗口延长至 pendingWaitMs
                // 继续等最终语义（pending 至多一次——服务端 llm.isDone() 守卫；扩展窗口内
                // 本地车窗到达照样立即胜出）
                is Outcome.Pending -> {
                    onEvent(OnDeviceArbiterEvent.Pending("cloud"))
                    waitMs = pendingWaitMs
                    continue
                }

                // 阶段 1 超时（窗口内无真实候选）→ 跳出循环，进阶段 2 本地兜底
                null -> break
            }
        }

        // 阶段 2（云端超时）：withTimeoutOrNull(localFallbackMs) 内等本地 ASR 命令词
        val nlu = withTimeoutOrNull(localFallbackMs) { local.await() }
        if (nlu != null) {
            onEvent(OnDeviceArbiterEvent.Received("local"))
            // 拒识（语音拒识 = unknown 意图，需求 1 静默）：本地未命中语义不参与仲裁
            // 胜出——云端超时场景直接失败（不执行不播报），避免 unknown 兜底胜出
            if (nlu.intent.isUnknown()) {
                onEvent(OnDeviceArbiterEvent.Lost("local", "unknown_intent"))
                return RaceWinner.Failed
            }
            if (!claimSemantic(turnId, "local")) return RaceWinner.Intercepted
            onEvent(OnDeviceArbiterEvent.Won("local", "cloud_timeout"))
            sink.onDecision(decision(turnId, route = "local", reason = "cloud_timeout_use_local"))
            // 云端语义若在超时窗口边缘迟到（本地先赢）→ 记失败（已有命令词胜出）
            if (completedValue(cloud) != null) {
                onEvent(OnDeviceArbiterEvent.Received("cloud"))
                onEvent(OnDeviceArbiterEvent.Lost("cloud", "command_already_won"))
            }
            return RaceWinner.Local(nlu)
        }

        sink.onDecision(decision(turnId, route = "local", reason = "both_failed"))
        return RaceWinner.Failed
    }

    /** 本地单链等绕过 race 的路径也必须经过同一份按轮输出账本。 */
    fun claimSemantic(turnId: String, route: String): Boolean {
        val accepted = emissionLedger.tryEmit(turnId) == SemanticEmissionResult.ACCEPTED
        if (!accepted) onEvent(OnDeviceArbiterEvent.Lost(route, "turn_already_output"))
        return accepted
    }

    /** 输家已完成（成功取到值）→ 语义确实到达过；被取消/未完成/异常返回 null（不算到达）。 */
    private fun <T> completedValue(d: Deferred<T>): T? =
        if (d.isCompleted && !d.isCancelled) runCatching { d.getCompleted() }.getOrNull() else null

    private fun decision(turnId: String, route: String, reason: String): DecisionEntry =
        DecisionEntry(
            arbiter = "on-device",
            route = route,
            reason = reason,
            utteranceId = turnId,
            timestampMs = clock(),
        )
}

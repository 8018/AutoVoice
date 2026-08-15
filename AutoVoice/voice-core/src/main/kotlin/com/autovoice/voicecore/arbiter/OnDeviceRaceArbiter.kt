package com.autovoice.voicecore.arbiter

import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.Reply
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
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
    data class Local(val intent: Intent) : RaceWinner()

    /** 云端与本地均超时。 */
    data object Failed : RaceWinner()

    /**
     * 语义被拦截（B2 需求 2/4）：语义到达时 utteranceId 已刷新（非最新轮会话）——
     * 结果属于已过期的轮，丢弃不执行（应用层静默回 IDLE，不播报不执行）。
     */
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
     * not_latest_round 不是最新轮会话（语义被拦截）。
     */
    data class Lost(val route: String, val reason: String) : OnDeviceArbiterEvent()
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
 *  - **非最新轮拦截**：race 进入快照 [utteranceId]，候选到达时若 utteranceId 已刷新
 *    （新一轮 vad start 产生的 uuid），语义属于过期轮 → 记 [OnDeviceArbiterEvent.Lost]
 *    （`not_latest_round` 不是最新轮会话）并返回 [RaceWinner.Intercepted]（丢弃）。
 *
 * 决策日志经 [DecisionSink] 写出：arbiter = `on-device`，
 * utteranceId 由注入的 [utteranceId] provider 提供（装配方绑到会话的
 * [com.autovoice.voicecore.session.VoiceSession.currentUtteranceId]，T7 起填真实值；
 * 未注入时默认空串），timestampMs 用注入的 [clock]。
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
    /** 本轮话语 utteranceId 读取器（T7）：装配方绑到会话的 currentUtteranceId；默认空串保兼容。 */
    private val utteranceId: () -> String = { "" },
    /** B2：仲裁过程事件（收到候选/胜出/失败）→ 装配方转 telemetry 插桩。默认 no-op。 */
    private val onEvent: (OnDeviceArbiterEvent) -> Unit = {},
) {
    /** 阶段 1 收敛结果（能力分级 2026-08-15）：本地车窗开关 / 云端语义。 */
    private sealed interface Outcome {
        /** 本地车窗开关到达（能力分级：直接胜出，不等云端）。 */
        data class LocalWin(val intent: Intent) : Outcome

        /** 云端语义到达（云端优先胜出）。 */
        data class Cloud(val reply: Reply) : Outcome
    }

    suspend fun race(cloud: Deferred<Reply>, local: Deferred<Intent>): RaceWinner {
        // B2：非最新轮拦截——快照本轮 utteranceId，候选到达时若已过期则丢弃
        val uidAtStart = utteranceId()

        // 阶段 1（cloudWaitMs 窗口，并发等 cloud 与 local，能力分级 2026-08-15）：
        // 两端结果经 Channel 汇合，先注册本地分支（同时完成时本地车窗优先）。
        // 本地车窗开关 → 送 LocalWin 立即胜出（不等云端）；本地非车窗（unknown 拒识 /
        // misc 防御）→ 不发结果，不参与胜出只等云端；云端语义 → 送 Cloud 胜出。
        // 首个真实候选即收敛；协程语义同旧实现：超时只把 withTimeoutOrNull 转换为
        // null，取消原样向上传播，不吞 CancellationException。
        val outcome = withTimeoutOrNull(cloudWaitMs) {
            coroutineScope {
                val results = Channel<Outcome>(capacity = 2)
                val localJob = launch {
                    local.await().takeIf { it.isWindowPower() }
                        ?.let { results.send(Outcome.LocalWin(it)) }
                }
                val cloudJob = launch { results.send(Outcome.Cloud(cloud.await())) }
                val winner = results.receive()
                // 已收敛：取消仍挂在 await 上的输家子协程，coroutineScope 才会返回
                // （await 的取消不影响 deferred 本身——deferred 归调用方所有）
                localJob.cancel()
                cloudJob.cancel()
                winner
            }
        }

        when (outcome) {
            // 本地车窗开关先到 → 能力分级直接胜出（reason = local_command_won，不等云端）
            is Outcome.LocalWin -> {
                onEvent(OnDeviceArbiterEvent.Received("local"))
                if (isStale(uidAtStart)) {
                    onEvent(OnDeviceArbiterEvent.Lost("local", "not_latest_round"))
                    return RaceWinner.Intercepted
                }
                onEvent(OnDeviceArbiterEvent.Won("local", "local_command"))
                sink.onDecision(decision(route = "local", reason = "local_command_won"))
                // 单赢家：云端语义若已同时到达（本地先赢）→ 记失败（已有命令词胜出）
                if (completedValue(cloud) != null) {
                    onEvent(OnDeviceArbiterEvent.Received("cloud"))
                    onEvent(OnDeviceArbiterEvent.Lost("cloud", "command_already_won"))
                }
                return RaceWinner.Local(outcome.intent)
            }

            // 云端语义先到 → 云端胜出（reason = cloud_won，现逻辑）
            is Outcome.Cloud -> {
                onEvent(OnDeviceArbiterEvent.Received("cloud"))
                if (isStale(uidAtStart)) {
                    onEvent(OnDeviceArbiterEvent.Lost("cloud", "not_latest_round"))
                    return RaceWinner.Intercepted
                }
                onEvent(OnDeviceArbiterEvent.Won("cloud", "priority"))
                sink.onDecision(decision(route = "cloud", reason = "cloud_won"))
                // 单赢家：本地命令词若已到达（云端先赢）→ 记失败（已有云端语义胜出）
                if (completedValue(local) != null) {
                    onEvent(OnDeviceArbiterEvent.Received("local"))
                    onEvent(OnDeviceArbiterEvent.Lost("local", "cloud_already_won"))
                }
                return RaceWinner.Cloud(outcome.reply)
            }

            // 阶段 1 超时（cloudWaitMs 内无胜出）→ 阶段 2 本地兜底（现逻辑）
            null -> {
                val intent = withTimeoutOrNull(localFallbackMs) { local.await() }
                if (intent != null) {
                    onEvent(OnDeviceArbiterEvent.Received("local"))
                    if (isStale(uidAtStart)) {
                        onEvent(OnDeviceArbiterEvent.Lost("local", "not_latest_round"))
                        return RaceWinner.Intercepted
                    }
                    // 拒识（语音拒识 = unknown 意图，需求 1 静默）：本地未命中语义不参与仲裁
                    // 胜出——云端超时场景直接失败（不执行不播报），避免 unknown 兜底胜出
                    if (intent.isUnknown()) {
                        onEvent(OnDeviceArbiterEvent.Lost("local", "unknown_intent"))
                        return RaceWinner.Failed
                    }
                    onEvent(OnDeviceArbiterEvent.Won("local", "cloud_timeout"))
                    sink.onDecision(decision(route = "local", reason = "cloud_timeout_use_local"))
                    // 云端语义若在超时窗口边缘迟到（本地先赢）→ 记失败（已有命令词胜出）
                    if (completedValue(cloud) != null) {
                        onEvent(OnDeviceArbiterEvent.Received("cloud"))
                        onEvent(OnDeviceArbiterEvent.Lost("cloud", "command_already_won"))
                    }
                    return RaceWinner.Local(intent)
                }

                sink.onDecision(decision(route = "local", reason = "both_failed"))
                return RaceWinner.Failed
            }
        }
    }

    /** B2：语义是否属于过期轮（utteranceId 已刷新）。provider 空（未接 telemetry）时不拦截。 */
    private fun isStale(uidAtStart: String): Boolean =
        uidAtStart.isNotBlank() && uidAtStart != utteranceId()

    /** 输家已完成（成功取到值）→ 语义确实到达过；被取消/未完成/异常返回 null（不算到达）。 */
    private fun <T> completedValue(d: Deferred<T>): T? =
        if (d.isCompleted && !d.isCancelled) runCatching { d.getCompleted() }.getOrNull() else null

    private fun decision(route: String, reason: String): DecisionEntry =
        DecisionEntry(
            arbiter = "on-device",
            route = route,
            reason = reason,
            utteranceId = utteranceId(),
            timestampMs = clock(),
        )
}

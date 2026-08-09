package com.autovoice.voicecore.arbiter

import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.Reply
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 端侧竞速仲裁结果（spec §5.1 修订，Task 64 本地优先）：本地意图 / 云端回复 / 双败。
 */
sealed class RaceWinner {
    /** 本地链识别出命令词（非 unknown 意图），本地优先立即胜出。 */
    data class Local(val intent: Intent) : RaceWinner()

    /** 本地未命中（非命令词 → unknown）后，云端回复在 cloudWaitMs 内到达。 */
    data class Cloud(val reply: Reply) : RaceWinner()

    /** 本地未命中且云端超时。 */
    data object Failed : RaceWinner()
}

/**
 * 端侧竞速仲裁器（spec §5.1 修订，Task 64 本地优先）：本地命令词命中即胜，云端语义兜底。
 *
 * 背景：云端优先（原实现）下离线命令词虽 1s 内识别出，云端 ASR+LLM+TTS 却在
 * cloudWaitMs（5s）内先收敛 → 本地结果每次被云端覆盖，用户体感"本地链路没开"。
 *
 * 收敛规则：
 *  - `withTimeoutOrNull(cloudWaitMs) { local.await() }` 返回**非 unknown 意图** →
 *    [RaceWinner.Local]（reason = `local_won`）：离线命令词立即生效，不等云端；
 *  - 本地未命中（unknown 意图，非命令词属正常语义）或本地超时 → 等云端
 *    `withTimeoutOrNull(cloudWaitMs) { cloud.await() }` → [RaceWinner.Cloud]
 *    （reason = `cloud_won`）；
 *  - 本地未命中且云端超时 → [RaceWinner.Failed]（reason = `both_failed`）。
 *
 * 最坏收敛时长 = 2 × cloudWaitMs（本地窗口 + 云端窗口）；[localFallbackMs] 已废弃
 * （本地未命中即转云端，不再等本地兜底），保留构造参数仅防外部调用方编译破坏。
 *
 * 决策日志经 [DecisionSink] 写出：arbiter = `on-device`，
 * utteranceId 当前无上游会话参数可取，填占位空串，timestampMs 用注入的 [clock]。
 *
 * 协程语义：`withTimeoutOrNull` 只把自身超时转换为 null；块内抛出的
 * [kotlinx.coroutines.CancellationException]（如父协程取消导致 `await` 中断）
 * 会原样向上传播。本实现不做任何 catch，绝不吞掉取消。
 */
class OnDeviceRaceArbiter(
    private val cloudWaitMs: Long = 2000,
    @Deprecated("Task 64 本地优先后不再等待本地兜底；保留参数防外部调用方编译破坏")
    private val localFallbackMs: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sink: DecisionSink,
) {
    suspend fun race(cloud: Deferred<Reply>, local: Deferred<Intent>): RaceWinner {
        // 本地优先：本地命令词命中（非 unknown）→ 立即胜出，不等云端
        val localIntent = withTimeoutOrNull(cloudWaitMs) { local.await() }
        if (localIntent != null && !localIntent.isUnknown()) {
            sink.onDecision(decision(route = "local", reason = "local_won"))
            return RaceWinner.Local(localIntent)
        }

        // 本地未命中/超时 → 云端语义兜底
        val reply = withTimeoutOrNull(cloudWaitMs) { cloud.await() }
        if (reply != null) {
            sink.onDecision(decision(route = "cloud", reason = "cloud_won"))
            return RaceWinner.Cloud(reply)
        }

        sink.onDecision(decision(route = "local", reason = "both_failed"))
        return RaceWinner.Failed
    }

    private fun decision(route: String, reason: String): DecisionEntry =
        DecisionEntry(
            arbiter = "on-device",
            route = route,
            reason = reason,
            utteranceId = "",
            timestampMs = clock(),
        )
}

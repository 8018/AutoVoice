package com.autovoice.voicecore.arbiter

import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.Reply
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 端侧竞速仲裁结果（spec §5.1）：云端回复 / 本地意图 / 双败。
 */
sealed class RaceWinner {
    /** 云端回复在 cloudWaitMs 内到达。 */
    data class Cloud(val reply: Reply) : RaceWinner()

    /** 云端超时后，本地意图在 localFallbackMs 内到达。 */
    data class Local(val intent: Intent) : RaceWinner()

    /** 云端与本地均超时。 */
    data object Failed : RaceWinner()
}

/**
 * 端侧竞速仲裁器（spec §5.1）：云端优先，超时后本地兜底。
 *
 * 收敛规则：
 *  - `withTimeoutOrNull(cloudWaitMs) { cloud.await() }` 非空 → [RaceWinner.Cloud]
 *    （reason = `cloud_won`）；
 *  - 云端超时 → `withTimeoutOrNull(localFallbackMs) { local.await() }` 非空 →
 *    [RaceWinner.Local]（reason = `cloud_timeout_use_local`）；
 *  - 两者皆超时 → [RaceWinner.Failed]（reason = `both_failed`）。
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
    private val localFallbackMs: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sink: DecisionSink,
) {
    suspend fun race(cloud: Deferred<Reply>, local: Deferred<Intent>): RaceWinner {
        val reply = withTimeoutOrNull(cloudWaitMs) { cloud.await() }
        if (reply != null) {
            sink.onDecision(decision(route = "cloud", reason = "cloud_won"))
            return RaceWinner.Cloud(reply)
        }

        val intent = withTimeoutOrNull(localFallbackMs) { local.await() }
        if (intent != null) {
            sink.onDecision(decision(route = "local", reason = "cloud_timeout_use_local"))
            return RaceWinner.Local(intent)
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

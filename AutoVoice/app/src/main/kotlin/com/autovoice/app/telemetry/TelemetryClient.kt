package com.autovoice.app.telemetry

import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * 链路数据上报客户端（数据平台一期）：事件包按"轮"聚合——[begin] 开启一轮（携带
 * utteranceId），[record] 追加事件，[end] 批量 POST /api/telemetry/round；[uploadAudio]
 * 单独 POST VAD 后 PCM（multipart）。失败静默（Log.w，绝不抛）；enabled=false 时
 * 全部 no-op（装配方在 telemetry 未配置时传 false，避免处处判空）。
 *
 * [recordFor]（T7 评审 C1）：显式 utteranceId 通道——异步回调（MediaPlayer /
 * UtteranceProgressListener）晚于 [end] 收包的事件无法进当前轮，且可能被并进下一轮；
 * 它按调用方快照的 utteranceId 归属：轮未关闭且匹配 → 进 round events 随 [end] 一并
 * POST；轮已关闭 / 跨轮 → 直接异步 POST 单事件到 /api/telemetry/events（Task 3 服务端
 * 端点，body {utteranceId, events[]}，按 utterance_id 汇合到已有 round，不新建轮）。
 *
 * 线程安全：begin/record/recordFor/end 由多个协程调用，内部 @Synchronized 串行；
 * 收包后仅向有界上传器入队，不在调用线程做 HTTP。JSON 上传保持入队顺序。
 */
class TelemetryClient(
    okHttp: OkHttpClient,
    baseUrl: String,
    private val deviceId: String?,
    scope: CoroutineScope,
    private val enabled: Boolean = true,
    /**
     * 打戳时钟（时钟同步）：默认设备墙钟；装配方可注入 `{ 设备时间 + 网关握手估算的时钟偏移 }`
     * 把事件时间戳统一换算为服务器时钟（ready.serverTime 协议）。
     */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val uploader = if (enabled) {
        TelemetryUploadDispatcher(scope, RetrofitTelemetryTransport(okHttp, baseUrl))
    } else {
        null
    }

    /** barge-in 允许旧轮候选与新轮重叠，因此 telemetry 也按 utteranceId 并发持有。 */
    private val rounds = LinkedHashMap<String, CurrentRound>()
    private var activeUtteranceId: String = ""
    private var sessionId: String = ""
    private var closed = false

    @Synchronized
    fun onSessionId(id: String) {
        if (!enabled || closed) return
        sessionId = id
    }

    /** 开启一轮新话语的事件包；同 utteranceId 的 [end] 才会收包。 */
    @Synchronized
    fun begin(utteranceId: String) {
        if (!enabled || closed) return
        if (utteranceId.isBlank()) return
        if (rounds.size >= MAX_OPEN_ROUNDS && utteranceId !in rounds) {
            end(rounds.keys.first(), "capacity_evicted")
        }
        rounds[utteranceId] = CurrentRound(utteranceId, clock(), mutableListOf())
        activeUtteranceId = utteranceId
    }

    /** 追加一条事件到当前轮（未 [begin] 时丢弃，防御）。 */
    @Synchronized
    fun record(stage: String, level: String, payload: Map<String, Any?>) {
        if (!enabled || closed) return
        val round = rounds[activeUtteranceId] ?: return
        round.events.add(event(stage, level, payload))
    }

    /**
     * 追加一条事件到指定 utteranceId（T7 评审 C1）：当前轮未关闭且 utteranceId 匹配 →
     * 进 round events（随 [end] 一并 POST）；轮已关闭或 utteranceId 不匹配（跨轮迟到事件，
     * 如播放完成/失败的异步回调晚于 end()）→ 直接异步 POST 单事件到 /api/telemetry/events，
     * 服务端按 utterance_id 汇合到已有 round（Task 3 /events 端点，不新建轮）。
     */
    @Synchronized
    fun recordFor(utteranceId: String, stage: String, level: String, payload: Map<String, Any?>) {
        if (!enabled || closed) return
        val round = rounds[utteranceId]
        if (round != null) {
            round.events.add(event(stage, level, payload))
            return
        }
        // 轮已关闭/跨轮：单事件直传（events 数组与 round 事件同构 {stage,tsMs,level,payload}）
        uploader?.postEvents(TelemetryEventBatch(utteranceId, listOf(event(stage, level, payload))))
    }

    /** 收包并 POST /api/telemetry/round（异步；utteranceId 与 begin 不一致时不收）。 */
    @Synchronized
    fun end(utteranceId: String, reason: String? = null) {
        if (!enabled || closed) return
        val round = rounds.remove(utteranceId) ?: return
        if (reason != null) round.events.add(event("turn_finished", "info", mapOf("reason" to reason)))
        if (activeUtteranceId == utteranceId) {
            activeUtteranceId = rounds.keys.lastOrNull().orEmpty()
        }
        uploader?.postRound(TelemetryRoundPayload(
            utteranceId = round.utteranceId,
            sessionId = sessionId,
            deviceId = deviceId ?: "",
            source = "button",
            startMs = round.startMs,
            endMs = clock(),
            events = round.events.toList(),
        ))
    }

    @Synchronized
    fun finishOpenRounds(reason: String) {
        if (!enabled || closed) return
        rounds.keys.toList().forEach { end(it, reason) }
    }

    /** No new uploads after close; queued telemetry is allowed to finish independently of VoiceEngine. */
    @Synchronized
    fun close() {
        if (closed) return
        finishOpenRounds("client_closed")
        closed = true
        rounds.clear()
        activeUtteranceId = ""
        uploader?.close()
    }

    /** 单独上传 VAD 后 PCM（multipart：utteranceId + deviceId + <id>.pcm，异步）。 */
    @Synchronized
    fun uploadAudio(utteranceId: String, pcm: ByteArray) {
        if (!enabled || closed) return
        uploader?.postAudio(utteranceId, deviceId, pcm)
    }

    /** Capture immutable values before the upload leaves the caller's thread. */
    private fun event(stage: String, level: String, payload: Map<String, Any?>): TelemetryEventPayload =
        TelemetryEventPayload(stage, clock(), level, payload.toMap())

    private class CurrentRound(
        val utteranceId: String,
        val startMs: Long,
        val events: MutableList<TelemetryEventPayload>,
    )

    companion object {
        private const val MAX_OPEN_ROUNDS = 64
    }
}

package com.autovoice.app.telemetry

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

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
 * 线程安全：begin/record/recordFor/end 由装配方在多个协程（主线程 / 仲裁器 / 网关桥 /
 * 播放线程）调用，内部 @Synchronized 串行；HTTP 发送切 Dispatchers.IO，end 先把
 * current 置 null 再异步上传（不重复收包，也不阻塞调用方）。
 */
class TelemetryClient(
    private val okHttp: OkHttpClient,
    private val baseUrl: String,
    private val deviceId: String?,
    private val scope: CoroutineScope,
    private val enabled: Boolean = true,
    /**
     * 打戳时钟（时钟同步）：默认设备墙钟；装配方可注入 `{ 设备时间 + 网关握手估算的时钟偏移 }`
     * 把事件时间戳统一换算为服务器时钟（ready.serverTime 协议）。
     */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** barge-in 允许旧轮候选与新轮重叠，因此 telemetry 也按 utteranceId 并发持有。 */
    private val rounds = LinkedHashMap<String, CurrentRound>()
    private var activeUtteranceId: String = ""
    private var sessionId: String = ""

    @Synchronized
    fun onSessionId(id: String) {
        if (!enabled) return
        sessionId = id
    }

    /** 开启一轮新话语的事件包；同 utteranceId 的 [end] 才会收包。 */
    @Synchronized
    fun begin(utteranceId: String) {
        if (!enabled) return
        rounds[utteranceId] = CurrentRound(utteranceId, clock(), mutableListOf())
        activeUtteranceId = utteranceId
    }

    /** 追加一条事件到当前轮（未 [begin] 时丢弃，防御）。 */
    @Synchronized
    fun record(stage: String, level: String, payload: Map<String, Any?>) {
        if (!enabled) return
        val round = rounds[activeUtteranceId] ?: return
        round.events.add(eventJson(stage, level, payload))
    }

    /**
     * 追加一条事件到指定 utteranceId（T7 评审 C1）：当前轮未关闭且 utteranceId 匹配 →
     * 进 round events（随 [end] 一并 POST）；轮已关闭或 utteranceId 不匹配（跨轮迟到事件，
     * 如播放完成/失败的异步回调晚于 end()）→ 直接异步 POST 单事件到 /api/telemetry/events，
     * 服务端按 utterance_id 汇合到已有 round（Task 3 /events 端点，不新建轮）。
     */
    @Synchronized
    fun recordFor(utteranceId: String, stage: String, level: String, payload: Map<String, Any?>) {
        if (!enabled) return
        val round = rounds[utteranceId]
        if (round != null) {
            round.events.add(eventJson(stage, level, payload))
            return
        }
        // 轮已关闭/跨轮：单事件直传（events 数组与 round 事件同构 {stage,tsMs,level,payload}）
        postJson(
            "/api/telemetry/events",
            JSONObject()
                .put("utteranceId", utteranceId)
                .put("events", JSONArray(listOf(eventJson(stage, level, payload)))),
        )
    }

    /** 收包并 POST /api/telemetry/round（异步；utteranceId 与 begin 不一致时不收）。 */
    @Synchronized
    fun end(utteranceId: String) {
        if (!enabled) return
        val round = rounds.remove(utteranceId) ?: return
        if (activeUtteranceId == utteranceId) {
            activeUtteranceId = rounds.keys.lastOrNull().orEmpty()
        }
        postJson(
            "/api/telemetry/round",
            JSONObject()
                .put("utteranceId", round.utteranceId)
                .put("sessionId", sessionId)
                .put("deviceId", deviceId ?: "")
                .put("source", "button")
                .put("startMs", round.startMs)
                .put("endMs", clock())
                .put("events", JSONArray(round.events)),
        )
    }

    /** 单独上传 VAD 后 PCM（multipart：utteranceId + <id>.pcm，异步）。 */
    @Synchronized
    fun uploadAudio(utteranceId: String, pcm: ByteArray) {
        if (!enabled) return
        scope.launch(Dispatchers.IO) {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("utteranceId", utteranceId)
                    .addFormDataPart(
                        "file",
                        "$utteranceId.pcm",
                        pcm.toRequestBody("application/octet-stream".toMediaType()),
                    )
                    .build()
                val req = Request.Builder()
                    .url("$baseUrl/api/telemetry/audio")
                    .post(body)
                    .build()
                okHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "telemetry audio upload failed: http ${resp.code}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "telemetry audio upload failed", t)
            }
        }
    }

    /** 单条事件 JSON（round events 与 /events 直传同构：{stage,tsMs,level,payload}）。 */
    private fun eventJson(stage: String, level: String, payload: Map<String, Any?>): JSONObject =
        JSONObject()
            .put("stage", stage)
            .put("tsMs", clock())
            .put("level", level)
            .put("payload", JSONObject(payload))

    /** POST JSON 到数据平台（异步；失败静默 Log.w，绝不抛）。 */
    private fun postJson(path: String, body: JSONObject) {
        scope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("$baseUrl$path")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                okHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "telemetry $path upload failed: http ${resp.code}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "telemetry $path upload failed", t)
            }
        }
    }

    private class CurrentRound(
        val utteranceId: String,
        val startMs: Long,
        val events: MutableList<JSONObject>,
    )

    companion object {
        private const val TAG = "TelemetryClient"
    }
}

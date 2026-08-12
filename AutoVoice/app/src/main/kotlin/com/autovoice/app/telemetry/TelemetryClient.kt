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
 * 线程安全：begin/record/end 由装配方在多个协程（主线程 / 仲裁器 / 网关桥）调用，
 * 内部 @Synchronized 串行；HTTP 发送切 Dispatchers.IO，end 先把 current 置 null
 * 再异步上传（不重复收包，也不阻塞调用方）。
 */
class TelemetryClient(
    private val okHttp: OkHttpClient,
    private val baseUrl: String,
    private val deviceId: String?,
    private val scope: CoroutineScope,
    private val enabled: Boolean = true,
) {
    private var current: CurrentRound? = null
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
        current = CurrentRound(utteranceId, System.currentTimeMillis(), mutableListOf())
    }

    /** 追加一条事件到当前轮（未 [begin] 时丢弃，防御）。 */
    @Synchronized
    fun record(stage: String, level: String, payload: Map<String, Any?>) {
        if (!enabled) return
        val round = current ?: return
        round.events.add(
            JSONObject()
                .put("stage", stage)
                .put("tsMs", System.currentTimeMillis())
                .put("level", level)
                .put("payload", JSONObject(payload)),
        )
    }

    /** 收包并 POST /api/telemetry/round（异步；utteranceId 与 begin 不一致时不收）。 */
    @Synchronized
    fun end(utteranceId: String) {
        if (!enabled) return
        val round = current ?: return
        if (round.utteranceId != utteranceId) return
        current = null
        val body = JSONObject()
            .put("utteranceId", round.utteranceId)
            .put("sessionId", sessionId)
            .put("deviceId", deviceId ?: "")
            .put("source", "button")
            .put("startMs", round.startMs)
            .put("endMs", System.currentTimeMillis())
            .put("events", JSONArray(round.events))
        scope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("$baseUrl/api/telemetry/round")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                okHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "telemetry round upload failed: http ${resp.code}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "telemetry round upload failed", t)
            }
        }
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

    private class CurrentRound(
        val utteranceId: String,
        val startMs: Long,
        val events: MutableList<JSONObject>,
    )

    companion object {
        private const val TAG = "TelemetryClient"
    }
}

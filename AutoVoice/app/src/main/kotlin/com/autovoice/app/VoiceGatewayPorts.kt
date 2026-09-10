package com.autovoice.app

import com.autovoice.voicecore.AudioReply

/**
 * 独立 TTS 播报请求（TTS 解耦 v1.1）：设备执行 intent 后按 speakText 向服务端
 * 请求合成音频；返回 null = 失败/超时（调用方静默处理并记失败事件，不重试）。
 * 生产实现：GatewayCloudRunner（tts_request/tts_response 独立槽）。
 */
fun interface TtsRequester {
    suspend fun request(text: String): AudioReply?

    /** 使用发起播报的轮次快照，避免并发新轮覆盖 telemetry 归属。 */
    suspend fun request(text: String, utteranceId: String): AudioReply? = request(text)
}

/** 闲聊域长连接：start 后 PCM 可在模型播报期间持续 append。 */
internal interface RealtimeChatRunner {
    suspend fun startRealtimeChat()
    fun appendRealtimeAudio(pcm: ByteArray)
    fun finishRealtimeChat()
}

/** 普通业务域的一轮实时上行；与持续全双工闲聊相互独立。 */
interface StreamingCloudRunner {
    fun beginStreamingTurn(utteranceId: String)
    fun appendStreamingAudio(pcm: ByteArray)
    fun finishStreamingTurn(utteranceId: String)
    fun cancelStreamingTurn(utteranceId: String)
    /** Candidate audio has been admitted as a business turn by ASR or final semantic evidence. */
    fun commitStreamingTurn(utteranceId: String) {}
}

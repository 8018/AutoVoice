package com.autovoice.app.telemetry

/**
 * 端侧链路插桩阶段常量（与服务端 telemetry 落库的 stage 字符串一致，见
 * AutoVoiceServer :contracts 的 TelemetryStages——T7 已逐串核对对齐）。
 * 端侧记录 utterance_start / vad / local_asr / execute / tts_play_request /
 * tts_play_start / tts_play_interrupted / tts_play_end / device_arbiter /
 * cloud_arbiter；cloud_asr / offline_pool / llm / tts_cache_check/hit/miss /
 * tts_synth_request/ok/failed 由服务端插桩（T4/T5/B4），端侧不重复定义。
 */
object TelemetryStages {
    /** 话语开始（onListeningStart 生成 utteranceId 后立即记录）。 */
    const val UTTERANCE_START = "utterance_start"

    /** VAD 切出的语音段（onTurnSegment，含字节数/时长与轮内聚合统计）。 */
    const val VAD = "vad"

    /**
     * VAD 语音段开始（录音实时，SpeechStart 触发）：本轮第一个段产生 utteranceId
     * （vad start 的 uuid 就是 utteranceId，单一 id 贯穿全轮），并同步到端云仲裁器
     * 做非最新轮拦截（B2）。
     */
    const val VAD_START = "vad_start"

    /** VAD 语音段结束（录音实时，SpeechEnd 触发，与 [VAD_START] 配对）。 */
    const val VAD_END = "vad_end"

    /** 本地 ASR + NLU（buildLocalChain，识别文本/意图/耗时）。 */
    const val LOCAL_ASR = "local_asr"

    /** 意图执行（车控 apply：applied/skipped；全败兜底：failed）。 */
    const val EXECUTE = "execute"

    /** TTS 播报请求（B4 需求 1：speakViaTts 发出播报请求，含文本）。 */
    const val TTS_PLAY_REQUEST = "tts_play_request"

    /** TTS 播放开始（B4：MediaPlayer 实际起播，network 播放）。 */
    const val TTS_PLAY_START = "tts_play_start"

    /** TTS 播放中断（B4：stop/新播放打断）。 */
    const val TTS_PLAY_INTERRUPTED = "tts_play_interrupted"

    /** TTS 播放结束（B4：completed ok / failed error；system 兜底播报结果也用此 stage）。 */
    const val TTS_PLAY_END = "tts_play_end"

    /** 端侧仲裁器决策（OnDeviceRaceArbiter / VoiceSession 的 on-device 条目）。 */
    const val DEVICE_ARBITER = "device_arbiter"

    /** 端侧仲裁收到候选（B2 需求 4：route=cloud 收到云端语义 / route=local 收到本地 ASR 命令词）。 */
    const val DEVICE_ARBITER_RECEIVED = "device_arbiter_received"

    /** 端侧仲裁胜出（B2：route + 原因 priority 优先 / cloud_timeout 超时未收到云端）。 */
    const val DEVICE_ARBITER_WON = "device_arbiter_won"

    /** 端侧仲裁失败（B2：route + 原因 cloud_already_won / command_already_won / not_latest_round）。 */
    const val DEVICE_ARBITER_LOST = "device_arbiter_lost"

    /** 云端仲裁器决策（网关下行 decision 事件，arbiter=cloud）。 */
    const val CLOUD_ARBITER = "cloud_arbiter"
}

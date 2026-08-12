package com.autovoice.app.telemetry

/**
 * 端侧链路插桩阶段常量（与服务端 telemetry 落库的 stage 字符串一致，见
 * AutoVoiceServer :contracts 的 TelemetryStages——T7 已逐串核对对齐）。
 * 端侧记录 utterance_start / vad / local_asr / execute / tts_request /
 * tts_play / device_arbiter / cloud_arbiter；cloud_asr / offline_pool / llm /
 * tts_cache / tts_synth 由服务端插桩（T4/T5），端侧不重复定义。
 */
object TelemetryStages {
    /** 话语开始（onListeningStart 生成 utteranceId 后立即记录）。 */
    const val UTTERANCE_START = "utterance_start"

    /** VAD 切出的语音段（onTurnSegment，含字节数/时长与轮内聚合统计）。 */
    const val VAD = "vad"

    /** 本地 ASR + NLU（buildLocalChain，识别文本/意图/耗时）。 */
    const val LOCAL_ASR = "local_asr"

    /** 意图执行（车控 apply：applied/skipped；全败兜底：failed）。 */
    const val EXECUTE = "execute"

    /** 独立 TTS 合成请求（speakViaTts 的 tts.request）。 */
    const val TTS_REQUEST = "tts_request"

    /** TTS 播报（network=云端音频播放 / system=系统 TTS 兜底，start/completed/failed/interrupted）。 */
    const val TTS_PLAY = "tts_play"

    /** 端侧仲裁器决策（OnDeviceRaceArbiter / VoiceSession 的 on-device 条目）。 */
    const val DEVICE_ARBITER = "device_arbiter"

    /** 云端仲裁器决策（网关下行 decision 事件，arbiter=cloud）。 */
    const val CLOUD_ARBITER = "cloud_arbiter"
}

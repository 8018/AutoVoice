package com.autovoice.app.telemetry

/**
 * 端侧链路插桩阶段常量（与服务端 telemetry 落库的 stage 字符串一致，见
 * AutoVoiceServer 的 telemetry 模块）。端侧记录 utterance_start / vad /
 * device_arbiter / cloud_arbiter；cloud_asr / offline_pool / llm 由服务端
 * 插桩（T4/T5），此处不重复定义。
 */
object TelemetryStages {
    /** 话语开始（onListeningStart 生成 utteranceId 后立即记录）。 */
    const val UTTERANCE_START = "utterance_start"

    /** VAD 切出的语音段（onTurnSegment，含字节数与时长）。 */
    const val VAD = "vad"

    /** 端侧仲裁器决策（OnDeviceRaceArbiter / VoiceSession 的 on-device 条目）。 */
    const val DEVICE_ARBITER = "device_arbiter"

    /** 云端仲裁器决策（网关下行 decision 事件，arbiter=cloud）。 */
    const val CLOUD_ARBITER = "cloud_arbiter"
}

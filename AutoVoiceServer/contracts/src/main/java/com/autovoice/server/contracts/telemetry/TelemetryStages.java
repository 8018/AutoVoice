package com.autovoice.server.contracts.telemetry;

/** 链路追踪阶段枚举（TelemetryEvent.stage 取值）。 */
public final class TelemetryStages {
    public static final String UTTERANCE_START = "utterance_start";
    public static final String VAD = "vad";
    public static final String LOCAL_ASR = "local_asr";
    public static final String CLOUD_ASR = "cloud_asr";
    public static final String LLM = "llm";
    public static final String OFFLINE_POOL = "offline_pool";
    public static final String CLOUD_ARBITER = "cloud_arbiter";
    /** 云端仲裁收到候选（B3 需求 3：route=nlu-traditional 收到 asr 命令词 / llm 收到 llm 语义）。 */
    public static final String CLOUD_ARBITER_RECEIVED = "cloud_arbiter_received";
    /** 云端仲裁胜出（B3：route + reason=priority 优先 / llm_timeout 超时未收到 llm + decision 决策 reason）。 */
    public static final String CLOUD_ARBITER_WON = "cloud_arbiter_won";
    /** 云端仲裁失败（B3：route + reason=llm_already_won / command_already_won / not_latest_round）。 */
    public static final String CLOUD_ARBITER_LOST = "cloud_arbiter_lost";
    public static final String DEVICE_ARBITER = "device_arbiter";
    public static final String EXECUTE = "execute";
    public static final String TTS_REQUEST = "tts_request";
    public static final String TTS_CACHE = "tts_cache";
    public static final String TTS_SYNTH = "tts_synth";
    public static final String TTS_PLAY = "tts_play";

    private TelemetryStages() {
    }
}

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
    public static final String DEVICE_ARBITER = "device_arbiter";
    public static final String EXECUTE = "execute";
    public static final String TTS_REQUEST = "tts_request";
    public static final String TTS_CACHE = "tts_cache";
    public static final String TTS_SYNTH = "tts_synth";
    public static final String TTS_PLAY = "tts_play";

    private TelemetryStages() {
    }
}

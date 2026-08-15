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
    /** 云端仲裁 LLM 处理中占位（B5：离线未命中空调且 LLM 未完成 → 下发 pending，非收敛事件）。 */
    public static final String CLOUD_ARBITER_PENDING = "cloud_arbiter_pending";
    public static final String DEVICE_ARBITER = "device_arbiter";
    public static final String EXECUTE = "execute";
    /** TTS 播报请求（B4 需求 1：端侧 speakViaTts 发出播报请求 / 服务器 tts-server 收到）。 */
    public static final String TTS_PLAY_REQUEST = "tts_play_request";
    /** TTS 缓存检查（B4：合成前查缓存）。 */
    public static final String TTS_CACHE_CHECK = "tts_cache_check";
    /** TTS 缓存命中（B4：直接回放缓存）。 */
    public static final String TTS_CACHE_HIT = "tts_cache_hit";
    /** TTS 缓存未命中（B4：委托底层合成）。 */
    public static final String TTS_CACHE_MISS = "tts_cache_miss";
    /** TTS 生成请求（B4：发起语音合成）。 */
    public static final String TTS_SYNTH_REQUEST = "tts_synth_request";
    /** TTS 生成成功（B4：合成完成返回音频）。 */
    public static final String TTS_SYNTH_OK = "tts_synth_ok";
    /** TTS 生成失败（B4：合成异常/超时）。 */
    public static final String TTS_SYNTH_FAILED = "tts_synth_failed";
    /** TTS 播放开始（B4：MediaPlayer 实际起播）。 */
    public static final String TTS_PLAY_START = "tts_play_start";
    /** TTS 播放中断（B4：stop/新播放打断）。 */
    public static final String TTS_PLAY_INTERRUPTED = "tts_play_interrupted";
    /** TTS 播放结束（B4：completed ok / failed error）。 */
    public static final String TTS_PLAY_END = "tts_play_end";

    private TelemetryStages() {
    }
}

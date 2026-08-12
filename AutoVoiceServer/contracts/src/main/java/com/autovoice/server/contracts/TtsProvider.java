package com.autovoice.server.contracts;

/** TTS Provider SPI：文本 → 音频回复。 */
public interface TtsProvider {

    Reply synthesize(String text, SessionContext ctx);

    /**
     * 带 utteranceId 的合成（telemetry 链路贯通，Task 5）：实现类可重写以透传
     * utteranceId（网关 → tts-server → 缓存/合成插桩）；未重写时回退 2 参版
     * （utteranceId 视为空，行为不变）。
     */
    default Reply synthesize(String text, SessionContext ctx, String utteranceId) {
        return synthesize(text, ctx);
    }
}

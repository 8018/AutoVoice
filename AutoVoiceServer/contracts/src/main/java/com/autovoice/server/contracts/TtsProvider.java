package com.autovoice.server.contracts;

/** TTS Provider SPI：文本 → 音频回复。 */
public interface TtsProvider {

    Reply synthesize(String text, SessionContext ctx);
}

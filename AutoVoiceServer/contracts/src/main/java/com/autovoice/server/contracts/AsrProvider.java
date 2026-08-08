package com.autovoice.server.contracts;

/** ASR Provider SPI：PCM（S16LE / 16 kHz / 单声道）→ 识别文本，同步执行，失败抛 {@link AsrException}。 */
public interface AsrProvider {

    String transcribe(byte[] pcm16k, SessionContext ctx);
}

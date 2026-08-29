package com.autovoice.server.contracts;

/** 一通 Realtime 会话；实现必须允许在模型输出期间继续调用 {@link #appendAudio(byte[])}。 */
public interface RealtimeChatSession extends AutoCloseable {

    void appendAudio(byte[] pcm16k);

    @Override
    void close();
}

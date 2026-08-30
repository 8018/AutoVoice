package com.autovoice.server.contracts;

import java.util.concurrent.CompletableFuture;

/** 单轮流式 ASR 会话。实现必须允许在底层 WebSocket 完成握手前 append。 */
public interface StreamingAsrSession {
    void append(byte[] pcm16k);
    CompletableFuture<String> finish();
    void cancel();
}

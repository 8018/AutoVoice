package com.autovoice.server.contracts;

import java.util.concurrent.CompletableFuture;

/** 编译时在线语音后端的一轮流式输入。ASR 可边收边回调，语义在 finish 后收敛。 */
public interface OnlineSpeechStream {
    void append(byte[] pcm16k);
    CompletableFuture<OnlineSpeechResult> finish();
    void cancel();
}

package com.autovoice.server.contracts;

/**
 * 在线 ASR 独立输出。流式 ASR/PGS 可多次发送 partial，最终以 isFinal=true 收敛。
 * 该通道不经过 NLU/语义仲裁门，只受轮次取消与 segmentId 过期检查约束。
 */
public interface OnlineAsrSink {

    OnlineAsrSink NOOP = (text, isFinal) -> {};

    void onResult(String text, boolean isFinal);
}

package com.autovoice.server.contracts;

/**
 * 在线 ASR 独立输出。流式 ASR/PGS 可多次发送 partial，最终以 isFinal=true 收敛。
 * 该通道不经过 NLU/语义仲裁门，只受轮次取消与 segmentId 过期检查约束。
 */
public interface OnlineAsrSink {

    OnlineAsrSink NOOP = (text, isFinal) -> {};

    /** 识别文本只负责展示和 NLU 输入，不隐含新轮成立。 */
    void onResult(String text, boolean isFinal);

    /**
     * ASR/AEC 已确认输入是有效的新话语。会话状态机只消费本事件，不从文本内容推断。
     * 同一 ASR 会话最多发送一次。
     */
    default void onTurnEstablished() {}
}

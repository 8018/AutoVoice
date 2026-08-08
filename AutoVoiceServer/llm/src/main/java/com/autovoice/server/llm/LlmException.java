package com.autovoice.server.llm;

/**
 * LLM 服务侧错误：HTTP 非 2xx、响应无 choices、choices[0].message.content 缺失等。
 *
 * <p>extends {@link RuntimeException}，经 {@link DeepSeekLlmProvider#chat} 的
 * supplyAsync lambda 包装使 future 异常完成，由仲裁（RaceArbiter）的
 * {@code whenComplete} err 分支让位 safety 兜底收敛。</p>
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }
}

package com.autovoice.server.contracts;

import java.util.concurrent.CompletableFuture;

/** LLM Provider SPI：文本 → 回复（文本 / 音频 / 动作意图）。 */
public interface LlmProvider {

    CompletableFuture<Reply> chat(String text, SessionContext ctx);

    /**
     * 带 utteranceId 的入口（telemetry 贯通）：实现方以 utteranceId 记录 llm 事件。
     * 默认转发到 {@link #chat(String, SessionContext)}（旧实现零改动，仅不产生 utteranceId 级事件）。
     */
    default CompletableFuture<Reply> chat(String text, SessionContext ctx, String utteranceId) {
        return chat(text, ctx);
    }
}

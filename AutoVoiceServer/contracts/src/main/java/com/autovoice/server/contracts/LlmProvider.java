package com.autovoice.server.contracts;

import java.util.concurrent.CompletableFuture;

/** LLM Provider SPI：文本 → 回复（文本 / 音频 / 动作意图）。 */
public interface LlmProvider {

    CompletableFuture<Reply> chat(String text, SessionContext ctx);
}

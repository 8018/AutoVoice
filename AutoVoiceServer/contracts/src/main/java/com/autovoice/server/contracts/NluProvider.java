package com.autovoice.server.contracts;

import java.util.concurrent.CompletableFuture;

/** 传统 NLU Provider SPI：文本 → 结构化意图。 */
public interface NluProvider {

    CompletableFuture<Intent> understand(String text, SessionContext ctx);
}

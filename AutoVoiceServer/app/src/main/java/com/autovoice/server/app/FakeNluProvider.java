package com.autovoice.server.app;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.NluProvider;
import com.autovoice.server.contracts.SessionContext;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 演示用 fake NLU（仅 {@code providers.nlu: fake} 时由 {@link AppConfig} 装配）：
 * 文本含"空调"→ climate/set_temperature，否则 unknown（brief 明文）。
 */
final class FakeNluProvider implements NluProvider {

    static final String SOURCE = "nlu.fake";
    private static final String SCHEMA_VERSION = "1.0";

    @Override
    public CompletableFuture<Intent> understand(String text, SessionContext ctx) {
        boolean isAcCommand = text != null && text.contains("空调");
        Intent intent = isAcCommand
                ? Intent.of(SCHEMA_VERSION, "climate", "set_temperature", Map.of(), 1.0, SOURCE, null)
                : Intent.unknown(SOURCE);
        return CompletableFuture.completedFuture(intent);
    }
}

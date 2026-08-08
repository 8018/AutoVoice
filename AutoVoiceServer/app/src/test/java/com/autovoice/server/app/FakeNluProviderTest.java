package com.autovoice.server.app;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.SessionContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FakeNluProvider 契约（brief 明文）：文本含"空调"→ climate/set_temperature，否则 unknown。
 */
class FakeNluProviderTest {

    private final FakeNluProvider provider = new FakeNluProvider();
    private final SessionContext ctx = new SessionContext("s1", "zh-CN", Map.of());

    @Test
    void textContainingAcMapsToClimateSetTemperature() {
        CompletableFuture<Intent> future = provider.understand("把空调调到二十四度", ctx);
        Intent intent = future.join();
        assertFalse(intent.isUnknown());
        assertEquals("climate", intent.domain());
        assertEquals("set_temperature", intent.intent());
        assertEquals(FakeNluProvider.SOURCE, intent.source());
    }

    @Test
    void textWithoutAcIsUnknown() {
        CompletableFuture<Intent> future = provider.understand("今天天气怎么样", ctx);
        Intent intent = future.join();
        assertTrue(intent.isUnknown());
    }
}

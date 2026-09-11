package com.autovoice.server.contracts.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** D01b 统一事件字段契约:兼容构造、缺省归一化与敏感键判定。 */
class TelemetryEventFieldsTest {

    @Test
    void legacyFourArgConstructorKeepsContextFieldsEmpty() {
        TelemetryEvent event =
            new TelemetryEvent("asr", 42L, "info", Map.of("mode", "streaming"));
        assertEquals("", event.subject());
        assertEquals("", event.session());
        assertEquals("", event.turn());
        assertEquals("", event.request());
        assertEquals("", event.configVersion());
        assertEquals("", event.result());
        assertEquals("", event.reason());
    }

    @Test
    void contextFieldsCarryUnifiedIdentityChain() {
        TelemetryEvent event = new TelemetryEvent(
            "semantic_accepted", 42L, "info", Map.of(),
            "device-a", "session-1", "turn-7", "req-9", "cfg-3", "applied", "cloud_won");
        assertEquals("device-a", event.subject());
        assertEquals("session-1", event.session());
        assertEquals("turn-7", event.turn());
        assertEquals("req-9", event.request());
        assertEquals("cfg-3", event.configVersion());
        assertEquals("applied", event.result());
        assertEquals("cloud_won", event.reason());
    }

    @Test
    void nullContextAndPayloadAreNormalized() {
        TelemetryEvent event = new TelemetryEvent("stage", 1L, "info", null, null, null, null, null, null, null, null);
        assertEquals(Map.of(), event.payload());
        assertEquals("", event.subject());
        assertEquals("", event.reason());
    }

    @Test
    void sensitiveKeysAreDetectedCaseAndSeparatorInsensitive() {
        assertTrue(TelemetryFields.isSensitiveKey("authToken"));
        assertTrue(TelemetryFields.isSensitiveKey("api_key"));
        assertTrue(TelemetryFields.isSensitiveKey("X-API-SECRET"));
        assertTrue(TelemetryFields.isSensitiveKey("password"));
        assertFalse(TelemetryFields.isSensitiveKey("stage"));
        assertFalse(TelemetryFields.isSensitiveKey("turn"));
        assertFalse(TelemetryFields.isSensitiveKey(null));
    }
}

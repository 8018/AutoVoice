package com.autovoice.server.gateway;

import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GatewayTextEndpointTest {
    private final SessionContext session = new SessionContext("session-1", "zh-CN", Map.of());
    private final GatewayTextEndpoint endpoint = new GatewayTextEndpoint((text, context, utteranceId) ->
            CompletableFuture.completedFuture(new OnlineSpeechResult(Reply.ofText(text), "")));

    @Test
    void validatesIdentityTextAndContextBeforeInvokingProvider() {
        Map<String, Object> valid = payload();
        assertEquals("导航到公司", endpoint.validate(valid, session).text());
        assertCode("BAD_SESSION", changed(valid, "sessionId", "other"));
        assertCode("BAD_TEXT_REQUEST", changed(valid, "text", "  "));
        assertCode("BAD_TEXT_REQUEST", changed(valid, "text", "x".repeat(2001)));
        assertCode("BAD_TEXT_REQUEST", changed(valid, "requestId", "bad id"));
        assertCode("BAD_TEXT_REQUEST", changed(valid, "contextVersion", 2));
        assertCode("BAD_TEXT_REQUEST", changed(valid, "context", Map.of("ignored", true)));
        assertCode("BAD_TEXT_REQUEST", changed(valid, "inputSource", "audio"));
        assertCode("BAD_TEXT_REQUEST", changed(valid, "language", "en-US"));
    }

    @Test
    void unsupportedProviderIsRejected() {
        GatewayTextEndpoint absent = new GatewayTextEndpoint(null);
        assertEquals("UNSUPPORTED_CAPABILITY", assertThrows(GatewayTextEndpoint.InvalidTextRequest.class,
                () -> absent.validate(payload(), session)).code());
    }

    private void assertCode(String code, Map<String, Object> payload) {
        assertEquals(code, assertThrows(GatewayTextEndpoint.InvalidTextRequest.class,
                () -> endpoint.validate(payload, session)).code());
    }

    private static Map<String, Object> changed(Map<String, Object> original, String key, Object value) {
        Map<String, Object> copy = new HashMap<>(original);
        copy.put(key, value);
        return copy;
    }

    private static Map<String, Object> payload() {
        return Map.of("sessionId", "session-1", "requestId", "req-1", "utteranceId", "req-1",
                "segmentId", "text-seg-1", "text", "导航到公司", "language", "zh-CN",
                "inputSource", "text", "contextVersion", 1, "context", Map.of());
    }
}

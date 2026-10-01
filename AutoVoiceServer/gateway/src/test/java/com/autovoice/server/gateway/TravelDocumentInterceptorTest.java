package com.autovoice.server.gateway;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class TravelDocumentInterceptorTest {
    @Test void startsOnlyAfterBusinessAdmissionAndStreamsInOrder() {
        AtomicBoolean admitted = new AtomicBoolean();
        List<String> frames = new ArrayList<>();
        var interceptor = new TravelDocumentInterceptor(admitted::get,
                (operation, text) -> frames.add(operation + ":" + text));
        assertFalse(interceptor.accept("start", ""));
        assertFalse(interceptor.accept("delta", "early"));
        admitted.set(true);
        assertTrue(interceptor.accept("start", ""));
        assertTrue(interceptor.accept("delta", "# 北"));
        assertTrue(interceptor.accept("delta", "京攻略"));
        assertTrue(interceptor.accept("complete", ""));
        assertEquals(List.of("start:", "delta:# 北", "delta:京攻略", "complete:"), frames);
    }

    @Test void revocationAndTerminalStateRejectLateContent() {
        AtomicBoolean admitted = new AtomicBoolean(true);
        List<String> frames = new ArrayList<>();
        var interceptor = new TravelDocumentInterceptor(admitted::get,
                (operation, text) -> frames.add(operation));
        assertTrue(interceptor.accept("start", ""));
        admitted.set(false);
        assertFalse(interceptor.accept("delta", "stale"));
        admitted.set(true);
        assertTrue(interceptor.accept("error", "failed"));
        assertFalse(interceptor.accept("delta", "late"));
        assertEquals(List.of("start", "error"), frames);
    }

    @Test void malformedSequenceAndOversizeAreBlocked() {
        var interceptor = new TravelDocumentInterceptor(() -> true, (operation, text) -> {});
        assertFalse(interceptor.accept("delta", "early"));
        assertTrue(interceptor.accept("start", ""));
        assertFalse(interceptor.accept("start", ""));
        assertFalse(interceptor.accept("delta", "x".repeat(24_001)));
        assertFalse(interceptor.accept("unknown", ""));
        assertTrue(interceptor.accept("complete", ""));
        assertFalse(interceptor.accept("complete", ""));
    }
}

package com.autovoice.server.navigation;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryNavigationSelectionStoreTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void fullStoreEvictsDeterministicallyByExpiryCreationThenSessionId() {
        long now = CLOCK.millis();
        var store = new InMemoryNavigationSelectionStore(CLOCK, 2);
        store.put("b", pending("b", now + 20, now + 100));
        store.put("a", pending("a", now + 10, now + 100));
        store.put("c", pending("c", now + 30, now + 200));
        assertTrue(store.find("a").isEmpty());
        assertTrue(store.find("b").isPresent());
        assertTrue(store.find("c").isPresent());

        var tie = new InMemoryNavigationSelectionStore(CLOCK, 2);
        tie.put("b", pending("b", now + 10, now + 100));
        tie.put("a", pending("a", now + 10, now + 100));
        tie.put("c", pending("c", now + 20, now + 200));
        assertTrue(tie.find("a").isEmpty());
        assertTrue(tie.find("b").isPresent());
    }

    @Test
    void compareAndRemoveDoesNotDeleteReplacement() {
        long now = CLOCK.millis();
        var store = new InMemoryNavigationSelectionStore(CLOCK, 2);
        var old = pending("old", now + 10, now + 100);
        var replacement = pending("new", now + 20, now + 200);
        store.put("session", old);
        store.put("session", replacement);
        assertFalse(store.remove("session", old));
        assertSame(replacement, store.find("session").orElseThrow());
    }

    private static PendingNavigationSelection pending(String id, long created, long expires) {
        return new PendingNavigationSelection(id, List.of(), created, expires);
    }
}

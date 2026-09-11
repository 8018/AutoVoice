package com.autovoice.server.navigation;

import java.time.Clock;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded logical-session store with lazy expiry and deterministic oldest-expiry eviction. */
final class InMemoryNavigationSelectionStore implements NavigationSelectionStore {
    static final int DEFAULT_CAPACITY = 1_000;

    private final ConcurrentHashMap<String, PendingNavigationSelection> entries =
            new ConcurrentHashMap<>();
    private final Clock clock;
    private final int capacity;

    InMemoryNavigationSelectionStore(Clock clock, int capacity) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    @Override
    public Optional<PendingNavigationSelection> find(String logicalSessionId) {
        if (logicalSessionId == null || logicalSessionId.isBlank()) return Optional.empty();
        PendingNavigationSelection value = entries.get(logicalSessionId);
        if (value == null) return Optional.empty();
        if (clock.millis() > value.expiresAtMs()) {
            entries.remove(logicalSessionId, value);
            return Optional.empty();
        }
        return Optional.of(value);
    }

    @Override
    public synchronized void put(String logicalSessionId, PendingNavigationSelection selection) {
        if (logicalSessionId == null || logicalSessionId.isBlank()) return;
        long now = clock.millis();
        entries.entrySet().removeIf(entry -> now > entry.getValue().expiresAtMs());
        if (!entries.containsKey(logicalSessionId) && entries.size() >= capacity) {
            entries.entrySet().stream()
                    .min(Comparator
                            .comparingLong((Map.Entry<String, PendingNavigationSelection> entry) ->
                                    entry.getValue().expiresAtMs())
                            .thenComparingLong(entry -> entry.getValue().createdAtMs())
                            .thenComparing(Map.Entry::getKey))
                    .ifPresent(entry -> entries.remove(entry.getKey(), entry.getValue()));
        }
        entries.put(logicalSessionId, selection);
    }

    @Override
    public boolean remove(String logicalSessionId, PendingNavigationSelection expected) {
        return logicalSessionId != null && entries.remove(logicalSessionId, expected);
    }
}

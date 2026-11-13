package com.autovoice.server.telemetry;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin aggregate. All terminal outcomes share the denominator and duration distribution. */
public record NavigationStatistics(long from, long to, List<Operation> operations) {
    static final long MAX_WINDOW_MS = Duration.ofDays(7).toMillis();
    static final long MAX_DURATION_MS = Duration.ofHours(24).toMillis();
    static final List<String> OPERATIONS = List.of(
            "plan", "start", "stop", "search", "cancel_search", "map_visibility", "map_control",
            "audio_mute", "audio_volume");
    static final List<String> OUTCOMES = List.of(
            "succeeded", "accepted", "unknown", "unsupported", "missing_location", "failed",
            "timeout", "cancelled", "no_results", "rejected");

    public record Operation(String operation, long total, double successRate,
                            Map<String, Long> outcomes, long p50Ms, long p95Ms) {
    }

    static final class Counts {
        private final String operation;
        private final Map<String, Long> outcomes = new LinkedHashMap<>();
        private long total;
        private long p50;
        private long p95;

        Counts(String operation) {
            this.operation = operation;
            OUTCOMES.forEach(outcome -> outcomes.put(outcome, 0L));
        }

        void add(String outcome, long count, long p50, long p95) {
            outcomes.put(outcome, count);
            total += count;
            // One outcome group contains each percentile rank; other groups return SQL NULL (read as zero).
            this.p50 = Math.max(this.p50, p50);
            this.p95 = Math.max(this.p95, p95);
        }

        Operation summarize() {
            return new Operation(operation, total, (double) outcomes.get("succeeded") / total,
                    Collections.unmodifiableMap(outcomes), p50, p95);
        }
    }
}

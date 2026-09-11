package com.autovoice.server.gateway;

import com.autovoice.server.contracts.telemetry.TelemetryEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsrTurnTraceTest {

    @Test
    void recordsFirstAndFinalResultOnceWithModeAndFinishLatency() {
        List<TelemetryEvent> events = new CopyOnWriteArrayList<>();
        AsrTurnTrace trace = new AsrTurnTrace((uid, event) -> events.add(event), "u1", "classic");

        trace.onResult("导航", false);
        trace.markFinish();
        trace.onResult("导航到机场", true);
        trace.onResult("重复终帧", true);

        assertEquals(2, events.size());
        assertEquals("first_result", events.get(0).payload().get("event"));
        assertEquals("streaming", events.get(0).payload().get("mode"));
        assertEquals("final_result", events.get(1).payload().get("event"));
        assertTrue(((Number) events.get(1).payload().get("finishLatencyMs")).longValue() >= 1);
    }

    @Test
    void distinguishesFallbackAndTimeout() {
        List<TelemetryEvent> events = new CopyOnWriteArrayList<>();
        AsrTurnTrace trace = new AsrTurnTrace((uid, event) -> events.add(event), "u2", "omni");
        trace.markFinish();

        trace.onFailure(new CompletionException(new TimeoutException("late final")));
        trace.useBatch("streaming_finish_failed", new TimeoutException("late final"));

        assertEquals("timeout", events.get(0).payload().get("event"));
        assertEquals("streaming", events.get(0).payload().get("mode"));
        assertEquals("fallback", events.get(1).payload().get("event"));
        assertEquals("batch_fallback", events.get(1).payload().get("mode"));
        assertEquals("streaming_finish_failed", events.get(1).payload().get("reason"));
    }
}

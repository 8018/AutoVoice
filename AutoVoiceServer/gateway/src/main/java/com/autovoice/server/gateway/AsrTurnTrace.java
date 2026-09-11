package com.autovoice.server.gateway;

import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.contracts.telemetry.TelemetryStages;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Request-scoped ASR lifecycle observations shared by streaming and batch fallback paths. */
final class AsrTurnTrace {

    private final TelemetryRecorder recorder;
    private final String utteranceId;
    private final String backend;
    private final long startedAtNanos = System.nanoTime();
    private final AtomicBoolean firstRecorded = new AtomicBoolean();
    private final AtomicBoolean finalRecorded = new AtomicBoolean();
    private final AtomicBoolean failureRecorded = new AtomicBoolean();
    private volatile long finishStartedAtNanos;
    private volatile String mode = "streaming";

    AsrTurnTrace(TelemetryRecorder recorder, String utteranceId, String backend) {
        this.recorder = recorder;
        this.utteranceId = utteranceId;
        this.backend = backend;
    }

    void useBatch(String reason, Throwable error) {
        mode = "batch_fallback";
        Map<String, Object> payload = base("fallback");
        payload.put("reason", reason);
        if (error != null && error.getMessage() != null) payload.put("error", error.getMessage());
        String level = "streaming_unsupported".equals(reason) ? "info" : "warn";
        recorder.record(utteranceId, TelemetryStages.CLOUD_ASR, level, payload);
    }

    void markFinish() {
        if (finishStartedAtNanos == 0) finishStartedAtNanos = System.nanoTime();
    }

    void onResult(String text, boolean isFinal) {
        if (text == null || text.isBlank()) return;
        if (firstRecorded.compareAndSet(false, true)) {
            Map<String, Object> payload = base("first_result");
            payload.put("text", text);
            payload.put("latencyMs", elapsedMs(startedAtNanos));
            recorder.record(utteranceId, TelemetryStages.CLOUD_ASR, "info", payload);
        }
        if (isFinal && finalRecorded.compareAndSet(false, true)) {
            Map<String, Object> payload = base("final_result");
            payload.put("text", text);
            payload.put("latencyMs", elapsedMs(startedAtNanos));
            if (finishStartedAtNanos != 0) {
                payload.put("finishLatencyMs", elapsedMs(finishStartedAtNanos));
            }
            recorder.record(utteranceId, TelemetryStages.CLOUD_ASR, "info", payload);
        }
    }

    void onFailure(Throwable error) {
        if (!failureRecorded.compareAndSet(false, true)) return;
        Throwable cause = unwrap(error);
        boolean timeout = isTimeout(cause);
        Map<String, Object> payload = base(timeout ? "timeout" : "failed");
        payload.put("reason", timeout ? "final_result_timeout" : "provider_error");
        payload.put("errorType", cause.getClass().getSimpleName());
        if (cause.getMessage() != null) payload.put("error", cause.getMessage());
        if (finishStartedAtNanos != 0) payload.put("finishLatencyMs", elapsedMs(finishStartedAtNanos));
        recorder.record(utteranceId, TelemetryStages.CLOUD_ASR, "warn", payload);
    }

    String mode() {
        return mode;
    }

    private Map<String, Object> base(String event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("backend", backend);
        payload.put("mode", mode);
        payload.put("event", event);
        return payload;
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startNanos));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error == null ? new IllegalStateException("unknown ASR failure") : error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException) return true;
            current = current.getCause();
        }
        return false;
    }
}

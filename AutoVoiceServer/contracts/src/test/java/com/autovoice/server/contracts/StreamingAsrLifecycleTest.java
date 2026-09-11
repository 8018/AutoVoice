package com.autovoice.server.contracts;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StreamingAsrLifecycleTest {

    @Test
    void finishDeadlineStartsAtFinishAndReleasesHungSessionOnce() throws Exception {
        CompletableFuture<String> source = new CompletableFuture<>();
        AtomicInteger cancels = new AtomicInteger();
        StreamingAsrSession session = new StreamingAsrSession() {
            @Override public void append(byte[] pcm16k) { }
            @Override public CompletableFuture<String> finish() { return source; }
            @Override public void cancel() { cancels.incrementAndGet(); }
        };

        // A long capture before finish must not consume the final-result deadline.
        Thread.sleep(40);
        CompletableFuture<String> bounded = session.finishWithin(30, TimeUnit.MILLISECONDS);
        ExecutionException error = assertThrows(
                ExecutionException.class, () -> bounded.get(1, TimeUnit.SECONDS));

        assertInstanceOf(TimeoutException.class, error.getCause());
        assertEquals(1, cancels.get());
        source.complete("late result");
        assertTrue(bounded.isCompletedExceptionally(), "迟到结果不得覆盖已确定的超时");
    }

    @Test
    void providerDefaultBridgeUsesItsDeclaredFinishDeadline() {
        AtomicInteger cancels = new AtomicInteger();
        StreamingAsrProvider provider = new StreamingAsrProvider() {
            @Override public StreamingAsrSession start(SessionContext context, OnlineAsrSink sink) {
                return new StreamingAsrSession() {
                    @Override public void append(byte[] pcm16k) { }
                    @Override public CompletableFuture<String> finish() { return new CompletableFuture<>(); }
                    @Override public void cancel() { cancels.incrementAndGet(); }
                };
            }
            @Override public long finishTimeoutMs() { return 30; }
        };

        AsrException error = assertThrows(AsrException.class, () -> provider.transcribe(
                new byte[]{1}, new SessionContext("s1", "zh-CN", Map.of())));

        assertTrue(error.getMessage().contains("timed out"));
        assertEquals(1, cancels.get());
    }
}

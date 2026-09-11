package com.autovoice.server.contracts;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单轮流式 ASR 会话。实现必须允许在底层 WebSocket 完成握手前 append。 */
public interface StreamingAsrSession {
    void append(byte[] pcm16k);
    CompletableFuture<String> finish();
    void cancel();

    /**
     * Waits a bounded time for the final result, measured from {@code finishWithin} rather than
     * session creation. A timeout completes only the returned view exceptionally and releases the
     * provider session; the source future may finish later without changing the caller result.
     */
    default CompletableFuture<String> finishWithin(long timeout, TimeUnit unit) {
        if (timeout <= 0) throw new IllegalArgumentException("timeout must be positive");
        CompletableFuture<String> source = finish();
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        Runnable releaseOnce = () -> {
            if (released.compareAndSet(false, true)) cancel();
        };
        CompletableFuture<String> bounded = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                if (!terminal.compareAndSet(false, true)) return false;
                // Release the provider before cancellation becomes observable to the caller.
                releaseOnce.run();
                return super.cancel(mayInterruptIfRunning);
            }
        };
        source.whenComplete((text, error) -> {
            if (!terminal.compareAndSet(false, true)) return;
            if (error != null) {
                releaseOnce.run();
                bounded.completeExceptionally(error);
            } else {
                bounded.complete(text);
            }
        });
        CompletableFuture.delayedExecutor(timeout, unit).execute(() -> {
            TimeoutException timeoutError = new TimeoutException(
                    "streaming ASR final result timed out after " + timeout + " "
                            + unit.name().toLowerCase());
            if (!terminal.compareAndSet(false, true)) return;
            // Cleanup is part of reaching the terminal timeout state, not an eventual side effect.
            releaseOnce.run();
            bounded.completeExceptionally(timeoutError);
        });
        return bounded;
    }
}

package com.autovoice.server.agentloop;

import java.util.concurrent.*;

/** A monotonic, request-owned deadline. Queueing time is part of the budget. */
public final class ExecutionBudget {
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(16, 16, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), task -> {
                Thread thread = new Thread(task, "agent-budget");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final long started = System.nanoTime();
    private final long durationNanos;

    public ExecutionBudget(long durationMs) {
        if (durationMs <= 0) throw new IllegalArgumentException("execution budget must be positive");
        durationNanos = TimeUnit.MILLISECONDS.toNanos(durationMs);
    }

    public long remainingNanos() throws TimeoutException {
        long remaining = durationNanos - (System.nanoTime() - started);
        if (remaining <= 0) throw new TimeoutException("agent execution budget exhausted");
        return remaining;
    }

    public void check() throws InterruptedException, TimeoutException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("agent interrupted");
        remainingNanos();
    }

    public <T> T await(Future<T> future) throws InterruptedException, ExecutionException, TimeoutException {
        check();
        return future.get(remainingNanos(), TimeUnit.NANOSECONDS);
    }

    public <T> T run(Callable<T> action, Runnable cancel) throws Exception {
        check();
        Future<T> future = WORKERS.submit(() -> { check(); return action.call(); });
        try {
            T result = await(future);
            check();
            return result;
        } catch (TimeoutException | InterruptedException error) {
            future.cancel(true);
            WORKERS.remove((Runnable) future);
            try { cancel.run(); } catch (RuntimeException ignored) { }
            throw error;
        } catch (ExecutionException error) {
            for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof TimeoutException timeout) {
                    try { cancel.run(); } catch (RuntimeException ignored) { }
                    throw timeout;
                }
            }
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw error;
        }
    }
}

package com.autovoice.server.agentloop;

import java.util.concurrent.*;

/** A monotonic, request-owned deadline. Queueing time is part of the budget. */
public final class ExecutionBudget {
    private final long started = System.nanoTime();
    private final long durationNanos;
    private final ThreadPoolExecutor workers;

    public ExecutionBudget(long durationMs, AgentExecutionRuntime runtime) {
        if (durationMs <= 0) throw new IllegalArgumentException("execution budget must be positive");
        durationNanos = TimeUnit.MILLISECONDS.toNanos(durationMs);
        workers = java.util.Objects.requireNonNull(runtime, "runtime").budgets();
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
        Future<T> future = workers.submit(() -> { check(); return action.call(); });
        try {
            T result = await(future);
            check();
            return result;
        } catch (TimeoutException | InterruptedException error) {
            future.cancel(true);
            workers.remove((Runnable) future);
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

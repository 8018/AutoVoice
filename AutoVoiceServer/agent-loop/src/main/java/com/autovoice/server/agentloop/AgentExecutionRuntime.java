package com.autovoice.server.agentloop;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-level execution resources shared by all agent loops.
 *
 * <p>The application assembly owns this object and closes it when Spring stops. Request-scoped
 * {@link AgentLoop} and {@link RequestToolExecutor} instances only borrow its bounded executors;
 * they never create or shut down threads themselves.</p>
 */
public final class AgentExecutionRuntime implements AutoCloseable {
    private static final int WORKERS = 16;
    private static final int QUEUE_CAPACITY = 64;

    private final ThreadPoolExecutor budgets = pool("agent-budget");
    private final ThreadPoolExecutor toolReads = pool("agent-tool-read");

    ThreadPoolExecutor budgets() {
        return budgets;
    }

    ThreadPoolExecutor toolReads() {
        return toolReads;
    }

    @Override
    public void close() {
        budgets.shutdownNow();
        toolReads.shutdownNow();
    }

    private static ThreadPoolExecutor pool(String name) {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(WORKERS, WORKERS, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), task -> {
                    Thread thread = new Thread(task, name + "-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
}

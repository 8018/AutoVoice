package com.autovoice.server.agentloop;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-level execution resources shared by all agent loops.
 *
 * <p>The application assembly owns this object and closes it when Spring stops. Request-scoped
 * {@link AgentLoop} and {@link RequestToolExecutor} instances only borrow its bounded executors;
 * they never create or shut down threads themselves.</p>
 *
 * <p>D06b:经 {@link #submitToolRead} 提交的任务被登记;{@link #close} 对全部在途登记
 * 给出确定性终态(异常完成),等待方不会因队列被丢弃而无限挂起。</p>
 */
public final class AgentExecutionRuntime implements AutoCloseable {
    private static final int WORKERS = 16;
    private static final int QUEUE_CAPACITY = 64;

    private final ThreadPoolExecutor budgets = pool("agent-budget");
    private final ThreadPoolExecutor toolReads = pool("agent-tool-read");
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();

    ThreadPoolExecutor budgets() {
        return budgets;
    }

    ThreadPoolExecutor toolReads() {
        return toolReads;
    }

    /** D06b:跟踪提交工具读取任务;关闭后提交立即失败,在途任务在关闭时获得确定性终态。 */
    public <T> CompletableFuture<T> submitToolRead(Callable<T> task) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("agent execution runtime closed"));
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        pending.add(future);
        try {
            toolReads.submit(() -> {
                try {
                    future.complete(task.call());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException error) {
            future.completeExceptionally(error);
        }
        future.whenComplete((value, error) -> pending.remove(future));
        return future;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        budgets.shutdown();
        toolReads.shutdown();
        // 确定性终态:排队/在途登记全部异常完成,等待方不挂起
        for (CompletableFuture<?> future : pending) {
            future.completeExceptionally(
                    new IllegalStateException("agent execution runtime closed"));
        }
        pending.clear();
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

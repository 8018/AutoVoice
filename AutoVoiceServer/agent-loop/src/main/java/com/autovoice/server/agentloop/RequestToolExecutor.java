package com.autovoice.server.agentloop;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Per-request tool runner. Identical calls are cached across rounds. Consecutive read-only calls run
 * concurrently; a possibly mutating call is a dependency barrier. Result order always matches the
 * assistant's tool-call order.
 */
public final class RequestToolExecutor {
    private static final java.util.concurrent.ThreadPoolExecutor READS = new java.util.concurrent.ThreadPoolExecutor(
            16, 16, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(64), task -> {
                Thread thread = new Thread(task, "agent-tool-read");
                thread.setDaemon(true);
                return thread;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    @FunctionalInterface
    public interface Invoker {
        String invoke(AgentToolCall call) throws Exception;
    }

    @FunctionalInterface
    public interface ErrorFormatter {
        String format(AgentToolCall call, Throwable error);
    }

    private final Invoker invoker;
    private final ErrorFormatter errors;
    private final ToolExecutionPolicy policy;
    private final ConcurrentMap<String, CompletableFuture<AgentToolResult>> cache =
            new ConcurrentHashMap<>();

    public RequestToolExecutor(Invoker invoker, ErrorFormatter errors) {
        this(invoker, errors, ToolExecutionPolicy.conservative());
    }

    public RequestToolExecutor(Invoker invoker, ErrorFormatter errors, ToolExecutionPolicy policy) {
        this.invoker = invoker;
        this.errors = errors;
        this.policy = policy;
    }

    public List<AgentToolResult> execute(List<AgentToolCall> calls) {
        return execute(calls, null);
    }

    public List<AgentToolResult> execute(List<AgentToolCall> calls, ExecutionBudget budget) {
        List<AgentToolResult> results = new ArrayList<>(calls.size());
        List<AgentToolCall> reads = new ArrayList<>();
        for (AgentToolCall call : calls) {
            if (policy.isParallelRead(call)) {
                reads.add(call);
            } else {
                flush(reads, results, budget);
                results.add(runAndCache(call, budget));
            }
        }
        flush(reads, results, budget);
        return results;
    }

    private void flush(List<AgentToolCall> calls, List<AgentToolResult> target, ExecutionBudget budget) {
        if (calls.isEmpty()) return;
        if (calls.size() == 1) {
            target.add(runAndCache(calls.getFirst(), budget));
            calls.clear();
            return;
        }
        List<Future<AgentToolResult>> futures = new ArrayList<>();
        try {
            for (AgentToolCall call : calls) futures.add(READS.submit(() -> runAndCache(call, budget)));
            for (int i = 0; i < futures.size(); i++) {
                try {
                    target.add(budget == null ? futures.get(i).get() : budget.await(futures.get(i)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    target.add(failure(calls.get(i), e));
                } catch (ExecutionException e) {
                    target.add(failure(calls.get(i), e.getCause()));
                } catch (java.util.concurrent.TimeoutException e) {
                    futures.forEach(future -> future.cancel(true));
                    throw new IllegalStateException("agent tool wait budget exhausted", e);
                }
            }
        } finally {
            futures.forEach(future -> future.cancel(true));
            READS.purge();
            calls.clear();
        }
    }

    private AgentToolResult runAndCache(AgentToolCall call, ExecutionBudget budget) {
        try {
            if (budget != null) budget.check();
            else if (Thread.currentThread().isInterrupted()) throw new InterruptedException("tool interrupted");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failure(call, error);
        } catch (java.util.concurrent.TimeoutException error) {
            return failure(call, error);
        }
        CompletableFuture<AgentToolResult> mine = new CompletableFuture<>();
        CompletableFuture<AgentToolResult> existing = cache.putIfAbsent(call.cacheKey(), mine);
        if (existing != null) {
            try {
                AgentToolResult cached = budget == null ? existing.get() : budget.await(existing);
                return new AgentToolResult(call, cached.content(), true, cached.error());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failure(call, e);
            } catch (ExecutionException e) {
                return failure(call, e.getCause());
            } catch (java.util.concurrent.TimeoutException e) {
                return failure(call, e);
            }
        }
        AgentToolResult result;
        try {
            if (budget != null) budget.check();
            result = new AgentToolResult(call, invoker.invoke(call), false, false);
            if (budget != null) budget.check();
        } catch (Throwable error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            result = failure(call, error);
        }
        mine.complete(result);
        return result;
    }

    private AgentToolResult failure(AgentToolCall call, Throwable error) {
        return new AgentToolResult(call, errors.format(call, error), false, true);
    }
}

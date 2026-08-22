package com.autovoice.server.agentloop;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Per-request tool runner. Identical calls are cached across rounds. Consecutive read-only calls run
 * concurrently; a possibly mutating call is a dependency barrier. Result order always matches the
 * assistant's tool-call order.
 */
public final class RequestToolExecutor {
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
        List<AgentToolResult> results = new ArrayList<>(calls.size());
        List<AgentToolCall> reads = new ArrayList<>();
        for (AgentToolCall call : calls) {
            if (policy.isParallelRead(call)) {
                reads.add(call);
            } else {
                flush(reads, results);
                results.add(runAndCache(call));
            }
        }
        flush(reads, results);
        return results;
    }

    private void flush(List<AgentToolCall> calls, List<AgentToolResult> target) {
        if (calls.isEmpty()) return;
        if (calls.size() == 1) {
            target.add(runAndCache(calls.getFirst()));
            calls.clear();
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, calls.size()), r -> {
            Thread thread = new Thread(r, "agent-tool-read");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<AgentToolResult>> futures = calls.stream()
                    .map(call -> pool.submit(() -> runAndCache(call)))
                    .toList();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    target.add(futures.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    target.add(failure(calls.get(i), e));
                } catch (ExecutionException e) {
                    target.add(failure(calls.get(i), e.getCause()));
                }
            }
        } finally {
            pool.shutdownNow();
            calls.clear();
        }
    }

    private AgentToolResult runAndCache(AgentToolCall call) {
        CompletableFuture<AgentToolResult> mine = new CompletableFuture<>();
        CompletableFuture<AgentToolResult> existing = cache.putIfAbsent(call.cacheKey(), mine);
        if (existing != null) {
            try {
                AgentToolResult cached = existing.get();
                return new AgentToolResult(call, cached.content(), true, cached.error());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failure(call, e);
            } catch (ExecutionException e) {
                return failure(call, e.getCause());
            }
        }
        AgentToolResult result;
        try {
            result = new AgentToolResult(call, invoker.invoke(call), false, false);
        } catch (Throwable error) {
            result = failure(call, error);
        }
        mine.complete(result);
        return result;
    }

    private AgentToolResult failure(AgentToolCall call, Throwable error) {
        return new AgentToolResult(call, errors.format(call, error), false, true);
    }
}

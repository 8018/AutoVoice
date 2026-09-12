package com.autovoice.server.agentloop;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Per-request tool runner. Declared cacheable successful reads are reused across rounds. Independent reads run
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
    private final boolean enforceReadOnly;
    private final java.util.concurrent.ThreadPoolExecutor reads;
    private final ConcurrentMap<String, CompletableFuture<AgentToolResult>> cache =
            new ConcurrentHashMap<>();

    public RequestToolExecutor(Invoker invoker, ErrorFormatter errors,
                               AgentExecutionRuntime runtime) {
        this(invoker, errors, ToolExecutionPolicy.conservative(), runtime);
    }

    public RequestToolExecutor(Invoker invoker, ErrorFormatter errors, ToolExecutionPolicy policy,
                               AgentExecutionRuntime runtime) {
        this(invoker, errors, policy, runtime, true);
    }

    /**
     * D04 只读准入:默认路径({@code enforceReadOnly=true})只执行策略批准的只读工具,
     * 写入/未声明工具拒绝执行并给出结构化原因;仅在明确关停的调用方(如调度语义测试)
     * 才允许写工具执行。
     */
    public RequestToolExecutor(Invoker invoker, ErrorFormatter errors, ToolExecutionPolicy policy,
                               AgentExecutionRuntime runtime, boolean enforceReadOnly) {
        this.invoker = invoker;
        this.errors = errors;
        this.policy = policy;
        this.enforceReadOnly = enforceReadOnly;
        this.reads = java.util.Objects.requireNonNull(runtime, "runtime").toolReads();
    }

    public List<AgentToolResult> execute(List<AgentToolCall> calls) {
        return execute(calls, null);
    }

    public List<AgentToolResult> execute(List<AgentToolCall> calls, ExecutionBudget budget) {
        List<AgentToolResult> results = new ArrayList<>(calls.size());
        List<AgentToolCall> reads = new ArrayList<>();
        for (AgentToolCall call : calls) {
            if (enforceReadOnly && !policy.isReadOnly(call) && !policy.isApprovedOperation(call)) {
                results.add(rejected(call));
                continue;
            }
            if (policy.isParallelRead(call)) {
                reads.add(call);
            } else {
                flush(reads, results, budget);
                // An operation may change data read earlier, even if its response later fails.
                if (!policy.isReadOnly(call)) cache.clear();
                results.add(runAndCache(call, budget));
            }
        }
        flush(reads, results, budget);
        return results;
    }

    private AgentToolResult rejected(AgentToolCall call) {
        return new AgentToolResult(call,
                "tool \"" + call.name() + "\" rejected: not an approved read-only tool",
                false, true);
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
            for (AgentToolCall call : calls) futures.add(reads.submit(() -> runAndCache(call, budget)));
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
            reads.purge();
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
        String key = policy.cacheSuccess(call) ? call.cacheKey() : null;
        CompletableFuture<AgentToolResult> mine = new CompletableFuture<>();
        CompletableFuture<AgentToolResult> existing = key == null ? null : cache.putIfAbsent(key, mine);
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
        if (result.error() && key != null) cache.remove(key, mine);
        mine.complete(result);
        return result;
    }

    private AgentToolResult failure(AgentToolCall call, Throwable error) {
        return new AgentToolResult(call, errors.format(call, error), false, true);
    }
}

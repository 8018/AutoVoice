package com.autovoice.server.agentloop;

import java.util.List;
import java.util.Optional;

/** Provider-neutral bounded model/tool loop shared by text and speech backends. */
public final class AgentLoop<M, R> {
    public record Policy(int maxRounds, long toolBudgetMs, boolean disableToolsOnLastRound) {
        public Policy {
            if (maxRounds < 1) throw new IllegalArgumentException("maxRounds must be positive");
            toolBudgetMs = Math.max(0, toolBudgetMs);
        }
    }

    public interface Adapter<M, R> {
        M callModel(int round, boolean toolsAllowed) throws Exception;

        List<AgentToolCall> toolCalls(M message);

        Optional<R> terminal(M message, List<AgentToolCall> calls) throws Exception;

        void appendToolResults(M message, List<AgentToolResult> results) throws Exception;

        R finish(M message) throws Exception;

        R exhausted(M lastMessage) throws Exception;
    }

    private final Policy policy;
    private final RequestToolExecutor tools;
    private final Adapter<M, R> adapter;

    public AgentLoop(Policy policy, RequestToolExecutor tools, Adapter<M, R> adapter) {
        this.policy = policy;
        this.tools = tools;
        this.adapter = adapter;
    }

    public R run() throws Exception {
        long startedAt = System.currentTimeMillis();
        M last = null;
        for (int round = 1; round <= policy.maxRounds(); round++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("agent loop cancelled");
            }
            boolean withinBudget = policy.toolBudgetMs() == Long.MAX_VALUE
                    || policy.toolBudgetMs() > 0
                    && System.currentTimeMillis() - startedAt <= policy.toolBudgetMs();
            boolean lastRound = round == policy.maxRounds();
            boolean toolsAllowed = withinBudget && !(lastRound && policy.disableToolsOnLastRound());
            last = adapter.callModel(round, toolsAllowed);
            List<AgentToolCall> calls = adapter.toolCalls(last);
            if (calls.isEmpty()) return adapter.finish(last);
            Optional<R> terminal = adapter.terminal(last, calls);
            if (terminal.isPresent()) return terminal.get();
            if (!toolsAllowed) {
                throw new IllegalStateException("model called a tool while tools are disabled");
            }
            adapter.appendToolResults(last, tools.execute(calls));
        }
        return adapter.exhausted(last);
    }
}

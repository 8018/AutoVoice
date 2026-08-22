package com.autovoice.server.agentloop;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentLoopTest {
    @Test
    void runsModelToolResultModelThroughSharedStateMachine() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        List<AgentToolResult> appended = new ArrayList<>();
        RequestToolExecutor tools = new RequestToolExecutor(call -> "42",
                (call, error) -> error.getMessage());
        AgentLoop<String, String> loop = new AgentLoop<>(
                new AgentLoop.Policy(3, 1_000, true), tools, new AgentLoop.Adapter<>() {
                    @Override public String callModel(int round, boolean toolsAllowed) {
                        modelCalls.incrementAndGet();
                        return round == 1 ? "tool" : "done";
                    }

                    @Override public List<AgentToolCall> toolCalls(String message) {
                        return "tool".equals(message)
                                ? List.of(new AgentToolCall("1", "get_answer", "{}")) : List.of();
                    }

                    @Override public Optional<String> terminal(String message, List<AgentToolCall> calls) {
                        return Optional.empty();
                    }

                    @Override public void appendToolResults(String message, List<AgentToolResult> results) {
                        appended.addAll(results);
                    }

                    @Override public String finish(String message) { return message; }
                    @Override public String exhausted(String lastMessage) { return "exhausted"; }
                });

        assertEquals("done", loop.run());
        assertEquals(2, modelCalls.get());
        assertEquals("42", appended.getFirst().content());
    }
}

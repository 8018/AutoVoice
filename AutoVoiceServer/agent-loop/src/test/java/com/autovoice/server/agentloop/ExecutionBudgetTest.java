package com.autovoice.server.agentloop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionBudgetTest {
    private final AgentExecutionRuntime runtime = new AgentExecutionRuntime();

    @AfterEach void closeRuntime() { runtime.close(); }

    private abstract static class Adapter implements AgentLoop.Adapter<String, String> {
        public List<AgentToolCall> toolCalls(String message) {
            return List.of(new AgentToolCall("1", "get_place", "{}"));
        }
        public Optional<String> terminal(String message, List<AgentToolCall> calls) { return Optional.empty(); }
        public void appendToolResults(String message, List<AgentToolResult> results) { }
        public String finish(String message) { return message; }
        public String exhausted(String message) { return "exhausted"; }
    }

    private RequestToolExecutor tools(AtomicInteger count) {
        return new RequestToolExecutor(call -> { count.incrementAndGet(); return "ok"; },
                (call, error) -> error.toString(),
                com.autovoice.server.agentloop.ToolExecutionPolicy.conservative(), runtime, false);
    }

    @Test
    void lateModelCannotExecuteTerminalActionEvenIfItIgnoresInterrupt() throws Exception {
        CountDownLatch release = new CountDownLatch(1), stopped = new CountDownLatch(1);
        AtomicInteger terminals = new AtomicInteger(), cancels = new AtomicInteger(), calls = new AtomicInteger();
        var loop = new AgentLoop<>(new AgentLoop.Policy(3, 200, true), tools(calls), new Adapter() {
            public String callModel(int round, boolean allowed) {
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
                stopped.countDown();
                return "late";
            }
            public Optional<String> terminal(String message, List<AgentToolCall> tools) {
                terminals.incrementAndGet(); return Optional.of("navigate");
            }
            public void cancelExecution() { cancels.incrementAndGet(); }
        }, runtime);
        try {
            assertThrows(TimeoutException.class, loop::run);
            assertEquals(1, cancels.get());
        } finally { release.countDown(); }
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
        assertEquals(0, terminals.get());
        assertEquals(0, calls.get());
    }

    @Test
    void serialToolTimeoutDoesNotStartNextOperation() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        CountDownLatch interrupted = new CountDownLatch(1);
        RequestToolExecutor executor = new RequestToolExecutor(call -> {
            if (call.name().equals("get_place")) {
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException error) { interrupted.countDown(); throw error; }
            } else writes.incrementAndGet();
            return "ok";
        }, (call, error) -> error.toString(),
                com.autovoice.server.agentloop.ToolExecutionPolicy.conservative(), runtime, false);
        var loop = new AgentLoop<>(new AgentLoop.Policy(3, 300, true), executor, new Adapter() {
            public String callModel(int round, boolean allowed) { return "tools"; }
            public List<AgentToolCall> toolCalls(String message) {
                return List.of(new AgentToolCall("1", "get_place", "{}"),
                        new AgentToolCall("2", "set_destination", "{}"));
            }
        }, runtime);
        assertThrows(TimeoutException.class, loop::run);
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertEquals(0, writes.get());
    }

    @Test
    void parallelAndDuplicateCacheWaitsAreBounded() {
        var budget = new ExecutionBudget(300, runtime);
        var executor = new RequestToolExecutor(call -> {
            new CountDownLatch(1).await(); return "unreachable";
        }, (call, error) -> error.toString(), ToolExecutionPolicy.declared(List.of(
                new com.autovoice.server.contracts.FunctionTool("get_place", "", "{}",
                        com.autovoice.server.contracts.ToolExecutionTraits.INDEPENDENT_QUERY))), runtime);
        assertThrows(TimeoutException.class, () -> budget.run(() -> executor.execute(List.of(
                new AgentToolCall("1", "get_place", "{}"),
                new AgentToolCall("2", "get_place", "{}")), budget), () -> {}));
    }

    @Test
    void zeroToolBudgetStillAllowsBoundedTextAnswer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var loop = new AgentLoop<>(new AgentLoop.Policy(3, 0, true), tools(calls), new Adapter() {
            public String callModel(int round, boolean allowed) {
                assertFalse(allowed); return "hello";
            }
            public List<AgentToolCall> toolCalls(String message) { return List.of(); }
        }, runtime);
        assertEquals("hello", loop.run());
        assertEquals(0, calls.get());
    }
}

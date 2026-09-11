package com.autovoice.server.agentloop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestToolExecutorTest {
    private final AgentExecutionRuntime runtime = new AgentExecutionRuntime();

    @AfterEach void closeRuntime() { runtime.close(); }

    @Test
    void operationInvalidatesEarlierReadCacheAndIsNeverDeduplicated() {
        AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger();
        var executor = new RequestToolExecutor(call -> {
            if (call.name().equals("maps_geo")) return "" + reads.incrementAndGet();
            writes.incrementAndGet(); return "ok";
        }, (call, error) -> "error", queries(), runtime);
        var query = new AgentToolCall("1", "maps_geo", "{}");
        var operation = new AgentToolCall("2", "set_destination", "{}");
        executor.execute(List.of(query, operation, query, operation));
        assertEquals(2, reads.get());
        assertEquals(2, writes.get());
    }
    @Test
    void unknownGetNamedToolIsNotCachedOrParallelized() {
        AtomicInteger calls = new AtomicInteger();
        var tool = new AgentToolCall("1", "get_or_create_route", "{}");
        var executor = new RequestToolExecutor(call -> "" + calls.incrementAndGet(),
                (call, error) -> "error", runtime);
        assertTrue(!ToolExecutionPolicy.conservative().isParallelRead(tool));
        assertEquals("1", executor.execute(List.of(tool)).getFirst().content());
        assertEquals("2", executor.execute(List.of(tool)).getFirst().content());
    }

    @Test
    void failedReadCanBeRetriedAndOnlySuccessIsRetained() {
        AtomicInteger calls = new AtomicInteger();
        var executor = new RequestToolExecutor(call -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("temporary failure");
            return "ok";
        }, (call, error) -> "error", queries(), runtime);
        var tool = new AgentToolCall("1", "maps_geo", "{}");
        assertTrue(executor.execute(List.of(tool)).getFirst().error());
        assertEquals("ok", executor.execute(List.of(tool)).getFirst().content());
        assertTrue(executor.execute(List.of(tool)).getFirst().cached());
        assertEquals(2, calls.get());
    }

    @Test
    void canonicalArgumentsReuseObjectsButNeverReorderWaypoints() {
        AtomicInteger calls = new AtomicInteger();
        var executor = new RequestToolExecutor(call -> "" + calls.incrementAndGet(),
                (call, error) -> "error", queries(), runtime);
        var first = new AgentToolCall("1", "maps_geo", "{\"city\":\"成都\",\"route\":[1,2],\"options\":{\"b\":2,\"a\":1}}");
        var reordered = new AgentToolCall("2", "maps_geo", "{\"options\":{\"a\":1,\"b\":2},\"route\":[1,2],\"city\":\"成都\"}");
        executor.execute(List.of(first));
        assertTrue(executor.execute(List.of(reordered)).getFirst().cached());
        executor.execute(List.of(new AgentToolCall("3", "maps_geo", "{\"city\":\"成都\",\"route\":[2,1],\"options\":{\"b\":2,\"a\":1}}")));
        assertEquals(2, calls.get());
        var invalid = new AgentToolCall("4", "maps_geo", "{\"a\":1,\"a\":2}");
        executor.execute(List.of(invalid, invalid));
        assertEquals(4, calls.get());
    }

    @Test
    void declarationsSurviveCompactionAndDuplicatesFailClosed() {
        var definition = new com.autovoice.server.contracts.FunctionTool("maps_geo", "", "{}",
                com.autovoice.server.contracts.ToolExecutionTraits.INDEPENDENT_QUERY);
        var call = new AgentToolCall("1", "maps_geo", "{}");
        assertTrue(ToolExecutionPolicy.declared(ToolSchemaCompactor.compact(List.of(definition))).cacheSuccess(call));
        assertTrue(!ToolExecutionPolicy.declared(List.of(definition, definition)).cacheSuccess(call));
    }
    private static ToolExecutionPolicy queries() {
        return ToolExecutionPolicy.declared(List.of(
                new com.autovoice.server.contracts.FunctionTool("maps_text_search", "", "{}",
                        com.autovoice.server.contracts.ToolExecutionTraits.INDEPENDENT_QUERY),
                new com.autovoice.server.contracts.FunctionTool("maps_geo", "", "{}",
                        com.autovoice.server.contracts.ToolExecutionTraits.INDEPENDENT_QUERY)));
    }
    @Test
    void parallelizesIndependentReadsAndKeepsSourceOrder() {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        RequestToolExecutor executor = new RequestToolExecutor(call -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return call.name();
        }, (call, error) -> error.getMessage(), queries(), runtime);

        Thread unlock = new Thread(() -> {
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS), "read calls should overlap");
                release.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        unlock.start();
        List<AgentToolResult> results = executor.execute(List.of(
                new AgentToolCall("1", "maps_text_search", "{\"q\":\"A\"}"),
                new AgentToolCall("2", "maps_text_search", "{\"q\":\"B\"}")));
        assertEquals(List.of("maps_text_search", "maps_text_search"),
                results.stream().map(AgentToolResult::content).toList());
    }

    @Test
    void cachesSameCallAcrossBatches() {
        AtomicInteger calls = new AtomicInteger();
        RequestToolExecutor executor = new RequestToolExecutor(call -> {
            calls.incrementAndGet();
            return "ok";
        }, (call, error) -> error.getMessage(), queries(), runtime);
        AgentToolCall first = new AgentToolCall("1", "maps_geo", "{\"address\":\"A\"}");
        AgentToolCall second = new AgentToolCall("2", "maps_geo", "{\"address\":\"A\"}");

        assertTrue(!executor.execute(List.of(first)).getFirst().cached());
        assertTrue(executor.execute(List.of(second)).getFirst().cached());
        assertEquals(1, calls.get());
    }

    @Test
    void mutationIsDependencyBarrier() {
        StringBuilder order = new StringBuilder();
        RequestToolExecutor executor = new RequestToolExecutor(call -> {
            order.append(call.id());
            return "ok";
        }, (call, error) -> error.getMessage(), call -> call.name().startsWith("get_"), runtime);

        executor.execute(List.of(
                new AgentToolCall("1", "get_a", "{}"),
                new AgentToolCall("2", "navigate", "{}"),
                new AgentToolCall("3", "get_b", "{}")));
        assertEquals("123", order.toString());
    }
}

package com.autovoice.server.agentloop;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestToolExecutorTest {
    @Test
    void parallelizesIndependentReadsAndKeepsSourceOrder() {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        RequestToolExecutor executor = new RequestToolExecutor(call -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return call.name();
        }, (call, error) -> error.getMessage());

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
        }, (call, error) -> error.getMessage());
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
        }, (call, error) -> error.getMessage(), call -> call.name().startsWith("get_"));

        executor.execute(List.of(
                new AgentToolCall("1", "get_a", "{}"),
                new AgentToolCall("2", "navigate", "{}"),
                new AgentToolCall("3", "get_b", "{}")));
        assertEquals("123", order.toString());
    }
}

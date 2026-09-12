package com.autovoice.server.agentloop;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutionRuntimeTest {

    @Test
    void closeStopsBothOwnedPoolsAndIsIdempotent() {
        AgentExecutionRuntime runtime = new AgentExecutionRuntime();

        runtime.close();
        runtime.close();

        assertTrue(runtime.budgets().isShutdown());
        assertTrue(runtime.toolReads().isShutdown());
    }

    // ---------- D06b:关闭排空与确定性终态 ----------

    @Test
    void closeFailsPendingTrackedFuturesDeterministically() throws Exception {
        AgentExecutionRuntime runtime = new AgentExecutionRuntime();
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<String> pending = runtime.submitToolRead(() -> {
            started.countDown();
            new CountDownLatch(1).await(); // 永久阻塞的任务
            return "never";
        });
        assertTrue(started.await(2, TimeUnit.SECONDS), "任务应已开始执行");

        runtime.close();

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> pending.get(2, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof IllegalStateException,
                "关闭后等待方必须获得明确的终态异常,而不是无限挂起");
    }

    @Test
    void submitAfterCloseFailsImmediately() {
        AgentExecutionRuntime runtime = new AgentExecutionRuntime();
        runtime.close();

        CompletableFuture<String> future = runtime.submitToolRead(() -> "x");
        java.util.concurrent.CompletionException error =
                assertThrows(java.util.concurrent.CompletionException.class, future::join);
        assertTrue(error.getCause() instanceof IllegalStateException);
    }
}

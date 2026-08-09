package com.autovoice.server.arbitration;

import com.autovoice.server.contracts.DecisionEntry;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仲裁器测试（单路 LLM + safety 兜底）：原 NLU ∥ LLM 竞速已随 AIUI 下线退役，
 * 现在只验证 llm_reply / safety_timeout 两条收敛路径与单赢家守卫。
 */
class RaceArbiterTest {
    static final long SAFETY = 1000;
    final List<DecisionEntry> log = new ArrayList<>();
    final DecisionSink sink = log::add;
    final ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
    final RaceArbiter arbiter = new RaceArbiter(SAFETY, sched, sink);
    final SessionContext ctx = new SessionContext("s1", "zh-CN", Map.of());

    @AfterEach
    void shutdownPool() {
        sched.shutdownNow();
    }

    LlmProvider llm(String text, long delayMs) {
        return (t, c) -> CompletableFuture.supplyAsync(() -> {
            sleep(delayMs);
            return Reply.ofText(text);
        }, sched);
    }
    LlmProvider llmError(long delayMs) {
        return (t, c) -> CompletableFuture.supplyAsync(() -> {
            sleep(delayMs);
            throw new RuntimeException("llm down");
        }, sched);
    }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); } }

    @Test void llmWinsWithinSafety() {
        Reply r = arbiter.decide("打开空调", llm("LLM回答", 10), ctx).join();
        assertEquals("LLM回答", r.text());
        assertEquals(1, log.size()); // 恰一条决策日志
        assertEquals("llm_reply", log.get(log.size()-1).reason());
    }
    @Test void llmSlowSafetyFallback() {
        Reply r = arbiter.decide("打开空调", llm("LLM回答", 5000), ctx).join();
        assertTrue(r.text().contains("网络开小差"));
        assertEquals(1, log.size()); // 恰一条决策日志
        assertEquals("safety_timeout", log.get(log.size()-1).reason());
    }
    @Test void llmErrorFallsBackToSafety() {
        // LLM 异常（如 401/超时）：留给 safety 兜底，不吞掉整条链路
        Reply r = arbiter.decide("打开空调", llmError(10), ctx).join();
        assertTrue(r.text().contains("网络开小差"));
        assertEquals(1, log.size());
        assertEquals("safety_timeout", log.get(log.size()-1).reason());
    }
    @Test void lateLlmDoesNotStealFromSafetyFallback() {
        // llm 在 safety 期限后 4 秒才完成：llm 回调不得 CAS 抢赢，兜底必须胜出
        Reply r = arbiter.decide("打开空调", llm("LLM回答", 5000), ctx).join();
        assertTrue(r.text().contains("网络开小差"));
        assertEquals(1, log.size()); // 恰一条决策日志
        assertEquals("safety_timeout", log.get(log.size()-1).reason());
    }
}

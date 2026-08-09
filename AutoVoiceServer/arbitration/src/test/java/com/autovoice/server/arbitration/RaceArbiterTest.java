package com.autovoice.server.arbitration;

import com.autovoice.server.contracts.ArbiterDecision;
import com.autovoice.server.contracts.DecisionEntry;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.OfflineCommandHit;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仲裁器测试（双候选竞速）：offline_won / llm_reply（含宽限期）/ safety_timeout
 * 三条收敛路径 + 单赢家守卫 + 旧单路入口委托。
 */
class RaceArbiterTest {
    static final long SAFETY = 1000;
    static final long GRACE = 300;
    final List<DecisionEntry> log = new ArrayList<>();
    final DecisionSink sink = log::add;
    final ScheduledExecutorService sched = Executors.newScheduledThreadPool(4);
    final RaceArbiter arbiter = new RaceArbiter(SAFETY, GRACE, sched, sink);
    final SessionContext ctx = new SessionContext("s1", "zh-CN", Map.of());

    @AfterEach
    void shutdownPool() {
        sched.shutdownNow();
    }

    static Intent powerOnIntent() {
        return Intent.of("1.0", "climate", "power_on", Map.of(), 1.0, "test", null);
    }

    static OfflineCommandHit hit(String text) {
        return new OfflineCommandHit(text, powerOnIntent());
    }

    CompletableFuture<OfflineCommandHit> offlineAt(long delayMs, OfflineCommandHit value) {
        CompletableFuture<OfflineCommandHit> f = new CompletableFuture<>();
        sched.schedule(() -> f.complete(value), delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        return f;
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

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------ offline 胜出

    @Test
    void offlineHitWinsImmediatelyEvenIfLlmAlreadyArrived() {
        CompletableFuture<OfflineCommandHit> offline = CompletableFuture.completedFuture(hit("打开空调"));
        ArbiterDecision d = arbiter.decide(offline, llm("LLM回答", 10).chat("x", ctx), ctx).join();
        assertEquals("offline_won", d.reason());
        assertEquals("action", d.reply().kind());
        assertEquals("climate", d.reply().intent().domain());
        assertEquals("打开空调", d.offlineText());
        assertEquals(1, log.size());
        assertEquals("offline_won", log.get(0).reason());
        assertEquals("nlu-traditional", log.get(0).route());
    }

    @Test
    void offlineHitBeatsFasterLlm() {
        // LLM 50ms 到达，离线 200ms 命中 → 离线胜出（命中即胜，不看先后）
        CompletableFuture<OfflineCommandHit> offline = offlineAt(200, hit("关闭空调"));
        ArbiterDecision d = arbiter.decide(offline, llm("LLM回答", 50).chat("x", ctx), ctx).join();
        assertEquals("offline_won", d.reason());
        assertEquals("关闭空调", d.offlineText());
        assertEquals(1, log.size());
    }

    @Test
    void offlineHitWithinGraceBeatsLlmArrivedFirst() {
        // LLM 10ms 到达（离线未完成 → 起宽限期），离线 100ms 命中（< grace 300ms）→ 离线胜出
        CompletableFuture<OfflineCommandHit> offline = offlineAt(100, hit("打开空调"));
        ArbiterDecision d = arbiter.decide(offline, llm("LLM回答", 10).chat("x", ctx), ctx).join();
        assertEquals("offline_won", d.reason());
        assertEquals(1, log.size());
    }

    // ------------------------------------------------------------ LLM 胜出

    @Test
    void llmWinsImmediatelyWhenOfflineAlreadyDoneEmpty() {
        // 离线已完成（空结果）→ LLM 到达即胜出，不花宽限期
        CompletableFuture<OfflineCommandHit> offline = CompletableFuture.completedFuture(null);
        long start = System.currentTimeMillis();
        ArbiterDecision d = arbiter.decide(offline, llm("LLM回答", 10).chat("x", ctx), ctx).join();
        long elapsed = System.currentTimeMillis() - start;
        assertEquals("llm_reply", d.reason());
        assertEquals("LLM回答", d.reply().text());
        assertNull(d.offlineText());
        assertTrue(elapsed < GRACE, "离线已完成时 LLM 应立即胜出（elapsed=" + elapsed + "ms）");
        assertEquals(1, log.size());
        assertEquals("llm_reply", log.get(0).reason());
        assertEquals("llm", log.get(0).route());
    }

    @Test
    void llmWinsAfterGraceWhenOfflineNeverHits() {
        // LLM 10ms 到达，离线 200ms 空结果（> grace 前未命中）→ 宽限期到点 LLM 胜出
        CompletableFuture<OfflineCommandHit> offline = offlineAt(200, null);
        ArbiterDecision d = arbiter.decide(offline, llm("LLM回答", 10).chat("x", ctx), ctx).join();
        assertEquals("llm_reply", d.reason());
        assertEquals(1, log.size());
    }

    @Test
    void lateOfflineDoesNotStealFromLlm() {
        // LLM 10ms 到达 + 宽限 300ms 到点胜出；离线命中 600ms 才到 → CAS 拒绝，仍是 llm_reply
        CompletableFuture<OfflineCommandHit> offline = offlineAt(600, hit("打开空调"));
        ArbiterDecision d = arbiter.decide(offline, llm("LLM回答", 10).chat("x", ctx), ctx).join();
        assertEquals("llm_reply", d.reason());
        assertEquals(1, log.size(), "迟到的离线不得写第二条决策日志");
    }

    @Test
    void llmErrorFallsBackToSafety() {
        CompletableFuture<OfflineCommandHit> offline = offlineAt(5000, null); // 始终未完成
        ArbiterDecision d = arbiter.decide(offline, llmError(10).chat("x", ctx), ctx).join();
        assertEquals("safety_timeout", d.reason());
        assertTrue(d.reply().text().contains("网络开小差"));
        assertEquals(1, log.size());
    }

    @Test
    void bothHangSafetyFallback() {
        CompletableFuture<OfflineCommandHit> offline = new CompletableFuture<>();
        CompletableFuture<Reply> llmF = new CompletableFuture<>();
        ArbiterDecision d = arbiter.decide(offline, llmF, ctx).join();
        assertEquals("safety_timeout", d.reason());
        assertEquals(1, log.size());
    }

    // ------------------------------------------------------------ 旧单路入口委托

    @Test
    void legacyDecideDelegatesToLlmOnly() {
        Reply r = arbiter.decide("打开空调", llm("LLM回答", 10), ctx).join();
        assertEquals("LLM回答", r.text());
        assertEquals(1, log.size());
        assertEquals("llm_reply", log.get(0).reason());
    }

    @Test
    void legacyDecideLlmErrorSafetyFallback() {
        Reply r = arbiter.decide("打开空调", llmError(10), ctx).join();
        assertTrue(r.text().contains("网络开小差"));
        assertEquals(1, log.size());
        assertEquals("safety_timeout", log.get(0).reason());
    }
}

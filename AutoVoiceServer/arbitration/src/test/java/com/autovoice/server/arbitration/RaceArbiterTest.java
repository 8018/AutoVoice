package com.autovoice.server.arbitration;

import com.autovoice.server.contracts.ArbiterDecision;
import com.autovoice.server.contracts.CloudArbiterEvent;
import com.autovoice.server.contracts.DecisionEntry;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.OfflineCommandHit;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.SlotValue;
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
 * 三条收敛路径 + 单赢家守卫 + B3 过程事件（received/won/lost）+ 旧单路入口委托。
 */
class RaceArbiterTest {
    static final long SAFETY = 1000;
    static final long GRACE = 300;
    final List<DecisionEntry> log = new ArrayList<>();
    final DecisionSink sink = log::add;
    final ScheduledExecutorService sched = Executors.newScheduledThreadPool(4);
    final RaceArbiter arbiter = new RaceArbiter(SAFETY, GRACE, sched, sink);
    final SessionContext ctx = new SessionContext("s1", "zh-CN", Map.of());

    /** B3 事件收集：带 eventSink 的仲裁器 + 按序记录 (utteranceId, event)。 */
    record Event(String utteranceId, CloudArbiterEvent event) {
    }

    final List<Event> arbEvents = new ArrayList<>();
    final RaceArbiter eventArbiter = new RaceArbiter(SAFETY, GRACE, sched, sink,
            (uid, e) -> arbEvents.add(new Event(uid, e)));

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

    static Intent windowPowerIntent() {
        return Intent.of("1.0", "window", "power_on", Map.of(), 1.0, "test", null);
    }

    static Intent setTemperatureIntent(double degrees) {
        return Intent.of("1.0", "climate", "set_temperature",
                Map.of("temperature", SlotValue.number(degrees)), 1.0, "test", null);
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
        ArbiterDecision d = arbiter.decide(offline, llm("LLM回答", 10).chat("x", ctx), ctx, "utt-42").join();
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
        ArbiterDecision d = arbiter.decide(offline, llm("LLM回答", 50).chat("x", ctx), ctx, "utt-42").join();
        assertEquals("offline_won", d.reason());
        assertEquals("关闭空调", d.offlineText());
        assertEquals(1, log.size());
    }

    @Test
    void offlineHitWithinGraceBeatsLlmArrivedFirst() {
        // LLM 10ms 到达（离线未完成 → 起宽限期），离线 100ms 命中（< grace 300ms）→ 离线胜出
        CompletableFuture<OfflineCommandHit> offline = offlineAt(100, hit("打开空调"));
        ArbiterDecision d = arbiter.decide(offline, llm("LLM回答", 10).chat("x", ctx), ctx, "utt-42").join();
        assertEquals("offline_won", d.reason());
        assertEquals(1, log.size());
    }

    // ------------------------------------------------ 能力分级（2026-08-15）：仅空调控制直接胜出

    @Test
    void airconSetTemperatureOfflineWins() {
        // 调温（set_temperature）也是空调控制 → 直接胜出（offline_won）
        CompletableFuture<OfflineCommandHit> offline = CompletableFuture.completedFuture(
                new OfflineCommandHit("空调调到26度", setTemperatureIntent(26.0)));
        ArbiterDecision d = arbiter.decide(offline, llm("LLM回答", 10).chat("x", ctx), ctx, "utt-42").join();
        assertEquals("offline_won", d.reason());
        assertEquals("空调调到26度", d.offlineText());
        assertEquals("climate", d.reply().intent().domain());
        assertEquals("set_temperature", d.reply().intent().intent());
        assertEquals(1, log.size());
        assertEquals("nlu-traditional", log.get(0).route());
    }

    @Test
    void nonAirconOfflineHitDefersToLlm() {
        // 非空调命中（window/misc）按未命中处理：不发 received 事件、不参与胜出，
        // 离线已完成 → LLM 到达即胜出（不花宽限期），reason = llm_reply
        CompletableFuture<OfflineCommandHit> offline = CompletableFuture.completedFuture(
                new OfflineCommandHit("打开车窗", windowPowerIntent()));
        long start = System.currentTimeMillis();
        ArbiterDecision d = eventArbiter.decide(offline, llm("LLM回答", 10).chat("x", ctx), ctx, "utt-42").join();
        long elapsed = System.currentTimeMillis() - start;
        assertEquals("llm_reply", d.reason());
        assertEquals("LLM回答", d.reply().text());
        assertNull(d.offlineText());
        assertTrue(elapsed < GRACE, "离线已完成（非空调命中）时 LLM 应立即胜出（elapsed=" + elapsed + "ms）");
        assertEquals(1, log.size());
        assertEquals("llm_reply", log.get(0).reason());
        sleep(50); // 等可能迟到的事件回调（sched 线程）
        assertEvents(
                "utt-42|received(llm)",
                "utt-42|won(llm,priority,llm_reply)");
    }

    @Test
    void nonAirconOfflineHitLlmErrorFallsBackToSafety() {
        // 非空调命中 + LLM 失败 → 无候选收敛，safety 兜底
        CompletableFuture<OfflineCommandHit> offline = CompletableFuture.completedFuture(
                new OfflineCommandHit("打开车窗", windowPowerIntent()));
        ArbiterDecision d = eventArbiter.decide(offline, llmError(10).chat("x", ctx), ctx, "utt-42").join();
        assertEquals("safety_timeout", d.reason());
        assertEquals(1, log.size());
        assertEvents("utt-42|won(llm,llm_timeout,safety_timeout)");
    }

    // ------------------------------------------------------------ LLM 胜出

    @Test
    void llmWinsImmediatelyWhenOfflineAlreadyDoneEmpty() {
        // 离线已完成（空结果）→ LLM 到达即胜出，不花宽限期
        CompletableFuture<OfflineCommandHit> offline = CompletableFuture.completedFuture(null);
        long start = System.currentTimeMillis();
        ArbiterDecision d = arbiter.decide(offline, llm("LLM回答", 10).chat("x", ctx), ctx, "utt-42").join();
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
        ArbiterDecision d = arbiter.decide(offline, llm("LLM回答", 10).chat("x", ctx), ctx, "utt-42").join();
        assertEquals("llm_reply", d.reason());
        assertEquals(1, log.size());
    }

    @Test
    void lateOfflineDoesNotStealFromLlm() {
        // LLM 10ms 到达 + 宽限 300ms 到点胜出；离线命中 600ms 才到 → CAS 拒绝，仍是 llm_reply
        CompletableFuture<OfflineCommandHit> offline = offlineAt(600, hit("打开空调"));
        ArbiterDecision d = arbiter.decide(offline, llm("LLM回答", 10).chat("x", ctx), ctx, "utt-42").join();
        assertEquals("llm_reply", d.reason());
        assertEquals(1, log.size(), "迟到的离线不得写第二条决策日志");
    }

    @Test
    void llmErrorFallsBackToSafety() {
        CompletableFuture<OfflineCommandHit> offline = offlineAt(5000, null); // 始终未完成
        ArbiterDecision d = arbiter.decide(offline, llmError(10).chat("x", ctx), ctx, "utt-42").join();
        assertEquals("safety_timeout", d.reason());
        assertTrue(d.reply().text().contains("网络开小差"));
        assertEquals(1, log.size());
    }

    @Test
    void bothHangSafetyFallback() {
        CompletableFuture<OfflineCommandHit> offline = new CompletableFuture<>();
        CompletableFuture<Reply> llmF = new CompletableFuture<>();
        ArbiterDecision d = arbiter.decide(offline, llmF, ctx, "utt-42").join();
        assertEquals("safety_timeout", d.reason());
        assertEquals(1, log.size());
    }

    // ------------------------------------------------------------ 决策事件 utteranceId

    @Test
    void decisionEntryUsesPassedUtteranceId() {
        // 决策事件填调用方传入的 utteranceId（telemetry 贯通），而非 sessionId
        arbiter.decide(CompletableFuture.completedFuture(null),
                CompletableFuture.completedFuture(Reply.ofText("hi")), ctx, "utt-42").join();
        assertEquals("utt-42", log.get(0).utteranceId());
    }

    // ------------------------------------------------------------ B3 仲裁过程事件

    /** 事件 → "utt|received(route)" / "utt|won(route,reason,decision)" / "utt|lost(route,reason)"。 */
    private static String describe(Event e) {
        CloudArbiterEvent ev = e.event();
        return switch (ev.kind()) {
            case RECEIVED -> "received(" + ev.route() + ")";
            case WON -> "won(" + ev.route() + "," + ev.reason().wire() + "," + ev.decisionReason() + ")";
            case LOST -> "lost(" + ev.route() + "," + ev.reason().wire() + ")";
        };
    }

    private void assertEvents(String... expected) {
        List<String> actual = arbEvents.stream()
                .map(e -> e.utteranceId() + "|" + describe(e))
                .toList();
        assertEquals(List.of(expected), actual, "仲裁过程事件序列不符");
    }

    @Test
    void commandHitWinsThenLateLlmLoses() {
        // 命令词同步完成先到 → received(nlu-traditional) + won(priority/offline_won)；
        // LLM 10ms 迟到 → received(llm) + lost(command_already_won)（决策不变，CAS 拒绝）
        CompletableFuture<OfflineCommandHit> offline = CompletableFuture.completedFuture(hit("打开空调"));
        ArbiterDecision d = eventArbiter.decide(offline, llm("LLM回答", 10).chat("x", ctx), ctx, "utt-42").join();
        assertEquals("offline_won", d.reason());
        sleep(50); // 等迟到 LLM 的事件回调（sched 线程）
        assertEvents(
                "utt-42|received(nlu-traditional)",
                "utt-42|won(nlu-traditional,priority,offline_won)",
                "utt-42|received(llm)",
                "utt-42|lost(llm,command_already_won)");
    }

    @Test
    void llmWinsAfterGraceThenLateOfflineLoses() {
        // LLM 10ms 到达（离线未完成 → 宽限期），宽限期 300ms 到点 LLM 胜出；
        // 离线命中 600ms 才到 → received(nlu-traditional) + lost(llm_already_won)
        CompletableFuture<OfflineCommandHit> offline = offlineAt(600, hit("打开空调"));
        ArbiterDecision d = eventArbiter.decide(offline, llm("LLM回答", 10).chat("x", ctx), ctx, "utt-42").join();
        assertEquals("llm_reply", d.reason());
        sleep(650); // 等宽限期胜出 + 迟到离线事件
        assertEvents(
                "utt-42|received(llm)",
                "utt-42|won(llm,priority,llm_reply)",
                "utt-42|received(nlu-traditional)",
                "utt-42|lost(nlu-traditional,llm_already_won)");
    }

    @Test
    void graceWindowOfflineHitThenGraceTaskLateLlmLoses() {
        // LLM 10ms 到达（宽限期起），离线 100ms 命中（< grace）→ 离线胜出；
        // 宽限期 300ms 到点迟到 LLM → lost(command_already_won)
        CompletableFuture<OfflineCommandHit> offline = offlineAt(100, hit("打开空调"));
        ArbiterDecision d = eventArbiter.decide(offline, llm("LLM回答", 10).chat("x", ctx), ctx, "utt-42").join();
        assertEquals("offline_won", d.reason());
        sleep(GRACE + 100); // 等宽限期任务触发迟到 lost
        assertEvents(
                "utt-42|received(llm)",
                "utt-42|received(nlu-traditional)",
                "utt-42|won(nlu-traditional,priority,offline_won)",
                "utt-42|lost(llm,command_already_won)");
    }

    @Test
    void safetyTimeoutEmitsWonOnly() {
        // 双候选都不收敛 → safety 到点胜出（won(llm,llm_timeout,safety_timeout)）；
        // 空结果/错误不是候选 → 无 received 事件
        CompletableFuture<OfflineCommandHit> offline = new CompletableFuture<>();
        CompletableFuture<Reply> llmF = new CompletableFuture<>();
        ArbiterDecision d = eventArbiter.decide(offline, llmF, ctx, "utt-42").join();
        assertEquals("safety_timeout", d.reason());
        assertEvents("utt-42|won(llm,llm_timeout,safety_timeout)");
    }

    // ------------------------------------------------------------ 旧单路入口委托

    @Test
    void legacyDecideDelegatesToLlmOnly() {
        Reply r = arbiter.decide("打开空调", llm("LLM回答", 10), ctx, "utt-42").join();
        assertEquals("LLM回答", r.text());
        assertEquals(1, log.size());
        assertEquals("llm_reply", log.get(0).reason());
    }

    @Test
    void legacyDecideLlmErrorSafetyFallback() {
        Reply r = arbiter.decide("打开空调", llmError(10), ctx, "utt-42").join();
        assertTrue(r.text().contains("网络开小差"));
        assertEquals(1, log.size());
        assertEquals("safety_timeout", log.get(0).reason());
    }
}

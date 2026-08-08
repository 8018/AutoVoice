package com.autovoice.server.arbitration;

import com.autovoice.server.contracts.DecisionEntry;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.NluProvider;
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

class RaceArbiterTest {
    static final long GRACE = 100, SAFETY = 1000;
    final List<DecisionEntry> log = new ArrayList<>();
    final DecisionSink sink = log::add;
    final ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
    final RaceArbiter arbiter = new RaceArbiter(GRACE, SAFETY, sched, sink);
    final SessionContext ctx = new SessionContext("s1", "zh-CN", Map.of());

    @AfterEach
    void shutdownPool() {
        sched.shutdownNow();
    }

    NluProvider nlu(String text, long delayMs, boolean unknown) {
        return (t, c) -> CompletableFuture.supplyAsync(() -> {
            sleep(delayMs);
            return unknown ? Intent.unknown("test") : Intent.of("1.0", "climate", "set_temperature", Map.of(), 0.9, "test", null);
        }, sched);
    }
    LlmProvider llm(String text, long delayMs) {
        return (t, c) -> CompletableFuture.supplyAsync(() -> { sleep(delayMs); return Reply.ofText("LLM回答"); }, sched);
    }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); } }

    @Test void nluFirstWins() {
        Reply r = arbiter.decide("x", nlu("x", 10, false), llm("x", 300), ctx).join();
        assertEquals("action", r.kind()); // nlu 非拒识 → ofAction
        assertEquals("nlu_first", log.get(log.size()-1).reason());
    }
    @Test void llmFirstWaitsForNluWithinGrace() {
        Reply r = arbiter.decide("x", nlu("x", 60, false), llm("x", 5), ctx).join();
        assertEquals("nlu_first", log.get(log.size()-1).reason()); // nlu 60ms < GRACE 100ms
    }
    @Test void llmFirstNluRejectedThenLlm() {
        Reply r = arbiter.decide("x", nlu("x", 60, true), llm("x", 5), ctx).join();
        assertEquals("LLM回答", r.text());
        assertEquals("nlu_rejected_use_llm", log.get(log.size()-1).reason());
    }
    @Test void llmFirstNluTimeoutThenLlm() {
        Reply r = arbiter.decide("x", nlu("x", 500, false), llm("x", 5), ctx).join();
        assertEquals("LLM回答", r.text());
        assertEquals("llm_first_wait_timeout", log.get(log.size()-1).reason());
    }
    @Test void bothSlowSafetyFallback() {
        Reply r = arbiter.decide("x", nlu("x", 5000, false), llm("x", 5000), ctx).join();
        assertTrue(r.text().contains("网络开小差"));
        assertEquals("safety_timeout", log.get(log.size()-1).reason());
    }
}

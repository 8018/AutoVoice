package com.autovoice.server.gateway;

import com.autovoice.server.arbitration.DecisionSink;
import com.autovoice.server.arbitration.RaceArbiter;
import com.autovoice.server.contracts.AsrException;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.DecisionEntry;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.OfflineCommandProvider;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.SlotValue;
import com.autovoice.server.contracts.telemetry.TelemetryEvent;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.contracts.telemetry.TelemetryStages;
import com.autovoice.server.offlinecommand.OfflineCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流水线测试（双候选竞速 + TTS 解耦）：fake providers 全同步就绪
 * （completedFuture → arbiter 同步收敛），无需等待真实网络。
 * 决策事件经注入的 DecisionSink 收集断言。
 */
class SegmentPipelineTest {

    static final long SAFETY = 1000;
    static final long GRACE = 200;
    static final long ASR_FAIL_WAIT = 100;
    static final byte[] PCM = {0x01, 0x02, 0x03, 0x04};
    static final SessionContext CTX = new SessionContext("s1", "zh-CN", Map.of());

    final List<DecisionEntry> log = new ArrayList<>();
    final DecisionSink sink = log::add;
    final ScheduledExecutorService sched = Executors.newScheduledThreadPool(4);
    final List<TelemetryEvent> events = new ArrayList<>();
    final TelemetryRecorder recorder = (utt, e) -> events.add(e);

    @AfterEach
    void shutdownScheduler() {
        sched.shutdownNow();
    }

    RaceArbiter arbiter() {
        // B3：与装配层（VoiceGatewayHandler）相同的接线——仲裁事件 → telemetry 插桩
        return new RaceArbiter(SAFETY, GRACE, sched, sink,
                (uid, e) -> SegmentPipeline.recordArbiterEvent(recorder, uid, e));
    }

    static AsrProvider asr(String text) {
        return (pcm, ctx) -> text;
    }

    static AsrProvider asrFails() {
        return (pcm, ctx) -> {
            throw new AsrException("asr down");
        };
    }

    static LlmProvider llmAction() {
        return (t, ctx) -> CompletableFuture.completedFuture(
                Reply.ofAction(climateIntent(), "好的，空调温度已调到24度"));
    }

    static LlmProvider llmText(String text) {
        return (t, ctx) -> CompletableFuture.completedFuture(Reply.ofText(text));
    }

    static LlmProvider llmError() {
        return (t, ctx) -> CompletableFuture.failedFuture(new RuntimeException("llm down"));
    }

    static Intent climateIntent() {
        return Intent.of("1.0", "climate", "set_temperature",
                Map.of("temperature", SlotValue.number(24)), 0.95, "test", null);
    }

    /** 离线提供者：固定文本 → 由 RuleNlu 决定命中与否（"打开空调" 命中、"我想听歌" unknown）。 */
    static OfflineCommandService offline(String text) {
        return new OfflineCommandService((pcm, ctx) ->
                CompletableFuture.completedFuture(Optional.ofNullable(text)));
    }

    static OfflineCommandService offlineMiss() {
        return new OfflineCommandService(new com.autovoice.server.offlinecommand.NoopOfflineCommandProvider());
    }

    SegmentPipeline pipeline(AsrProvider asr, OfflineCommandService offline) {
        return new SegmentPipeline(asr, arbiter(), llmAction(), offline, ASR_FAIL_WAIT, sink, recorder);
    }

    // ------------------------------------------------------------ 竞速收敛

    @Test
    void offlineHitWinsOverLlm() {
        // 离线命中（"打开空调" → RuleNlu climate/power_on）→ offline_won，无 TTS、无音频
        SegmentPipeline p = pipeline(asr("把空调调到二十四度"), offline("打开空调"));
        SegmentPipeline.SegmentResult r = p.handleSegment(PCM, CTX, "u-1");
        assertEquals("offline_won", log.get(0).reason());
        assertEquals("nlu-traditional", log.get(0).route());
        assertEquals("action", replyKind(r));
        assertNotNull(r.intent());
        assertEquals("climate", r.intent().domain());
        assertEquals("power_on", r.intent().intent());
        assertEquals("打开空调", r.asrText(), "离线胜出时 asrText = 离线原文");
        assertEquals("好的，空调已打开", r.speakText());
        assertEquals(1, log.size());
    }

    @Test
    void offlineMissLlmActionWins() {
        // 离线未命中 + LLM 车控 → llm_reply；asrText = ASR 文本
        SegmentPipeline p = pipeline(asr("把空调调到二十四度"), offlineMiss());
        SegmentPipeline.SegmentResult r = p.handleSegment(PCM, CTX, "u-1");
        assertEquals("llm_reply", log.get(0).reason());
        assertNotNull(r.intent());
        assertEquals("set_temperature", r.intent().intent());
        assertEquals("把空调调到二十四度", r.asrText());
        assertEquals("好的，空调温度已调到24度", r.speakText());
        assertEquals(1, log.size());
    }

    @Test
    void offlineMissLlmTextReply() {
        // 离线未命中 + LLM 闲聊 → kind=text 且 text 与 speakText 同带（端侧 parseReply 强读 text）
        SegmentPipeline p = new SegmentPipeline(asr("今天天气怎么样"), arbiter(),
                llmText("今天天气不错"), offlineMiss(), ASR_FAIL_WAIT, sink, recorder);
        SegmentPipeline.SegmentResult r = p.handleSegment(PCM, CTX, "u-1");
        assertEquals("text", replyKind(r));
        assertEquals("今天天气不错", r.text());
        assertEquals("今天天气不错", r.speakText());
        assertNull(r.intent());
        assertEquals("今天天气怎么样", r.asrText());
        assertEquals("llm_reply", log.get(0).reason());
    }

    @Test
    void llmErrorFallsBackToSafety() {
        // LLM future 异常完成 → RaceArbiter safety 兜底（safety_timeout + 兜底话术）
        SegmentPipeline p = new SegmentPipeline(asr("x"), arbiter(), llmError(),
                offlineMiss(), ASR_FAIL_WAIT, sink, recorder);
        SegmentPipeline.SegmentResult r = p.handleSegment(PCM, CTX, "u-1");
        assertEquals("safety_timeout", log.get(0).reason());
        assertEquals("网络开小差了，请稍后再试", r.speakText());
        assertNull(r.intent());
    }

    // ------------------------------------------------------------ ASR 失败路径

    @Test
    void asrFailureOfflineHitWins() {
        // ASR 抛异常 + 离线窗口内命中 → 离线回复（offline_won 由 pipeline 记日志）
        SegmentPipeline p = pipeline(asrFails(), offline("打开空调"));
        SegmentPipeline.SegmentResult r = p.handleSegment(PCM, CTX, "u-1");
        assertEquals("offline_won", log.get(0).reason());
        assertEquals("nlu-traditional", log.get(0).route());
        assertNotNull(r.intent());
        assertEquals("power_on", r.intent().intent());
        assertEquals("打开空调", r.asrText());
        assertEquals("好的，空调已打开", r.speakText());
    }

    @Test
    void asrFailureOfflineMissFallsBack() {
        // ASR 抛异常 + 离线未命中 → asr_failed_fallback 兜底话术
        SegmentPipeline p = pipeline(asrFails(), offlineMiss());
        SegmentPipeline.SegmentResult r = p.handleSegment(PCM, CTX, "u-1");
        assertEquals("asr_failed_fallback", log.get(0).reason());
        assertEquals("网络开小差了，请稍后再试", r.speakText());
        assertNull(r.intent());
        assertNull(r.asrText());
        assertEquals(1, log.size());
    }

    @Test
    void asrFailureOfflineSlowTimesOutFallsBack() {
        // ASR 失败 + 离线在 ASR_FAIL_WAIT 之后才到 → 窗口超时，仍走 asr_failed_fallback
        OfflineCommandService slowOffline = new OfflineCommandService((pcm, ctx) -> {
            CompletableFuture<Optional<String>> f = new CompletableFuture<>();
            sched.schedule(() -> f.complete(Optional.of("打开空调")), 500,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            return f;
        });
        SegmentPipeline p = pipeline(asrFails(), slowOffline);
        SegmentPipeline.SegmentResult r = p.handleSegment(PCM, CTX, "u-1");
        assertEquals("asr_failed_fallback", log.get(0).reason());
        assertEquals("网络开小差了，请稍后再试", r.speakText());
        assertEquals(1, log.size(), "迟到的离线命中不得写日志");
    }

    @Test
    void blankTranscriptionFallsBack() {
        // ASR 返回空白 → 等同 ASR 失败（离线未命中）→ asr_failed_fallback
        SegmentPipeline p = pipeline(asr("   "), offlineMiss());
        SegmentPipeline.SegmentResult r = p.handleSegment(PCM, CTX, "u-1");
        assertEquals("asr_failed_fallback", log.get(0).reason());
        assertEquals("网络开小差了，请稍后再试", r.speakText());
    }

    /** 语义 kind：intent 非空 → action，否则 text（pipeline 层无 audio 概念）。 */
    private static String replyKind(SegmentPipeline.SegmentResult r) {
        return r.intent() != null ? "action" : "text";
    }

    // ------------------------------------------------------------ 链路插桩（Task 4）

    @Test
    void recordsCloudAsrAndArbiterEvents() {
        // 纯云端轮次（离线未命中）：cloud_asr ok 事件（text + durationMs）+
        // B3 仲裁过程事件 received(llm) + won(llm, priority, decision=llm_reply)
        SegmentPipeline p = pipeline(asr("空调调到二十四度"), offlineMiss());
        p.handleSegment(PCM, CTX, "utt-9");

        TelemetryEvent asrEvent = events.stream()
                .filter(e -> TelemetryStages.CLOUD_ASR.equals(e.stage()))
                .findFirst().orElseThrow();
        assertEquals("info", asrEvent.level());
        assertEquals("空调调到二十四度", asrEvent.payload().get("text"));
        assertTrue(asrEvent.payload().containsKey("durationMs"), "cloud_asr 事件应带 durationMs");

        TelemetryEvent receivedEvent = events.stream()
                .filter(e -> TelemetryStages.CLOUD_ARBITER_RECEIVED.equals(e.stage()))
                .findFirst().orElseThrow();
        assertEquals("info", receivedEvent.level());
        assertEquals("llm", receivedEvent.payload().get("route"));

        TelemetryEvent wonEvent = events.stream()
                .filter(e -> TelemetryStages.CLOUD_ARBITER_WON.equals(e.stage()))
                .findFirst().orElseThrow();
        assertEquals("info", wonEvent.level());
        assertEquals("llm", wonEvent.payload().get("route"));
        assertEquals("priority", wonEvent.payload().get("reason"));
        assertEquals("llm_reply", wonEvent.payload().get("decision"));
    }

    @Test
    void recordsOfflineWonArbiterEventAndAsrFailure() {
        // ASR 失败 + 离线命中：cloud_asr warn 事件（error）+ B3 降级路径事件
        // received(nlu-traditional) + won(nlu-traditional, priority, decision=offline_won)
        SegmentPipeline p = pipeline(asrFails(), offline("打开空调"));
        p.handleSegment(PCM, CTX, "utt-10");

        TelemetryEvent asrEvent = events.stream()
                .filter(e -> TelemetryStages.CLOUD_ASR.equals(e.stage()))
                .findFirst().orElseThrow();
        assertEquals("warn", asrEvent.level());
        assertTrue(asrEvent.payload().containsKey("error"), "ASR 失败事件应带 error");

        TelemetryEvent receivedEvent = events.stream()
                .filter(e -> TelemetryStages.CLOUD_ARBITER_RECEIVED.equals(e.stage()))
                .findFirst().orElseThrow();
        assertEquals("nlu-traditional", receivedEvent.payload().get("route"));

        TelemetryEvent wonEvent = events.stream()
                .filter(e -> TelemetryStages.CLOUD_ARBITER_WON.equals(e.stage()))
                .findFirst().orElseThrow();
        assertEquals("nlu-traditional", wonEvent.payload().get("route"));
        assertEquals("priority", wonEvent.payload().get("reason"));
        assertEquals("offline_won", wonEvent.payload().get("decision"));
    }
}

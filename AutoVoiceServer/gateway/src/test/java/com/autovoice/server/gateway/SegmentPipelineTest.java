package com.autovoice.server.gateway;

import com.autovoice.server.arbitration.DecisionSink;
import com.autovoice.server.arbitration.RaceArbiter;
import com.autovoice.server.contracts.AsrException;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.DecisionEntry;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.OfflineCommandProvider;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.SlotValue;
import com.autovoice.server.contracts.telemetry.TelemetryEvent;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.contracts.telemetry.TelemetryStages;
import com.autovoice.server.offlinecommand.OfflineCommandService;
import com.autovoice.server.speechclassic.ClassicOnlineSpeechProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    static OnlineSpeechProvider online(CompletableFuture<OnlineSpeechResult> result) {
        return new OnlineSpeechProvider() {
            @Override
            public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm16k, SessionContext context, String utteranceId) {
                return result;
            }

            @Override
            public String id() {
                return "test-s2s";
            }
        };
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
        return new SegmentPipeline(new ClassicOnlineSpeechProvider(asr, llmAction()), arbiter(),
                offline, ASR_FAIL_WAIT, sink, recorder);
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
        SegmentPipeline p = new SegmentPipeline(
                new ClassicOnlineSpeechProvider(asr("今天天气怎么样"), llmText("今天天气不错")),
                arbiter(), offlineMiss(), ASR_FAIL_WAIT, sink, recorder);
        SegmentPipeline.SegmentResult r = p.handleSegment(PCM, CTX, "u-1");
        assertEquals("text", replyKind(r));
        assertEquals("今天天气不错", r.text());
        assertEquals("今天天气不错", r.speakText());
        assertNull(r.intent());
        assertEquals("今天天气怎么样", r.asrText());
        assertEquals("llm_reply", log.get(0).reason());
    }

    @Test
    void offlineMissReleasesS2sAudioCandidate() {
        byte[] wav = {'R', 'I', 'F', 'F', 1, 2};
        OnlineSpeechProvider s2s = online(
                CompletableFuture.completedFuture(new OnlineSpeechResult(
                        Reply.ofAudio("audio/wav", wav, "好的", null), "")));
        SegmentPipeline p = new SegmentPipeline(s2s, arbiter(), offlineMiss(),
                ASR_FAIL_WAIT, sink, recorder);

        SegmentPipeline.SegmentResult result = p.handleSegment(PCM, CTX, "u-s2s");

        assertEquals("audio/wav", result.mime());
        assertEquals("好的", result.speakText());
        assertTrue(java.util.Arrays.equals(wav, result.audio()));
        assertEquals("llm_reply", log.get(0).reason());
    }

    @Test
    void cloudOfflineHitCancelsS2sCandidate() {
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<OnlineSpeechResult> pending = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                cancelled.set(true);
                return super.cancel(mayInterruptIfRunning);
            }
        };
        SegmentPipeline p = new SegmentPipeline(online(pending), arbiter(), offline("打开空调"),
                ASR_FAIL_WAIT, sink, recorder);

        SegmentPipeline.SegmentResult result = p.handleSegment(PCM, CTX, "u-s2s-cancel");

        assertEquals("power_on", result.intent().intent());
        assertTrue(cancelled.get(), "云端空调离线命中后必须向 S2S 会话传播取消");
    }

    @Test
    void safetyTimeoutCancelsProviderAndAbortsStartedAudioStream() {
        AtomicBoolean futureCancelled = new AtomicBoolean();
        AtomicBoolean providerCancelled = new AtomicBoolean();
        CompletableFuture<OnlineSpeechResult> pending = new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt) {
                futureCancelled.set(true);
                return super.cancel(interrupt);
            }
        };
        OnlineSpeechProvider streaming = new OnlineSpeechProvider() {
            @Override public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, SessionContext ctx, String uid) { return pending; }
            @Override public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, SessionContext ctx, String uid, OnlineAudioSink audio) {
                audio.onStart(24_000, 1, "pcm_s16le");
                audio.onChunk(new byte[]{1, 2});
                return pending;
            }
            @Override public void cancel(String uid) { providerCancelled.set(true); }
            @Override public String id() { return "hung-stream"; }
        };
        RaceArbiter quickTimeout = new RaceArbiter(30, GRACE, sched, sink,
                (uid, event) -> SegmentPipeline.recordArbiterEvent(recorder, uid, event));
        AtomicBoolean streamError = new AtomicBoolean();
        SegmentPipeline p = new SegmentPipeline(streaming, quickTimeout, offlineMiss(),
                ASR_FAIL_WAIT, sink, recorder);

        SegmentPipeline.SegmentResult result = p.handleSegment(PCM, CTX, "u-timeout", "seg-timeout",
                new OnlineAudioSink() {
                    @Override public void onError(Throwable error) { streamError.set(true); }
                });

        assertEquals("safety_timeout", log.get(0).reason());
        assertTrue(result.streamed(), "已开始的流由 error 收口，不应再发送第二个普通 reply");
        assertTrue(providerCancelled.get(), "超时必须中止 provider 的 HTTP/工具调用");
        assertTrue(futureCancelled.get(), "超时必须释放 provider 工作线程 future");
        assertTrue(streamError.get(), "已下发 audio_reply_start 后必须显式结束客户端流");
    }

    @Test
    void s2sChunksStayBehindCloudGateUntilOfflineMissIsKnown() throws Exception {
        CompletableFuture<Optional<String>> offlineRaw = new CompletableFuture<>();
        OfflineCommandService delayedOffline = new OfflineCommandService((pcm, ctx) -> offlineRaw);
        List<String> audioEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
        OnlineSpeechProvider streaming = new OnlineSpeechProvider() {
            @Override public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, SessionContext ctx, String uid) {
                throw new AssertionError("stream overload expected");
            }
            @Override public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, SessionContext ctx, String uid, OnlineAudioSink sink) {
                sink.onStart(24_000, 1, "pcm_s16le");
                sink.onChunk(new byte[]{1, 2});
                sink.onComplete("ok", null);
                return CompletableFuture.completedFuture(new OnlineSpeechResult(
                        Reply.ofAudio("audio/wav", new byte[]{1}, "ok", null), ""));
            }
            @Override public String id() { return "stream-test"; }
        };
        SegmentPipeline p = new SegmentPipeline(streaming, arbiter(), delayedOffline,
                ASR_FAIL_WAIT, sink, recorder);
        OnlineAudioSink downstream = new OnlineAudioSink() {
            @Override public void onStart(int rate, int channels, String encoding) { audioEvents.add("start"); }
            @Override public void onChunk(byte[] pcm) { audioEvents.add("chunk"); }
            @Override public void onComplete(String text, Intent intent) { audioEvents.add("end"); }
        };

        CompletableFuture<SegmentPipeline.SegmentResult> result = CompletableFuture.supplyAsync(
                () -> p.handleSegment(PCM, CTX, "u-gate", "seg-gate", downstream));
        Thread.sleep(30);
        assertTrue(audioEvents.isEmpty(), "离线仍 pending 时不得越过云端仲裁门");
        offlineRaw.complete(Optional.empty());

        assertTrue(result.get(1, java.util.concurrent.TimeUnit.SECONDS).streamed());
        assertEquals(List.of("start", "chunk", "end"), audioEvents);
    }

    @Test
    void s2sChunksReleaseWhenCloudArbiterGraceExpires() {
        CompletableFuture<Optional<String>> offlineRaw = new CompletableFuture<>();
        OfflineCommandService delayedOffline = new OfflineCommandService((pcm, ctx) -> offlineRaw);
        List<String> audioEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
        OnlineSpeechProvider streaming = new OnlineSpeechProvider() {
            @Override public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, SessionContext ctx, String uid) {
                throw new AssertionError("stream overload expected");
            }
            @Override public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, SessionContext ctx, String uid, OnlineAudioSink audio) {
                audio.onStart(24_000, 1, "pcm_s16le");
                audio.onChunk(new byte[]{3, 4});
                audio.onComplete("ok", null);
                return CompletableFuture.completedFuture(new OnlineSpeechResult(
                        Reply.ofAudio("audio/wav", new byte[]{1}, "ok", null), ""));
            }
            @Override public String id() { return "stream-test"; }
        };
        RaceArbiter shortGrace = new RaceArbiter(SAFETY, 20, sched, sink,
                (uid, event) -> SegmentPipeline.recordArbiterEvent(recorder, uid, event));
        SegmentPipeline p = new SegmentPipeline(streaming, shortGrace, delayedOffline,
                ASR_FAIL_WAIT, sink, recorder);
        OnlineAudioSink downstream = new OnlineAudioSink() {
            @Override public void onStart(int rate, int channels, String encoding) { audioEvents.add("start"); }
            @Override public void onChunk(byte[] pcm) { audioEvents.add("chunk"); }
            @Override public void onComplete(String text, Intent intent) { audioEvents.add("end"); }
        };

        SegmentPipeline.SegmentResult result = p.handleSegment(
                PCM, CTX, "u-grace", "seg-grace", downstream);

        assertTrue(result.streamed());
        assertEquals("llm_reply", log.get(0).reason());
        assertEquals(List.of("start", "chunk", "end"), audioEvents);
    }

    @Test
    void llmErrorFallsBackToSafety() {
        // LLM future 异常完成 → RaceArbiter safety 兜底（safety_timeout + 兜底话术）
        SegmentPipeline p = new SegmentPipeline(new ClassicOnlineSpeechProvider(asr("x"), llmError()),
                arbiter(), offlineMiss(), ASR_FAIL_WAIT, sink, recorder);
        SegmentPipeline.SegmentResult r = p.handleSegment(PCM, CTX, "u-1");
        assertEquals("safety_timeout", log.get(0).reason());
        assertEquals("网络开小差了，请稍后再试", r.speakText());
        assertNull(r.intent());
    }

    // ------------------------------------------------------------ void（cancel_turn / superseded 拦截）

    @Test
    void voidTurnReturnsNullWithoutCancellingProvider() throws Exception {
        // 在途轮被 void：handleSegment 立即返回 null（无结果），provider 与 future 都未被取消
        // （拦截而非取消）；决策日志与 telemetry 事件均为空
        AtomicBoolean providerCancelled = new AtomicBoolean();
        AtomicBoolean futureCancelled = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<OnlineSpeechResult> pending = new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt) {
                futureCancelled.set(true);
                return super.cancel(interrupt);
            }
        };
        OnlineSpeechProvider hung = new OnlineSpeechProvider() {
            @Override public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, SessionContext ctx, String uid) {
                entered.countDown();
                return pending;
            }
            @Override public void cancel(String uid) { providerCancelled.set(true); }
            @Override public String id() { return "hung"; }
        };
        RaceArbiter arb = arbiter();
        // 离线候选保持 pending：void 前不会提前发 pending 事件（消掉前置竞态），
        // void 后由 settledByVoid 守卫拦截——telemetry 事件全程为空
        CompletableFuture<Optional<String>> offlineRaw = new CompletableFuture<>();
        OfflineCommandService pendingOffline = new OfflineCommandService((pcm, ctx) -> offlineRaw);
        SegmentPipeline p = new SegmentPipeline(hung, arb, pendingOffline, ASR_FAIL_WAIT, sink, recorder);

        CompletableFuture<SegmentPipeline.SegmentResult> result = CompletableFuture.supplyAsync(
                () -> p.handleSegment(PCM, CTX, "u-void", "seg-void"));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(arb.voidTurn("u-void", RaceArbiter.REASON_CANCEL_TURN));

        assertNull(result.get(1, TimeUnit.SECONDS), "被 void 的轮无结果");
        assertFalse(providerCancelled.get(), "void 不取消 provider");
        assertFalse(futureCancelled.get(), "void 不取消候选 future");
        assertTrue(log.isEmpty());
        assertTrue(events.isEmpty());
    }

    @Test
    void voidTurnNeverReleasesBufferedAudioPastGate() throws Exception {
        // void 后即使在线候选自行完成，缓冲音频也永不过仲裁门（gate 已 reject）
        CompletableFuture<Optional<String>> offlineRaw = new CompletableFuture<>();
        OfflineCommandService delayedOffline = new OfflineCommandService((pcm, ctx) -> offlineRaw);
        List<String> audioEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1);
        OnlineSpeechProvider streaming = new OnlineSpeechProvider() {
            @Override public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, SessionContext ctx, String uid) {
                throw new AssertionError("stream overload expected");
            }
            @Override public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, SessionContext ctx, String uid, OnlineAudioSink sink) {
                entered.countDown();
                sink.onStart(24_000, 1, "pcm_s16le"); // 离线仍 pending：gate 只缓冲不放行
                sink.onChunk(new byte[]{1, 2});
                return CompletableFuture.completedFuture(new OnlineSpeechResult(
                        Reply.ofAudio("audio/wav", new byte[]{1}, "ok", null), ""));
            }
            @Override public String id() { return "stream-gate"; }
        };
        RaceArbiter arb = arbiter();
        SegmentPipeline p = new SegmentPipeline(streaming, arb, delayedOffline, ASR_FAIL_WAIT, sink, recorder);
        OnlineAudioSink downstream = new OnlineAudioSink() {
            @Override public void onStart(int rate, int channels, String encoding) { audioEvents.add("start"); }
            @Override public void onChunk(byte[] pcm) { audioEvents.add("chunk"); }
            @Override public void onComplete(String text, Intent intent) { audioEvents.add("end"); }
        };

        CompletableFuture<SegmentPipeline.SegmentResult> result = CompletableFuture.supplyAsync(
                () -> p.handleSegment(PCM, CTX, "u-gate-void", "seg-gate-void", downstream));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(arb.voidTurn("u-gate-void", RaceArbiter.REASON_SUPERSEDED));

        assertNull(result.get(1, TimeUnit.SECONDS));
        offlineRaw.complete(Optional.empty()); // 在线候选照常完成——输出已被 gate 拒绝
        Thread.sleep(50);
        assertTrue(audioEvents.isEmpty(), "void 后缓冲音频不得越过仲裁门");
        assertTrue(log.isEmpty());
        // 事件层面：至多 void 前已发出的 received（候选已完成的前置竞态），绝无 won/lost
        assertTrue(events.stream().noneMatch(e -> TelemetryStages.CLOUD_ARBITER_WON.equals(e.stage())
                        || TelemetryStages.CLOUD_ARBITER_LOST.equals(e.stage())),
                "void 轮不得产生 won/lost 事件: " + events);
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

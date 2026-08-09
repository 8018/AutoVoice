package com.autovoice.server.gateway;

import com.autovoice.server.arbitration.DecisionSink;
import com.autovoice.server.arbitration.RaceArbiter;
import com.autovoice.server.contracts.AsrException;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.DecisionEntry;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.SlotValue;
import com.autovoice.server.contracts.TtsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 流水线测试：fake providers 全同步就绪（completedFuture → arbiter 同步收敛），
 * 无需等待真实网络。决策事件经注入的 DecisionSink 收集断言。
 * 语义统一由 LLM function calling 承担（action 回复），原 NLU 链路退役。
 */
class SegmentPipelineTest {

    static final long SAFETY = 1000;
    static final byte[] WAV = {0x52, 0x49, 0x46, 0x46, 0x00};
    static final byte[] PCM = {0x01, 0x02, 0x03, 0x04};
    static final SessionContext CTX = new SessionContext("s1", "zh-CN", Map.of());

    final List<DecisionEntry> log = new ArrayList<>();
    final DecisionSink sink = log::add;
    final ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);

    @AfterEach
    void shutdownScheduler() {
        sched.shutdownNow();
    }

    RaceArbiter arbiter() {
        return new RaceArbiter(SAFETY, sched, sink);
    }

    static AsrProvider asr(String text) {
        return (pcm, ctx) -> text;
    }

    static LlmProvider llmAction() {
        return (t, ctx) -> CompletableFuture.completedFuture(
                Reply.ofAction(climateIntent(), "好的，空调温度已调到24度"));
    }

    static LlmProvider llmText(String text) {
        return (t, ctx) -> CompletableFuture.completedFuture(Reply.ofText(text));
    }

    static TtsProvider ttsWav() {
        return (text, ctx) -> Reply.ofAudio("audio/wav", WAV);
    }

    static Intent climateIntent() {
        return Intent.of("1.0", "climate", "set_temperature",
                Map.of("temperature", SlotValue.number(24)), 0.95, "test", null);
    }

    @Test
    void llmActionWinsFullPipeline() {
        // LLM function calling 产出 action 回复 → llm_reply → speakText=reply.speakText() → TTS 合成
        SegmentPipeline p = new SegmentPipeline(asr("把空调调到二十四度"), arbiter(),
                llmAction(), ttsWav(), sink);
        SegmentPipeline.SegmentResult r = p.handleSegment(PCM, CTX, "u-1");
        assertArrayEquals(WAV, r.wavAudio());
        assertEquals("好的，空调温度已调到24度", r.speakText());
        assertNotNull(r.intent());
        assertEquals("set_temperature", r.intent().intent());
        assertEquals(1, log.size());
        assertEquals("llm_reply", log.get(0).reason());
    }

    @Test
    void llmTextReplyFallsThrough() {
        // LLM 自由文本回复（闲聊）：speakText=reply.text()、intent=null
        SegmentPipeline p = new SegmentPipeline(asr("今天天气怎么样"), arbiter(),
                llmText("今天天气不错"), ttsWav(), sink);
        SegmentPipeline.SegmentResult r = p.handleSegment(PCM, CTX, "u-1");
        assertEquals("今天天气不错", r.speakText());
        assertNull(r.intent());
        assertArrayEquals(WAV, r.wavAudio());
        assertEquals(1, log.size());
        assertEquals("llm_reply", log.get(0).reason());
    }

    @Test
    void ttsFailureDegradesToText() {
        // TTS 抛 RuntimeException（Task 9 语义）→ wavAudio=null 而 speakText 保留（下行降级 kind=text）
        SegmentPipeline p = new SegmentPipeline(asr("x"), arbiter(), llmAction(),
                (text, ctx) -> {
                    throw new RuntimeException("tts down");
                }, sink);
        SegmentPipeline.SegmentResult r = p.handleSegment(PCM, CTX, "u-1");
        assertNull(r.wavAudio());
        assertEquals("好的，空调温度已调到24度", r.speakText());
        assertNotNull(r.intent(), "TTS 失败仅降级音频，arbiter 的意图决策仍保留");
        assertEquals(1, log.size());
        assertEquals("llm_reply", log.get(0).reason());
    }

    @Test
    void asrFailureFallsBack() {
        // ASR 抛 AsrException → 兜底话术（不合成音频、intent=null）+ 一条 asr_failed_fallback 决策事件
        SegmentPipeline p = new SegmentPipeline(
                (pcm, ctx) -> {
                    throw new AsrException("asr down");
                }, arbiter(), llmAction(), ttsWav(), sink);
        SegmentPipeline.SegmentResult r = p.handleSegment(PCM, CTX, "u-1");
        assertNull(r.wavAudio());
        assertEquals("网络开小差了，请稍后再试", r.speakText());
        assertNull(r.intent());
        assertEquals(1, log.size());
        assertEquals("asr_failed_fallback", log.get(0).reason());
    }

    @Test
    void blankTranscriptionIsAsrFailure() {
        SegmentPipeline p = new SegmentPipeline(asr("   "), arbiter(), llmAction(),
                ttsWav(), sink);
        SegmentPipeline.SegmentResult r = p.handleSegment(PCM, CTX, "u-1");
        assertNull(r.wavAudio());
        assertEquals("网络开小差了，请稍后再试", r.speakText());
        assertEquals("asr_failed_fallback", log.get(0).reason());
    }
}

package com.autovoice.server.gateway;

import com.autovoice.server.contracts.AsrException;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.RealtimeChatProvider;
import com.autovoice.server.contracts.RealtimeChatSession;
import com.autovoice.server.contracts.RealtimeChatSink;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.StreamingAsrProvider;
import com.autovoice.server.contracts.StreamingAsrSession;
import com.autovoice.server.contracts.TtsProvider;
import com.autovoice.server.contracts.telemetry.NoopTelemetryRecorder;
import com.autovoice.server.offlinecommand.NoopOfflineCommandProvider;
import com.autovoice.server.offlinecommand.OfflineCommandService;
import com.autovoice.server.session.SessionRegistry;
import com.autovoice.server.speechclassic.ClassicOnlineSpeechProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 处理器接线测试：手写 WebSocketSession stub 捕获下行消息，验证
 * 握手回调、二进制帧累积、decision→reply 下发顺序与下行 kind 收敛（Classic 为
 * text/action，S2S 为 audio）。
 * 测试环境用极短仲裁参数（safety 1s），fake providers 同步就绪。
 */
class VoiceGatewayHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] WAV = {0x52, 0x49, 0x46, 0x46};
    private static final long SAFETY = 1000;
    private static final long ASR_FAIL_WAIT = 100;

    private final SessionRegistry registry = new SessionRegistry();

    private static OfflineCommandService noopOffline() {
        return new OfflineCommandService(new NoopOfflineCommandProvider());
    }

    private static OfflineCommandService hitOffline(String text) {
        return new OfflineCommandService((pcm, ctx) ->
                java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.of(text)));
    }

    // ---------- 接线：hello → ready ----------

    @Test
    void helloGetsReadyWithAdoptedSession() {
        VoiceGatewayHandler h = newHandler(asr("x"), llm("LLM"), ttsOk());
        StubSession s = open(h);
        h.handleMessage(s, new TextMessage(hello()));

        assertEquals(1, s.sent.size());
        JsonNode ready = parse(s.sent.get(0));
        assertEquals("ready", ready.get("type").asText());
        JsonNode p = ready.get("payload");
        assertTrue(p.has("sessionId"));
        assertEquals("zh-CN", p.get("language").asText());
        assertEquals("1.1", p.get("protocolVersion").asText());
        // 时钟同步：ready 携带服务器墙钟毫秒（客户端估算偏移用）；±5s 容忍避免 flaky
        assertTrue(p.has("serverTime"), "ready 应携带 serverTime");
        long serverTime = p.get("serverTime").asLong();
        assertTrue(Math.abs(serverTime - System.currentTimeMillis()) < 5_000L,
                "serverTime 应近似当前时间，实际偏差 %d ms".formatted(System.currentTimeMillis() - serverTime));
        // 会话已登记（demo-1 不存在 → 新建）
        assertNotNull(registry.get(p.get("sessionId").asText()));
    }

    // ---------- 接线：完整段 → decision 先行 + reply(action，无音频) ----------

    @Test
    void realtimeChatBinaryBypassesSegmentPipelineAndStreamsUnsolicitedReply() throws Exception {
        AtomicInteger ordinaryCalls = new AtomicInteger();
        AtomicReference<byte[]> received = new AtomicReference<>();
        class RealtimeOnline implements OnlineSpeechProvider, RealtimeChatProvider {
            @Override public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, SessionContext ctx, String uid) {
                ordinaryCalls.incrementAndGet();
                return CompletableFuture.completedFuture(new OnlineSpeechResult(Reply.ofText("wrong"), ""));
            }
            @Override public RealtimeChatSession openRealtimeChat(SessionContext ctx, RealtimeChatSink sink) {
                return new RealtimeChatSession() {
                    @Override public void appendAudio(byte[] pcm) {
                        received.set(pcm);
                        sink.onStart(24_000, 1, "pcm_s16le");
                        sink.onChunk(new byte[]{7, 8});
                        sink.onReplyText("你好", true);
                        sink.onComplete("你好", null, "");
                    }
                    @Override public void close() { }
                };
            }
            @Override public String id() { return "realtime-test"; }
        }
        VoiceGatewayHandler h = new VoiceGatewayHandler(new RealtimeOnline(), ttsOk(), noopOffline(),
                registry, SAFETY, ASR_FAIL_WAIT);
        StubSession s = open(h);
        String sid = handshake(h, s);
        h.handleMessage(s, new TextMessage(
                "{\"type\":\"chat_start\",\"payload\":{\"sessionId\":\"" + sid + "\"}}"));
        awaitType(s, "chat_ready");
        h.handleMessage(s, new BinaryMessage(new byte[]{1, 2, 3}));
        awaitType(s, "audio_reply_end");

        assertArrayEquals(new byte[]{1, 2, 3}, received.get());
        assertEquals(0, ordinaryCalls.get(), "连续闲聊不得进入普通 ASR/离线/仲裁 SegmentPipeline");
        JsonNode start = findTextFrame(s, "audio_reply_start");
        assertTrue(start.get("payload").get("chat").asBoolean());
        assertTrue(s.sent.stream().anyMatch(message -> message instanceof BinaryMessage));
        h.close();
    }

    @Test
    void fullSegmentAccumulatesPcmAndEmitsDecisionBeforeActionReply() throws InterruptedException {
        byte[][] asrReceived = new byte[1][];
        VoiceGatewayHandler h = newHandler((pcm, ctx) -> {
            asrReceived[0] = pcm;
            return "把空调调到二十四度";
        }, llmAction(), ttsOk());
        StubSession s = open(h);
        h.handleMessage(s, new TextMessage(hello()));
        String sid = parse(s.sent.get(0)).get("payload").get("sessionId").asText();

        // audio_start 携带客户端生成的 segmentId（每轮话语唯一，reply/error 原样回显）
        h.handleMessage(s, new TextMessage(audioStart(sid, "seg-1")));
        h.handleMessage(s, new BinaryMessage(new byte[]{1, 2}));
        h.handleMessage(s, new BinaryMessage(new byte[]{3, 4}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));

        awaitSent(s, 5); // ready + ASR 话语成立 + 独立文本 + decision + reply

        assertEquals("asr_turn_started", parse(s.sent.get(1)).get("type").asText());
        JsonNode asrPartial = parse(s.sent.get(2));
        assertEquals("asr_partial", asrPartial.get("type").asText());
        assertEquals("把空调调到二十四度", asrPartial.get("payload").get("text").asText());
        // ASR 不等仲裁；语义链内仍保持 decision 先于 reply。
        JsonNode decision = parse(s.sent.get(3));
        assertEquals("decision", decision.get("type").asText());
        assertEquals("llm_reply", decision.get("payload").get("reason").asText());
        assertEquals("cloud", decision.get("payload").get("arbiter").asText());
        assertEquals("u-1", decision.get("payload").get("utteranceId").asText(),
                "旧客户端（无 utteranceId）回退自增 u-N，行为与改造前一致");

        // TTS 解耦：reply 只携带语义（action + speakText），无 mime/dataBase64
        JsonNode reply = parse(s.sent.get(4));
        assertEquals("reply", reply.get("type").asText());
        JsonNode p = reply.get("payload");
        assertEquals("action", p.get("kind").asText());
        assertFalse(p.has("mime"), "TTS 解耦后 reply 不得携带音频");
        assertFalse(p.has("dataBase64"), "TTS 解耦后 reply 不得携带音频");
        assertEquals("已为您执行空调指令", p.get("speakText").asText());
        assertEquals("set_temperature", p.get("intent").get("intent").asText());
        assertEquals("把空调调到二十四度", p.get("asrText").asText(), "reply 应携带 ASR 识别文本");
        assertEquals("seg-1", p.get("segmentId").asText(), "reply 应回显 audio_start 的 segmentId");

        // 二进制帧已按序累积为完整 PCM 交给 ASR
        assertArrayEquals(new byte[]{1, 2, 3, 4}, asrReceived[0]);
    }

    @Test
    void audioStartClientUtteranceIdFlowsToDecision() throws InterruptedException {
        // 端侧 utteranceId 贯通：audio_start 携带 utteranceId → 决策事件原样采纳（不回落自增 u-N）
        VoiceGatewayHandler h = newHandler(asr("x"), llm("LLM"), ttsOk());
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStartWithUtteranceId(sid, "utt-custom-1")));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));
        awaitSent(s, 3);

        JsonNode decision = parse(s.sent.get(1));
        assertEquals("decision", decision.get("type").asText());
        assertEquals("utt-custom-1", decision.get("payload").get("utteranceId").asText(),
                "决策事件应携带端侧 utteranceId");
    }

    @Test
    void reconnectResumesSessionAndReplaysCompletedTurnWithoutRunningPipelineTwice() throws InterruptedException {
        AtomicInteger asrCalls = new AtomicInteger();
        VoiceGatewayHandler h = newHandler((pcm, ctx) -> {
            asrCalls.incrementAndGet();
            return "导航到公司";
        }, llm("已规划路线"), ttsOk());

        StubSession first = open(h);
        String sid = handshake(h, first);
        h.handleMessage(first, new TextMessage(audioStartWithUtteranceId(sid, "seg-retry", "utt-retry")));
        h.handleMessage(first, new BinaryMessage(new byte[]{1}));
        h.handleMessage(first, new TextMessage(audioEnd(sid)));
        awaitSent(first, 5);

        StubSession second = open(h);
        h.handleMessage(second, new TextMessage(helloWithSessionId(sid)));
        assertEquals(sid, parse(second.sent.get(0)).get("payload").get("sessionId").asText());
        h.handleMessage(second, new TextMessage(audioStartWithUtteranceId(sid, "seg-retry", "utt-retry")));
        h.handleMessage(second, new BinaryMessage(new byte[]{1}));
        h.handleMessage(second, new TextMessage(audioEnd(sid)));
        awaitSent(second, 2); // ready + cached reply（不再重复 ASR/LLM/仲裁）

        assertEquals(1, asrCalls.get());
        JsonNode replay = parse(second.sent.get(1));
        assertEquals("reply", replay.get("type").asText());
        assertEquals("seg-retry", replay.get("payload").get("segmentId").asText());
    }

    @Test
    void textReplyCarriesTextAndSpeakTextOmitsIntent() throws InterruptedException {
        // LLM 文本回复（闲聊）：kind=text 且 text 与 speakText 同带（端侧 parseReply 强读 text）
        VoiceGatewayHandler h = newHandler(asr("x"), llm("LLM回答"), ttsOk());
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStart(sid)));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));
        awaitSent(s, 3);

        JsonNode reply = parse(s.sent.get(2));
        assertEquals("reply", reply.get("type").asText());
        JsonNode p = reply.get("payload");
        assertEquals("text", p.get("kind").asText());
        assertEquals("LLM回答", p.get("text").asText(), "kind=text 必须携带 text 字段");
        assertEquals("LLM回答", p.get("speakText").asText());
        assertFalse(p.has("intent"), "intent 为 null 时省略字段，不发送 null");
        assertFalse(p.has("segmentId"), "audio_start 未携带 segmentId 时 reply 不得发送该字段");
    }

    // ---------- 离线命令命中 ----------

    @Test
    void offlineHitEmitsOfflineWonActionReply() throws InterruptedException {
        // 离线识别命中（"打开空调"）→ offline_won：decision 先行，reply=action（intent + speakText）
        VoiceGatewayHandler h = newHandler(asr("x"), llm("LLM"), ttsOk(), hitOffline("打开空调"));
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStart(sid)));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));
        awaitSent(s, 3);
        JsonNode decision = parse(s.sent.get(1));
        assertEquals("offline_won", decision.get("payload").get("reason").asText());
        assertEquals("nlu-traditional", decision.get("payload").get("route").asText());

        JsonNode reply = parse(s.sent.get(2));
        JsonNode p = reply.get("payload");
        assertEquals("action", p.get("kind").asText());
        assertEquals("climate", p.get("intent").get("domain").asText());
        assertEquals("power_on", p.get("intent").get("intent").asText());
        assertEquals("好的，空调已打开", p.get("speakText").asText());
        assertEquals("打开空调", p.get("asrText").asText(), "离线胜出时 asrText = 离线原文");
    }

    @Test
    void s2sAudioReplyKeepsAudioTextAndIntentAfterCloudArbitration() throws InterruptedException {
        Intent intent = Intent.of("1.0", "climate", "power_on", Map.of(), 1.0,
                "qwen-omni.car_control", null);
        OnlineSpeechProvider s2s = online(Reply.ofAudio("audio/wav", WAV, "好的，空调已打开", intent));
        VoiceGatewayHandler h = new VoiceGatewayHandler(s2s, ttsOk(), noopOffline(), registry,
                SAFETY, ASR_FAIL_WAIT);
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStart(sid, "s2s-1")));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));
        awaitSent(s, 3);

        JsonNode payload = parse(s.sent.get(2)).get("payload");
        assertEquals("audio", payload.get("kind").asText());
        assertEquals("audio/wav", payload.get("mime").asText());
        assertArrayEquals(WAV, java.util.Base64.getDecoder().decode(payload.get("dataBase64").asText()));
        assertEquals("好的，空调已打开", payload.get("speakText").asText());
        assertEquals("power_on", payload.get("intent").get("intent").asText());
        assertEquals("s2s-1", payload.get("segmentId").asText());
    }

    @Test
    void s2sStreamsOnlyAfterCloudOfflineMiss() throws InterruptedException {
        byte[] chunk = {10, 11, 12, 13};
        OnlineSpeechProvider streaming = new OnlineSpeechProvider() {
            @Override
            public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm16k, com.autovoice.server.contracts.SessionContext context,
                    String utteranceId) {
                throw new AssertionError("streaming overload expected");
            }

            @Override
            public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm16k, com.autovoice.server.contracts.SessionContext context,
                    String utteranceId, OnlineAudioSink sink) {
                sink.onStart(24_000, 1, "pcm_s16le");
                sink.onChunk(chunk);
                sink.onComplete("Streaming reply", null, "What did I say?");
                return CompletableFuture.completedFuture(new OnlineSpeechResult(
                        Reply.ofAudio("audio/wav", WAV, "流式回答", null), ""));
            }

            @Override public String id() { return "stream-test"; }
        };
        VoiceGatewayHandler h = new VoiceGatewayHandler(streaming, ttsOk(), noopOffline(), registry,
                SAFETY, ASR_FAIL_WAIT);
        StubSession s = open(h);
        String sid = handshake(h, s);
        h.handleMessage(s, new TextMessage(audioStart(sid, "stream-1")));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));
        awaitSent(s, 5);

        assertEquals("audio_reply_start", parse(s.sent.get(1)).get("type").asText());
        assertTrue(s.sent.get(2) instanceof BinaryMessage);
        ByteBuffer streamed = ((BinaryMessage) s.sent.get(2)).getPayload();
        byte[] actual = new byte[streamed.remaining()];
        streamed.get(actual);
        assertArrayEquals(chunk, actual);
        JsonNode audioEnd = parse(s.sent.get(3));
        assertEquals("audio_reply_end", audioEnd.get("type").asText());
        assertEquals("What did I say?", audioEnd.get("payload").get("asrText").asText());
        assertEquals("decision", parse(s.sent.get(4)).get("type").asText());
        assertEquals(5, s.sent.size(), "流式轮不得再发送完整 reply");
    }

    @Test
    void cancelTurnUsesProcessingSnapshotAfterNextAudioStart() throws InterruptedException {
        // 新 audio_start 覆盖可变字段后 cancel_turn 仍命中 processing 轮快照：
        // 旧段被拦截零帧、挂起的 provider future 不被取消（拦截而非取消）
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<String> processingUtterance = new AtomicReference<>();
        AtomicBoolean futureCancelled = new AtomicBoolean();
        CompletableFuture<OnlineSpeechResult> pending = new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt) {
                futureCancelled.set(true);
                return super.cancel(interrupt);
            }
        };
        OnlineSpeechProvider cancellable = new OnlineSpeechProvider() {
            @Override public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, com.autovoice.server.contracts.SessionContext ctx, String uid) {
                throw new AssertionError("stream overload expected");
            }
            @Override public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, com.autovoice.server.contracts.SessionContext ctx, String uid,
                    OnlineAudioSink audio) {
                processingUtterance.set(uid);
                started.countDown();
                return pending;
            }
            @Override public String id() { return "cancel-test"; }
        };
        VoiceGatewayHandler h = new VoiceGatewayHandler(cancellable, ttsOk(), noopOffline(), registry,
                SAFETY, ASR_FAIL_WAIT);
        StubSession s = open(h);
        String sid = handshake(h, s);
        h.handleMessage(s, new TextMessage(audioStart(sid, "old-segment")));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));
        assertTrue(started.await(1, TimeUnit.SECONDS));

        // A new recording may overwrite mutable connection fields while the old turn is processing.
        h.handleMessage(s, new TextMessage(audioStart(sid, "new-segment")));
        h.handleMessage(s, new TextMessage(
                "{\"type\":\"cancel_turn\",\"payload\":{\"segmentId\":\"old-segment\"}}"));

        assertNotNull(processingUtterance.get(), "processing 快照应已记录在途 utteranceId");
        assertFalse(futureCancelled.get(), "cancel_turn 是拦截而非取消：provider future 不被取消");
        Thread.sleep(100); // 等 void 收敛 + 工作线程收尾
        assertNoErrorAndOnlyPreVoidPendingFor(s, "old-segment");
    }

    @Test
    void nonAirconOfflineHitEmitsPendingFrameBeforeLlmDecision() throws InterruptedException {
        // B5 端到端：离线命中"打开车窗"（非空调 → 按未命中处理）+ LLM 慢 → 仲裁先发 pending 占位帧
        // （带 segmentId，端侧对账用），随后 decision + reply 正常下发——占位不替代最终结果
        VoiceGatewayHandler h = newHandler(asr("x"), slowLlm(300, "已为您打开车窗"), ttsOk(),
                hitOffline("打开车窗"));
        StubSession s = open(h);
        h.handleMessage(s, new TextMessage(hello()));
        String sid = parse(s.sent.get(0)).get("payload").get("sessionId").asText();
        h.handleMessage(s, new TextMessage(audioStart(sid, "seg-1")));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));

        // pending 在 LLM 完成前同步下发（offline 已完成 + LLM 未 done → 立即发占位事件）
        awaitSent(s, 6); // ready + ASR 成立 + ASR 文本 + pending + decision + reply
        assertEquals("asr_turn_started", parse(s.sent.get(1)).get("type").asText());
        assertEquals("asr_partial", parse(s.sent.get(2)).get("type").asText());
        JsonNode pending = parse(s.sent.get(3));
        assertEquals("pending", pending.get("type").asText());
        assertEquals("seg-1", pending.get("payload").get("segmentId").asText(),
                "pending 应回显 audio_start 的 segmentId（端侧按话语对账）");
        assertEquals("正在处理，请稍候", pending.get("payload").get("text").asText());

        // 最终结果不受 pending 影响：decision 先行、reply 照常（空调离线命中路径无 pending，见上一用例）
        assertEquals("decision", parse(s.sent.get(4)).get("type").asText());
        JsonNode reply = parse(s.sent.get(5));
        assertEquals("reply", reply.get("type").asText());
        assertEquals("已为您打开车窗", reply.get("payload").get("speakText").asText());
    }

    // ---------- 独立 TTS 链路（tts_request/tts_response，TTS 解耦） ----------

    @Test
    void ttsRequestAfterHandshakeSendsTtsResponse() throws InterruptedException {
        VoiceGatewayHandler h = newHandler(asr("x"), llm("LLM"), ttsOk());
        StubSession s = open(h);
        handshake(h, s);

        h.handleMessage(s, new TextMessage(ttsRequest("好的，空调已打开", "tts-1")));

        awaitSent(s, 2);
        assertEquals(2, s.sent.size(), "ready + tts_response");
        JsonNode res = parse(s.sent.get(1));
        assertEquals("tts_response", res.get("type").asText());
        JsonNode p = res.get("payload");
        assertEquals("audio/wav", p.get("mime").asText());
        assertArrayEquals(WAV, Base64.getDecoder().decode(p.get("dataBase64").asText()));
        assertEquals("好的，空调已打开", p.get("text").asText(), "tts_response 应回显请求文本");
        assertEquals("tts-1", p.get("segmentId").asText(), "tts_response 应回显请求 segmentId");
    }

    @Test
    void ttsRequestBeforeHandshakeIgnored() {
        VoiceGatewayHandler h = newHandler(asr("x"), llm("LLM"), ttsOk());
        StubSession s = open(h);
        h.handleMessage(s, new TextMessage(ttsRequest("打开空调", null)));
        assertEquals(0, s.sent.size(), "未握手时 tts_request 不处理");
    }

    @Test
    void ttsFailureSendsTtsFailedErrorWithoutClosing() throws InterruptedException {
        VoiceGatewayHandler h = newHandler(asr("x"), llm("LLM"),
                (text, ctx) -> {
                    throw new RuntimeException("tts down");
                });
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(ttsRequest("打开空调", "tts-9")));
        awaitSent(s, 2);
        JsonNode error = parse(s.sent.get(1));
        assertEquals("error", error.get("type").asText());
        assertEquals("TTS_FAILED", error.get("payload").get("code").asText());
        assertEquals("tts-9", error.get("payload").get("segmentId").asText(),
                "TTS 错误应回显 tts_request 的 segmentId");

        // 连接仍可用：错误不关连接，后续语音轮次照常
        h.handleMessage(s, new TextMessage(audioStart(sid, "seg-2")));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));
        awaitSent(s, 6);
        assertEquals(6, s.sent.size(),
                "TTS 失败后连接仍可继续语音轮次（ready+error+ASR成立+文本+decision+reply）");
    }

    @Test
    void ttsRequestDoesNotBlockWebSocketMessageThread() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        VoiceGatewayHandler h = newHandler(asr("x"), llm("LLM"), (text, ctx) -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return Reply.ofAudio("audio/wav", WAV);
        });
        StubSession s = open(h);
        handshake(h, s);

        long started = System.nanoTime();
        h.handleMessage(s, new TextMessage(ttsRequest("打开空调", "tts-async")));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMs < 200, "tts_request 应只入队，不在 WebSocket 收包线程等待合成");
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertEquals(1, s.sent.size(), "合成未完成前只有 ready");
        release.countDown();
        awaitSent(s, 2);
        assertEquals("tts_response", parse(s.sent.get(1)).get("type").asText());
    }

    @Test
    void oversizedAudioIsRejectedBeforeAsr() {
        AtomicInteger asrCalls = new AtomicInteger();
        AsrProvider countingAsr = (pcm, ctx) -> {
            asrCalls.incrementAndGet();
            return "x";
        };
        VoiceGatewayHandler h = new VoiceGatewayHandler(
                new ClassicOnlineSpeechProvider(countingAsr, llm("LLM")), ttsOk(), noopOffline(), registry,
                SAFETY, ASR_FAIL_WAIT, 1500, false, Map.of(), 32, 3,
                NoopTelemetryRecorder.INSTANCE);
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStart(sid, "too-big")));
        h.handleMessage(s, new BinaryMessage(new byte[]{1, 2, 3, 4}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));

        assertEquals(2, s.sent.size(), "ready + AUDIO_TOO_LARGE");
        JsonNode error = parse(s.sent.get(1));
        assertEquals("AUDIO_TOO_LARGE", error.get("payload").get("code").asText());
        assertEquals("too-big", error.get("payload").get("segmentId").asText());
        assertEquals(0, asrCalls.get(), "超限段不得进入 ASR");
    }

    // ---------- 降级路径 ----------

    @Test
    void asrFailureSendsFallbackDecisionAndTextReply() throws InterruptedException {
        VoiceGatewayHandler h = newHandler((pcm, ctx) -> {
            throw new AsrException("asr down");
        }, llm("LLM"), ttsOk());
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStart(sid)));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));
        awaitSent(s, 3);
        JsonNode decision = parse(s.sent.get(1));
        assertEquals("asr_failed_fallback", decision.get("payload").get("reason").asText());

        JsonNode reply = parse(s.sent.get(2));
        JsonNode p = reply.get("payload");
        assertEquals("text", p.get("kind").asText());
        assertEquals("网络开小差了，请稍后再试", p.get("speakText").asText());
        assertFalse(p.has("intent"));
    }

    // ---------- 非法消息 → error ----------

    @Test
    void badJsonSendsError() {
        VoiceGatewayHandler h = newHandler(asr("x"), llm("LLM"), ttsOk());
        StubSession s = open(h);
        h.handleMessage(s, new TextMessage("not json"));
        JsonNode error = parse(s.sent.get(0));
        assertEquals("error", error.get("type").asText());
        assertEquals("INTERNAL", error.get("payload").get("code").asText());
    }

    @Test
    void errorEchoesSegmentIdWhenAudioStartCarriedOne() {
        // 连接内多轮往返时 error 需携带本话语的 segmentId，端侧才能准确对账（丢弃他轮的 error）
        VoiceGatewayHandler h = newHandler(asr("x"), llm("LLM"), ttsOk());
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStart(sid, "seg-1")));
        h.handleMessage(s, new TextMessage("not json"));

        assertEquals(2, s.sent.size(), "ready + error");
        JsonNode error = parse(s.sent.get(1));
        assertEquals("error", error.get("type").asText());
        assertEquals("INTERNAL", error.get("payload").get("code").asText());
        assertEquals("seg-1", error.get("payload").get("segmentId").asText(),
                "error 应回显当前话语的 segmentId");
        assertEquals(sid, error.get("payload").get("sessionId").asText());
    }

    @Test
    void helloWithoutSessionIdGetsReadyWithAdoptedSession() {
        // 协议意图：sessionId 服务端权威，客户端 hello 不预生成（gateway-client 按此发送）——
        // 缺 sessionId 是合法 hello，服务端采纳/生成 sessionId 并回 ready
        VoiceGatewayHandler h = newHandler(asr("x"), llm("LLM"), ttsOk());
        StubSession s = open(h);
        h.handleMessage(s, new TextMessage("{\"type\":\"hello\",\"payload\":{\"client\":\"autovoice-android\",\"protocolVersion\":\"1.0\"}}"));
        assertEquals(1, s.sent.size());
        JsonNode ready = parse(s.sent.get(0));
        assertEquals("ready", ready.get("type").asText());
        String sid = ready.get("payload").get("sessionId").asText();
        assertNotNull(sid);
        assertNotNull(registry.get(sid), "服务端应已创建会话");
    }

    @Test
    void helloMissingClientSendsBadHelloError() {
        // client 仍必填（协议 §3.1）：缺 client 的 hello 是非法握手
        VoiceGatewayHandler h = newHandler(asr("x"), llm("LLM"), ttsOk());
        StubSession s = open(h);
        h.handleMessage(s, new TextMessage("{\"type\":\"hello\",\"payload\":{\"protocolVersion\":\"1.0\"}}"));
        JsonNode error = parse(s.sent.get(0));
        assertEquals("error", error.get("type").asText());
        assertEquals("BAD_HELLO", error.get("payload").get("code").asText());
    }

    // ---------- 接入策略（M1）：鉴权 / 连接上限 ----------

    @Test
    void authEnabledWithValidTokenGetsReady() {
        VoiceGatewayHandler h = newAuthHandler(Map.of("demo-1", "tok"), 32);
        StubSession s = open(h);
        h.handleMessage(s, new TextMessage(helloWithAuth("demo-1", "tok")));

        assertEquals(1, s.sent.size());
        assertEquals("ready", parse(s.sent.get(0)).get("type").asText());
        assertNull(s.closeStatus, "鉴权通过不应关闭连接");
    }

    @Test
    void authEnabledWithWrongTokenRejectsWithBadAuth() {
        VoiceGatewayHandler h = newAuthHandler(Map.of("demo-1", "tok"), 32);
        StubSession s = open(h);
        h.handleMessage(s, new TextMessage(helloWithAuth("demo-1", "wrong")));

        assertEquals(1, s.sent.size());
        JsonNode error = parse(s.sent.get(0));
        assertEquals("error", error.get("type").asText());
        assertEquals("BAD_AUTH", error.get("payload").get("code").asText());
        assertNotNull(s.closeStatus, "鉴权失败应关闭连接");
        assertEquals(4001, s.closeStatus.getCode());
    }

    @Test
    void authEnabledRejectsLegacyHelloWithoutCredentials() {
        // 共享 fixture 的 hello（无 deviceId/authToken）在鉴权开启时应被拒——旧客户端需升级
        VoiceGatewayHandler h = newAuthHandler(Map.of("demo-1", "tok"), 32);
        StubSession s = open(h);
        h.handleMessage(s, new TextMessage(TestFixtures.HELLO_JSON));

        JsonNode error = parse(s.sent.get(0));
        assertEquals("BAD_AUTH", error.get("payload").get("code").asText());
        assertEquals(4001, s.closeStatus.getCode());
    }

    @Test
    void authDisabledAcceptsLegacyHello() {
        // 鉴权默认关：fixture 老 hello 裸连兼容（现有全部测试路径回归）
        VoiceGatewayHandler h = newHandler(asr("x"), llm("LLM"), ttsOk());
        StubSession s = open(h);
        h.handleMessage(s, new TextMessage(TestFixtures.HELLO_JSON));
        assertEquals("ready", parse(s.sent.get(0)).get("type").asText());
        assertNull(s.closeStatus);
    }

    @Test
    void connectionLimitRejectsAdditionalConnection() {
        VoiceGatewayHandler h = newAuthHandler(Map.of("demo-1", "tok"), 1);
        StubSession first = open(h);
        h.handleMessage(first, new TextMessage(helloWithAuth("demo-1", "tok")));
        assertEquals("ready", parse(first.sent.get(0)).get("type").asText(), "第一条连接正常握手");

        StubSession second = open(h);
        assertNotNull(second.closeStatus, "超限连接应被服务端关闭");
        assertEquals(4001, second.closeStatus.getCode());
        assertEquals("connection limit reached", second.closeStatus.getReason());
        assertTrue(second.sent.isEmpty(), "超限连接不应收到任何消息");
    }

    // ---------- audio_end 异步化（M2）：不占 WS 消息线程 + BUSY 拒收 ----------

    @Test
    void audioEndReturnsImmediatelyWhilePipelineRunsAsync() throws Exception {
        // 慢 pipeline（ASR 阻塞在工作线程）：audio_end 立即返回，WS 线程不被占死，
        // decision/reply 由工作线程在释放后下发（协议时序不变）
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        VoiceGatewayHandler h = newHandler((pcm, ctx) -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return "把空调调到二十四度";
        }, llmAction(), ttsOk());
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStart(sid, "seg-1")));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));

        assertTrue(entered.await(5, TimeUnit.SECONDS), "流水线应在工作线程启动");
        assertEquals(1, s.sent.size(), "处理中不应有 decision/reply（WS 线程未被占死，处理在后台）");

        release.countDown();
        awaitSent(s, 5);
        assertEquals("asr_turn_started", parse(s.sent.get(1)).get("type").asText());
        assertEquals("asr_partial", parse(s.sent.get(2)).get("type").asText());
        assertEquals("decision", parse(s.sent.get(3)).get("type").asText());
        assertEquals("reply", parse(s.sent.get(4)).get("type").asText());
        assertEquals("seg-1", parse(s.sent.get(4)).get("payload").get("segmentId").asText(),
                "异步回复应回显本段 segmentId");
    }

    @Test
    void binaryAudioFeedsStreamingAsrAndPublishesPartialBeforeAudioEnd() throws Exception {
        CountDownLatch appended = new CountDownLatch(1);
        StreamingAsrProvider streamingAsr = (ctx, sink) -> new StreamingAsrSession() {
            @Override public void append(byte[] pcm16k) {
                sink.onTurnEstablished();
                sink.onResult("今天天", false);
                appended.countDown();
            }
            @Override public CompletableFuture<String> finish() {
                sink.onResult("今天天气", true);
                return CompletableFuture.completedFuture("今天天气");
            }
            @Override public void cancel() { }
        };
        VoiceGatewayHandler h = newHandler(streamingAsr, llm("晴天"), ttsOk());
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStart(sid, "seg-stream")));
        h.handleMessage(s, new BinaryMessage(new byte[]{1, 2, 3, 4}));

        assertTrue(appended.await(1, TimeUnit.SECONDS));
        assertEquals(3, s.sent.size(), "audio_end 前应已分别下发 ASR 成立事件与 partial");
        assertEquals("asr_turn_started", parse(s.sent.get(1)).path("type").asText());
        JsonNode partial = parse(s.sent.get(2));
        assertEquals("asr_partial", partial.path("type").asText());
        assertEquals("今天天", partial.at("/payload/text").asText());
        assertFalse(partial.at("/payload/isFinal").asBoolean());

        h.handleMessage(s, new TextMessage(audioEnd(sid)));
        awaitSent(s, 6);
        assertEquals("asr_partial", parse(s.sent.get(3)).path("type").asText());
        assertTrue(parse(s.sent.get(3)).at("/payload/isFinal").asBoolean());
    }

    @Test
    void audioEndWhileProcessingSendsBusyError() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        VoiceGatewayHandler h = newHandler((pcm, ctx) -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return "x";
        }, llm("LLM"), ttsOk());
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStart(sid, "seg-1")));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));
        // 确定性：等工作线程越过 isCancelled 早退检查、进入 ASR 后再发下一段——
        // 否则 supersede 的 cancelledSegments 标记可能先于 worker 起动到达，
        // worker 在 :394 早退释放 processing，seg-2 不再 BUSY（合法但非本用例目标）
        assertTrue(entered.await(5, TimeUnit.SECONDS), "流水线应在工作线程启动并进入 ASR");

        // 上一段处理中再来一轮：audio_start 正常累积 PCM，audio_end → BUSY（同步下发）
        h.handleMessage(s, new TextMessage(audioStart(sid, "seg-2")));
        h.handleMessage(s, new BinaryMessage(new byte[]{2}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));

        JsonNode error = parse(s.sent.get(1));
        assertEquals("error", error.get("type").asText());
        assertEquals("BUSY", error.get("payload").get("code").asText());
        assertEquals("seg-2", error.get("payload").get("segmentId").asText(),
                "BUSY 应回显被拒段的 segmentId");

        release.countDown();
        // supersede 拦截：seg-1 已被新话语顶替，其 ASR/decision/reply 全部拦截不再下行
        Thread.sleep(300);
        assertEquals(2, s.sent.size(), "ready + BUSY（被顶替的 seg-1 零帧）");
    }

    // ---------- void（cancel_turn / superseded）：仲裁立即收敛 + processing 槽释放 ----------

    @Test
    void cancelTurnReleasesProcessingImmediately() throws InterruptedException {
        // 修复目标：cancel_turn 后仲裁立即收敛（拦截而非取消），processing 槽毫秒级释放，
        // 下一段 audio_end 不再 BUSY；被取消段零帧、挂起的 provider future 未被取消
        AtomicBoolean futureCancelled = new AtomicBoolean();
        CountDownLatch firstStarted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<OnlineSpeechResult> pending = new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt) {
                futureCancelled.set(true);
                return super.cancel(interrupt);
            }
        };
        OnlineSpeechProvider provider = new OnlineSpeechProvider() {
            @Override public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, com.autovoice.server.contracts.SessionContext ctx, String uid) {
                if (calls.incrementAndGet() == 1) {
                    firstStarted.countDown();
                    return pending; // 首段挂起直到被 void
                }
                return CompletableFuture.completedFuture(new OnlineSpeechResult(Reply.ofText("第二轮"), ""));
            }
            @Override public String id() { return "void-e2e"; }
        };
        VoiceGatewayHandler h = new VoiceGatewayHandler(provider, ttsOk(), noopOffline(), registry,
                SAFETY, ASR_FAIL_WAIT);
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStartWithUtteranceId(sid, "seg-1", "utt-1")));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

        long start = System.currentTimeMillis();
        h.handleMessage(s, new TextMessage(
                "{\"type\":\"cancel_turn\",\"payload\":{\"segmentId\":\"seg-1\"}}"));
        Thread.sleep(50); // 等工作线程 void 收尾（µs 级，留调度余量）

        h.handleMessage(s, new TextMessage(audioStartWithUtteranceId(sid, "seg-2", "utt-2")));
        h.handleMessage(s, new BinaryMessage(new byte[]{2}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));
        awaitReplyFor(s, "seg-2"); // seg-2 正常回复；被取消段零帧（无 BUSY）
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 800, "cancel 后下一段应立即处理（elapsed=" + elapsed + "ms），"
                + "不得等 SAFETY=" + SAFETY + " 兜底");
        assertFalse(futureCancelled.get(), "cancel_turn 是拦截而非取消：挂起的 provider future 不被取消");
        assertNoErrorAndOnlyPreVoidPendingFor(s, "seg-1");
        JsonNode decision = findFrame(s, "decision");
        assertEquals("utt-2", decision.get("payload").get("utteranceId").asText());
    }

    @Test
    void newAudioStartSupersedesInFlightTurn() throws InterruptedException {
        // 云端"最新会话拦截"（与端侧 isStale 同构）：seg-1 处理中新 audio_start（新 utteranceId）
        // 立即 void 在途轮——下一段 audio_end 无 BUSY、正常回复，seg-1 零帧
        CountDownLatch firstStarted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<OnlineSpeechResult> pending = new CompletableFuture<>();
        OnlineSpeechProvider provider = new OnlineSpeechProvider() {
            @Override public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, com.autovoice.server.contracts.SessionContext ctx, String uid) {
                if (calls.incrementAndGet() == 1) {
                    firstStarted.countDown();
                    return pending;
                }
                return CompletableFuture.completedFuture(new OnlineSpeechResult(Reply.ofText("第二轮"), ""));
            }
            @Override public String id() { return "supersede-e2e"; }
        };
        VoiceGatewayHandler h = new VoiceGatewayHandler(provider, ttsOk(), noopOffline(), registry,
                SAFETY, ASR_FAIL_WAIT);
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStartWithUtteranceId(sid, "seg-1", "utt-1")));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

        long start = System.currentTimeMillis();
        // 新一轮 vad start：新 utteranceId 上报 → 在途旧轮立即判定过期
        h.handleMessage(s, new TextMessage(audioStartWithUtteranceId(sid, "seg-2", "utt-2")));
        Thread.sleep(50); // 等工作线程 void 收尾（µs 级，留调度余量）
        h.handleMessage(s, new BinaryMessage(new byte[]{2}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));
        awaitReplyFor(s, "seg-2"); // seg-2 正常回复；被取代段零帧（无 BUSY）
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 800, "被取代的轮应立即让位（elapsed=" + elapsed + "ms），"
                + "不得等 SAFETY=" + SAFETY + " 兜底");
        assertNoErrorAndOnlyPreVoidPendingFor(s, "seg-1");
        JsonNode decision = findFrame(s, "decision");
        assertEquals("utt-2", decision.get("payload").get("utteranceId").asText());
    }

    @Test
    void audioStartWithSameUtteranceIdDoesNotSupersede() throws InterruptedException {
        // 同 utteranceId 重发（幂等重放）不触发最新会话拦截：处理槽仍被占用 → BUSY 保留
        CountDownLatch firstStarted = new CountDownLatch(1);
        CompletableFuture<OnlineSpeechResult> pending = new CompletableFuture<>();
        OnlineSpeechProvider provider = new OnlineSpeechProvider() {
            @Override public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, com.autovoice.server.contracts.SessionContext ctx, String uid) {
                firstStarted.countDown();
                return pending;
            }
            @Override public String id() { return "same-uid"; }
        };
        VoiceGatewayHandler h = new VoiceGatewayHandler(provider, ttsOk(), noopOffline(), registry,
                SAFETY, ASR_FAIL_WAIT);
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStartWithUtteranceId(sid, "seg-r1", "utt-r")));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

        h.handleMessage(s, new TextMessage(audioStartWithUtteranceId(sid, "seg-r2", "utt-r")));
        h.handleMessage(s, new BinaryMessage(new byte[]{2}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));

        awaitSent(s, 3); // ready + pending（LLM 未完成占位）+ BUSY
        JsonNode error = null;
        for (WebSocketMessage<?> m : s.sent) {
            JsonNode n = parse(m);
            if ("error".equals(n.get("type").asText())) {
                error = n;
            }
        }
        assertNotNull(error, "同 utteranceId 重发不触发顶替：处理槽仍占用，应收到 BUSY");
        assertEquals("BUSY", error.get("payload").get("code").asText());
        Thread.sleep(SAFETY + 200); // 等 SAFETY 兜底收敛在途轮，避免 worker 泄漏到其他测试
    }

    // ---------- helpers ----------

    private VoiceGatewayHandler newHandler(AsrProvider asr, LlmProvider llm, TtsProvider tts) {
        return newHandler(asr, llm, tts, noopOffline());
    }

    private VoiceGatewayHandler newHandler(AsrProvider asr, LlmProvider llm, TtsProvider tts,
                                           OfflineCommandService offline) {
        return new VoiceGatewayHandler(new ClassicOnlineSpeechProvider(asr, llm), tts, offline,
                registry, SAFETY, ASR_FAIL_WAIT);
    }

    /** 鉴权开启（M1）的 handler：设备表 + 连接上限可配。 */
    private VoiceGatewayHandler newAuthHandler(Map<String, String> devices, int maxConnections) {
        return new VoiceGatewayHandler(new ClassicOnlineSpeechProvider(asr("x"), llm("LLM")),
                ttsOk(), noopOffline(), registry,
                SAFETY, ASR_FAIL_WAIT, 1500, true, devices, maxConnections,
                NoopTelemetryRecorder.INSTANCE);
    }

    /** 带设备凭据的 hello（鉴权开启时的合法握手）。 */
    private static String helloWithAuth(String deviceId, String authToken) {
        return "{\"type\":\"hello\",\"payload\":{\"client\":\"autovoice-android\",\"protocolVersion\":\"1.1\",\"deviceId\":\""
                + deviceId + "\",\"authToken\":\"" + authToken + "\"}}";
    }

    private static StubSession open(VoiceGatewayHandler h) {
        StubSession s = new StubSession();
        h.afterConnectionEstablished(s);
        return s;
    }

    /** hello → 返回服务端采纳的 sessionId。 */
    private static String handshake(VoiceGatewayHandler h, StubSession s) {
        h.handleMessage(s, new TextMessage(hello()));
        return parse(s.sent.get(0)).get("payload").get("sessionId").asText();
    }

    private static AsrProvider asr(String text) {
        return (pcm, ctx) -> text;
    }

    private static LlmProvider llm(String text) {
        return (t, ctx) -> CompletableFuture.completedFuture(Reply.ofText(text));
    }

    private static OnlineSpeechProvider online(Reply reply) {
        return new OnlineSpeechProvider() {
            @Override
            public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm16k, com.autovoice.server.contracts.SessionContext context,
                    String utteranceId) {
                return CompletableFuture.completedFuture(new OnlineSpeechResult(reply, ""));
            }

            @Override
            public String id() {
                return "test-s2s";
            }
        };
    }

    /** LLM 慢响应（B5 pending 竞态用例用）：delayMs 后才返回文本回复，模拟工具循环推理耗时。 */
    private static LlmProvider slowLlm(long delayMs, String text) {
        return (t, ctx) -> CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Reply.ofText(text);
        });
    }

    /** LLM function calling 产出 action 回复（speakText 由服务端模板生成）。 */
    private static LlmProvider llmAction() {
        return (t, ctx) -> CompletableFuture.completedFuture(Reply.ofAction(
                Intent.of("1.0", "climate", "set_temperature", Map.of(), 0.95, "llm.car_control", null),
                "已为您执行空调指令"));
    }

    private static TtsProvider ttsOk() {
        return (text, ctx) -> Reply.ofAudio("audio/wav", WAV);
    }

    /** 直接复用共享 fixture（shared/fixtures/gateway-hello.json，sessionId=demo-1），禁止复制粘贴。 */
    private static String hello() {
        return TestFixtures.HELLO_JSON;
    }

    private static String helloWithSessionId(String sessionId) {
        return "{\"type\":\"hello\",\"payload\":{\"client\":\"autovoice-android\","
                + "\"protocolVersion\":\"1.1\",\"sessionId\":\"" + sessionId + "\"}}";
    }

    private static String audioStart(String sessionId) {
        return audioStart(sessionId, null);
    }

    /** segmentId（可选，protocol.md §3.2）：客户端每轮话语生成的唯一 ID，非空时随 audio_start 发送。 */
    private static String audioStart(String sessionId, String segmentId) {
        String seg = segmentId == null ? "" : ",\"segmentId\":\"" + segmentId + "\"";
        return "{\"type\":\"audio_start\",\"payload\":{\"sessionId\":\"" + sessionId
                + "\",\"sampleRate\":16000,\"channels\":1,\"encoding\":\"pcm_s16le\"" + seg + "}}";
    }

    /** audio_start 携带端侧 utteranceId（可选，telemetry 贯通）：服务端原样采纳进决策事件。 */
    private static String audioStartWithUtteranceId(String sessionId, String utteranceId) {
        return "{\"type\":\"audio_start\",\"payload\":{\"sessionId\":\"" + sessionId
                + "\",\"sampleRate\":16000,\"channels\":1,\"encoding\":\"pcm_s16le\""
                + ",\"utteranceId\":\"" + utteranceId + "\"}}";
    }


    private static String audioStartWithUtteranceId(String sessionId, String segmentId, String utteranceId) {
        return "{\"type\":\"audio_start\",\"payload\":{\"sessionId\":\"" + sessionId
                + "\",\"sampleRate\":16000,\"channels\":1,\"encoding\":\"pcm_s16le\""
                + ",\"segmentId\":\"" + segmentId + "\",\"utteranceId\":\"" + utteranceId + "\"}}";
    }

    private static String audioEnd(String sessionId) {
        return "{\"type\":\"audio_end\",\"payload\":{\"sessionId\":\"" + sessionId + "\",\"durationMs\":640}}";
    }

    /** tts_request（segmentId 可选，protocol.md §4.5）：text 必填。 */
    private static String ttsRequest(String text, String segmentId) {
        String seg = segmentId == null ? "" : ",\"segmentId\":\"" + segmentId + "\"";
        return "{\"type\":\"tts_request\",\"payload\":{\"text\":\"" + text + "\"" + seg + "}}";
    }

    private static JsonNode parse(WebSocketMessage<?> m) {
        try {
            return MAPPER.readTree(((TextMessage) m).getPayload());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** 等待工作线程（M2 异步化）把消息补齐再断言。 */
    private static void awaitSent(StubSession s, int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && s.sent.size() < n) {
            Thread.sleep(20);
        }
        assertEquals(n, s.sent.size(), "5s 内应收到 " + n + " 条下行消息（异步处理）");
    }

    private static JsonNode awaitType(StubSession s, String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        JsonNode frame;
        while (System.currentTimeMillis() < deadline) {
            frame = findTextFrame(s, type);
            if (frame != null) return frame;
            Thread.sleep(20);
        }
        throw new AssertionError("5s 内未收到 " + type);
    }

    private static JsonNode findTextFrame(StubSession s, String type) {
        synchronized (s.sent) {
            for (WebSocketMessage<?> message : s.sent) {
                if (!(message instanceof TextMessage)) continue;
                JsonNode frame = parse(message);
                if (type.equals(frame.path("type").asText())) return frame;
            }
        }
        return null;
    }

    /** 等待携带指定 segmentId 的 reply 帧（void 用例：不依赖消息总数，容忍前置 pending 占位）。 */
    private static JsonNode awaitReplyFor(StubSession s, String segmentId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode reply = findFrame(s, "reply");
            if (reply != null && segmentId.equals(reply.get("payload").path("segmentId").asText())) {
                return reply;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("5s 内未收到 segmentId=" + segmentId + " 的 reply");
    }

    /** 按类型查找第一条帧（线程安全快照遍历），无则返回 null。 */
    private static JsonNode findFrame(StubSession s, String type) {
        synchronized (s.sent) {
            for (WebSocketMessage<?> m : s.sent) {
                JsonNode n = parse(m);
                if (type.equals(n.get("type").asText())) {
                    return n;
                }
            }
        }
        return null;
    }

    /**
     * void 用例通用断言：无 error 帧；被拦截段（segmentId）至多出现 void 前已下发的
     * pending 占位（offline 空结果 + LLM 未完成的前置竞态），其余下行一律拦截。
     */
    private static void assertNoErrorAndOnlyPreVoidPendingFor(StubSession s, String segmentId) {
        synchronized (s.sent) {
            for (WebSocketMessage<?> m : s.sent) {
                JsonNode n = parse(m);
                assertFalse("error".equals(n.get("type").asText()),
                        "void 后不得出现 BUSY 等 error 帧: " + n);
                JsonNode p = n.get("payload");
                if (p != null && p.has("segmentId") && segmentId.equals(p.get("segmentId").asText())) {
                    assertEquals("pending", n.get("type").asText(),
                            "被拦截段仅允许 void 前已下发的 pending 占位: " + n);
                }
            }
        }
    }

    /** 最小 WebSocketSession stub：记录所有下行消息与最近一次服务端关闭（closeStatus）。
     *  下行可能来自工作线程（M2 异步化），sent 必须线程安全。 */
    private static final class StubSession implements WebSocketSession {
        final List<WebSocketMessage<?>> sent = Collections.synchronizedList(new ArrayList<>());
        CloseStatus closeStatus;

        @Override public String getId() { return "ws-1"; }
        @Override public URI getUri() { return URI.create("ws://localhost/ws"); }
        @Override public HttpHeaders getHandshakeHeaders() { return HttpHeaders.EMPTY; }
        @Override public Map<String, Object> getAttributes() { return Map.of(); }
        @Override public Principal getPrincipal() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int limit) { }
        @Override public int getTextMessageSizeLimit() { return 0; }
        @Override public void setBinaryMessageSizeLimit(int limit) { }
        @Override public int getBinaryMessageSizeLimit() { return 0; }
        @Override public List<WebSocketExtension> getExtensions() { return List.of(); }
        @Override public boolean isOpen() { return true; }
        @Override public void sendMessage(WebSocketMessage<?> message) throws IOException { sent.add(message); }
        @Override public void close() throws IOException { close(CloseStatus.NORMAL); }
        @Override public void close(CloseStatus status) throws IOException { this.closeStatus = status; }
    }
}

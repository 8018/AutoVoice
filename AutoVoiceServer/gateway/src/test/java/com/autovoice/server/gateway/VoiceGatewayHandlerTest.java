package com.autovoice.server.gateway;

import com.autovoice.server.contracts.AsrException;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.TtsProvider;
import com.autovoice.server.offlinecommand.NoopOfflineCommandProvider;
import com.autovoice.server.offlinecommand.OfflineCommandService;
import com.autovoice.server.session.SessionRegistry;
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
import java.security.Principal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 处理器接线测试：手写 WebSocketSession stub 捕获下行消息，验证
 * 握手回调、二进制帧累积、decision→reply 下发顺序与下行 kind 收敛（TTS 解耦：
 * reply 只携带语义，不再有 audio kind）。
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
        // 会话已登记（demo-1 不存在 → 新建）
        assertNotNull(registry.get(p.get("sessionId").asText()));
    }

    // ---------- 接线：完整段 → decision 先行 + reply(action，无音频) ----------

    @Test
    void fullSegmentAccumulatesPcmAndEmitsDecisionBeforeActionReply() {
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

        assertEquals(3, s.sent.size(), "ready + decision + reply");

        // 顺序：decision（协议 §5 第 7 步）先于 reply（第 8 步）
        JsonNode decision = parse(s.sent.get(1));
        assertEquals("decision", decision.get("type").asText());
        assertEquals("llm_reply", decision.get("payload").get("reason").asText());
        assertEquals("cloud", decision.get("payload").get("arbiter").asText());

        // TTS 解耦：reply 只携带语义（action + speakText），无 mime/dataBase64
        JsonNode reply = parse(s.sent.get(2));
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
    void textReplyCarriesTextAndSpeakTextOmitsIntent() {
        // LLM 文本回复（闲聊）：kind=text 且 text 与 speakText 同带（端侧 parseReply 强读 text）
        VoiceGatewayHandler h = newHandler(asr("x"), llm("LLM回答"), ttsOk());
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStart(sid)));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));

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
    void offlineHitEmitsOfflineWonActionReply() {
        // 离线识别命中（"打开空调"）→ offline_won：decision 先行，reply=action（intent + speakText）
        VoiceGatewayHandler h = newHandler(asr("x"), llm("LLM"), ttsOk(), hitOffline("打开空调"));
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStart(sid)));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));

        assertEquals(3, s.sent.size());
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

    // ---------- 独立 TTS 链路（tts_request/tts_response，TTS 解耦） ----------

    @Test
    void ttsRequestAfterHandshakeSendsTtsResponse() {
        VoiceGatewayHandler h = newHandler(asr("x"), llm("LLM"), ttsOk());
        StubSession s = open(h);
        handshake(h, s);

        h.handleMessage(s, new TextMessage(ttsRequest("好的，空调已打开", "tts-1")));

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
    void ttsFailureSendsTtsFailedErrorWithoutClosing() {
        VoiceGatewayHandler h = newHandler(asr("x"), llm("LLM"),
                (text, ctx) -> {
                    throw new RuntimeException("tts down");
                });
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(ttsRequest("打开空调", "tts-9")));
        JsonNode error = parse(s.sent.get(1));
        assertEquals("error", error.get("type").asText());
        assertEquals("TTS_FAILED", error.get("payload").get("code").asText());
        assertEquals("tts-9", error.get("payload").get("segmentId").asText(),
                "TTS 错误应回显 tts_request 的 segmentId");

        // 连接仍可用：错误不关连接，后续语音轮次照常
        h.handleMessage(s, new TextMessage(audioStart(sid, "seg-2")));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));
        assertEquals(4, s.sent.size(), "TTS 失败后连接仍可继续语音轮次（ready+error+decision+reply）");
    }

    // ---------- 降级路径 ----------

    @Test
    void asrFailureSendsFallbackDecisionAndTextReply() {
        VoiceGatewayHandler h = newHandler((pcm, ctx) -> {
            throw new AsrException("asr down");
        }, llm("LLM"), ttsOk());
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStart(sid)));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));

        assertEquals(3, s.sent.size());
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

    // ---------- helpers ----------

    private VoiceGatewayHandler newHandler(AsrProvider asr, LlmProvider llm, TtsProvider tts) {
        return newHandler(asr, llm, tts, noopOffline());
    }

    private VoiceGatewayHandler newHandler(AsrProvider asr, LlmProvider llm, TtsProvider tts,
                                           OfflineCommandService offline) {
        return new VoiceGatewayHandler(asr, llm, tts, offline, registry, SAFETY, ASR_FAIL_WAIT);
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

    private static String audioStart(String sessionId) {
        return audioStart(sessionId, null);
    }

    /** segmentId（可选，protocol.md §3.2）：客户端每轮话语生成的唯一 ID，非空时随 audio_start 发送。 */
    private static String audioStart(String sessionId, String segmentId) {
        String seg = segmentId == null ? "" : ",\"segmentId\":\"" + segmentId + "\"";
        return "{\"type\":\"audio_start\",\"payload\":{\"sessionId\":\"" + sessionId
                + "\",\"sampleRate\":16000,\"channels\":1,\"encoding\":\"pcm_s16le\"" + seg + "}}";
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

    /** 最小 WebSocketSession stub：记录所有下行消息。 */
    private static final class StubSession implements WebSocketSession {
        final List<WebSocketMessage<?>> sent = new ArrayList<>();

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
        @Override public void close() throws IOException { }
        @Override public void close(CloseStatus status) throws IOException { }
    }
}

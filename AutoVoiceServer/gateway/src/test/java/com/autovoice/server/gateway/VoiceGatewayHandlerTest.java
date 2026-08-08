package com.autovoice.server.gateway;

import com.autovoice.server.contracts.AsrException;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.NluProvider;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SlotValue;
import com.autovoice.server.contracts.TtsProvider;
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
 * 握手回调、二进制帧累积、decision→reply 下发顺序与下行 kind 收敛。
 * 测试环境用极短仲裁参数（grace 100ms / safety 1s），fake providers 同步就绪。
 */
class VoiceGatewayHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] WAV = {0x52, 0x49, 0x46, 0x46};
    private static final long GRACE = 100, SAFETY = 1000;

    private final SessionRegistry registry = new SessionRegistry();

    // ---------- 接线：hello → ready ----------

    @Test
    void helloGetsReadyWithAdoptedSession() {
        VoiceGatewayHandler h = newHandler(asr("x"), nluOk(), llm("LLM"), ttsOk());
        StubSession s = open(h);
        h.handleMessage(s, new TextMessage(hello()));

        assertEquals(1, s.sent.size());
        JsonNode ready = parse(s.sent.get(0));
        assertEquals("ready", ready.get("type").asText());
        JsonNode p = ready.get("payload");
        assertTrue(p.has("sessionId"));
        assertEquals("zh-CN", p.get("language").asText());
        assertEquals("1.0", p.get("protocolVersion").asText());
        // 会话已登记（demo-1 不存在 → 新建）
        assertNotNull(registry.get(p.get("sessionId").asText()));
    }

    // ---------- 接线：完整段 → decision 先行 + reply(audio) ----------

    @Test
    void fullSegmentAccumulatesPcmAndEmitsDecisionBeforeAudioReply() {
        byte[][] asrReceived = new byte[1][];
        VoiceGatewayHandler h = newHandler((pcm, ctx) -> {
            asrReceived[0] = pcm;
            return "把空调调到二十四度";
        }, nluOk(), llm("LLM"), ttsOk());
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
        assertEquals("nlu_first", decision.get("payload").get("reason").asText());
        assertEquals("cloud", decision.get("payload").get("arbiter").asText());

        JsonNode reply = parse(s.sent.get(2));
        assertEquals("reply", reply.get("type").asText());
        JsonNode p = reply.get("payload");
        assertEquals("audio", p.get("kind").asText(), "下行恒 kind=audio");
        assertEquals("audio/wav", p.get("mime").asText());
        assertArrayEquals(WAV, Base64.getDecoder().decode(p.get("dataBase64").asText()));
        assertEquals("已为您执行空调指令", p.get("speakText").asText());
        assertEquals("set_temperature", p.get("intent").get("intent").asText());
        assertEquals("seg-1", p.get("segmentId").asText(), "reply 应回显 audio_start 的 segmentId");

        // 二进制帧已按序累积为完整 PCM 交给 ASR
        assertArrayEquals(new byte[]{1, 2, 3, 4}, asrReceived[0]);
    }

    @Test
    void textReplySpeakTextNullIntentOmitsIntentField() {
        // LLM 文本回复（nlu 拒识 → LLM）：intent=null → 下行 audio 消息省略 intent 字段
        VoiceGatewayHandler h = newHandler(asr("x"),
                (t, ctx) -> CompletableFuture.completedFuture(Intent.unknown("test")),
                llm("LLM回答"), ttsOk());
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStart(sid)));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));

        JsonNode reply = parse(s.sent.get(2));
        assertEquals("reply", reply.get("type").asText());
        JsonNode p = reply.get("payload");
        assertEquals("audio", p.get("kind").asText());
        assertEquals("LLM回答", p.get("speakText").asText());
        assertFalse(p.has("intent"), "intent 为 null 时省略字段，不发送 null");
        assertFalse(p.has("segmentId"), "audio_start 未携带 segmentId 时 reply 不得发送该字段");
    }

    // ---------- 降级路径 ----------

    @Test
    void ttsFailureDegradesToTextReply() {
        VoiceGatewayHandler h = newHandler(asr("x"), nluOk(), llm("LLM"),
                (text, ctx) -> {
                    throw new RuntimeException("tts down");
                });
        StubSession s = open(h);
        String sid = handshake(h, s);

        h.handleMessage(s, new TextMessage(audioStart(sid)));
        h.handleMessage(s, new BinaryMessage(new byte[]{1}));
        h.handleMessage(s, new TextMessage(audioEnd(sid)));

        JsonNode reply = parse(s.sent.get(2));
        assertEquals("reply", reply.get("type").asText());
        JsonNode p = reply.get("payload");
        assertEquals("text", p.get("kind").asText(), "TTS 失败 → 降级 kind=text");
        assertEquals("已为您执行空调指令", p.get("speakText").asText());
        assertFalse(p.has("dataBase64"), "降级文本无音频");
        assertFalse(p.has("mime"), "降级文本无音频");
    }

    @Test
    void asrFailureSendsFallbackDecisionAndTextReply() {
        VoiceGatewayHandler h = newHandler((pcm, ctx) -> {
            throw new AsrException("asr down");
        }, nluOk(), llm("LLM"), ttsOk());
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
        VoiceGatewayHandler h = newHandler(asr("x"), nluOk(), llm("LLM"), ttsOk());
        StubSession s = open(h);
        h.handleMessage(s, new TextMessage("not json"));
        JsonNode error = parse(s.sent.get(0));
        assertEquals("error", error.get("type").asText());
        assertEquals("INTERNAL", error.get("payload").get("code").asText());
    }

    @Test
    void errorEchoesSegmentIdWhenAudioStartCarriedOne() {
        // 连接内多轮往返时 error 需携带本话语的 segmentId，端侧才能准确对账（丢弃他轮的 error）
        VoiceGatewayHandler h = newHandler(asr("x"), nluOk(), llm("LLM"), ttsOk());
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
    void malformedHelloSendsBadHelloError() {
        VoiceGatewayHandler h = newHandler(asr("x"), nluOk(), llm("LLM"), ttsOk());
        StubSession s = open(h);
        h.handleMessage(s, new TextMessage("{\"type\":\"hello\",\"payload\":{\"client\":\"autovoice-android\",\"protocolVersion\":\"1.0\"}}"));
        JsonNode error = parse(s.sent.get(0));
        assertEquals("error", error.get("type").asText());
        assertEquals("BAD_HELLO", error.get("payload").get("code").asText());
    }

    // ---------- helpers ----------

    private VoiceGatewayHandler newHandler(AsrProvider asr, NluProvider nlu, LlmProvider llm, TtsProvider tts) {
        return new VoiceGatewayHandler(asr, nlu, llm, tts, registry, GRACE, SAFETY);
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

    private static NluProvider nluOk() {
        return (t, ctx) -> CompletableFuture.completedFuture(
                Intent.of("1.0", "climate", "set_temperature",
                        Map.of("temperature", SlotValue.number(24)), 0.95, "test", null));
    }

    private static LlmProvider llm(String text) {
        return (t, ctx) -> CompletableFuture.completedFuture(Reply.ofText(text));
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

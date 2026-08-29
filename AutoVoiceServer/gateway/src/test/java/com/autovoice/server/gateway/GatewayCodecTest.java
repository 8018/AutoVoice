package com.autovoice.server.gateway;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.SlotValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------- decode：合法消息 ----------

    @Test
    void decodesValidHello() {
        // 直接复用共享 fixture（shared/fixtures/gateway-hello.json），禁止复制粘贴
        Map<String, Object> msg = GatewayCodec.decode(TestFixtures.HELLO_JSON);
        assertEquals("hello", msg.get("type"));
        Map<?, ?> payload = (Map<?, ?>) msg.get("payload");
        assertEquals("demo-1", payload.get("sessionId"));
        assertEquals("autovoice-android", payload.get("client"));
    }

    @Test
    void decodesValidAudioStart() {
        Map<String, Object> msg = GatewayCodec.decode("""
                {"type":"audio_start","payload":{"sessionId":"demo-1","sampleRate":16000,"channels":1,"encoding":"pcm_s16le"}}
                """);
        assertEquals("audio_start", msg.get("type"));
        Map<?, ?> payload = (Map<?, ?>) msg.get("payload");
        assertEquals(16000, payload.get("sampleRate"));
    }

    @Test
    void decodesAudioStartCarryingSegmentId() {
        // segmentId（可选）：客户端每轮话语生成的唯一 ID，服务端在 reply/error 中原样回显
        Map<String, Object> msg = GatewayCodec.decode("""
                {"type":"audio_start","payload":{"sessionId":"demo-1","sampleRate":16000,"channels":1,"encoding":"pcm_s16le","segmentId":"seg-1"}}
                """);
        assertEquals("seg-1", ((Map<?, ?>) msg.get("payload")).get("segmentId"));
    }

    @Test
    void audioStartCarriesOptionalUtteranceId() {
        // utteranceId（可选，telemetry 贯通）：客户端每轮话语生成的唯一 ID，服务端沿决策事件复用
        Map<String, Object> msg = GatewayCodec.decode("""
                {"type":"audio_start","payload":{"sessionId":"s1","sampleRate":16000,"channels":1,"encoding":"pcm_s16le","utteranceId":"utt-1"}}
                """);
        assertEquals("utt-1", ((Map<?, ?>) msg.get("payload")).get("utteranceId"));
    }

    @Test
    void ttsRequestCarriesOptionalUtteranceId() {
        // tts_request 同样可携带 utteranceId（可选字段，不属必需）
        Map<String, Object> msg = GatewayCodec.decode("""
                {"type":"tts_request","payload":{"text":"打开空调","utteranceId":"utt-2"}}
                """);
        assertEquals("utt-2", ((Map<?, ?>) msg.get("payload")).get("utteranceId"));
    }

    @Test
    void decodesValidAudioEnd() {
        Map<String, Object> msg = GatewayCodec.decode("""
                {"type":"audio_end","payload":{"sessionId":"demo-1","durationMs":2340}}
                """);
        assertEquals("audio_end", msg.get("type"));
        assertEquals(2340, ((Map<?, ?>) msg.get("payload")).get("durationMs"));
    }

    @Test
    void decodesValidReplyKinds() {
        GatewayCodec.decode("{\"type\":\"reply\",\"payload\":{\"kind\":\"text\",\"text\":\"hi\",\"speakText\":\"hi\"}}");
        GatewayCodec.decode("{\"type\":\"reply\",\"payload\":{\"kind\":\"audio\",\"mime\":\"audio/wav\",\"dataBase64\":\"AAAA\",\"speakText\":\"hi\"}}");
        GatewayCodec.decode("{\"type\":\"reply\",\"payload\":{\"kind\":\"action\",\"intent\":{\"schemaVersion\":\"1.0\",\"domain\":\"climate\",\"intent\":\"set_temperature\",\"slots\":{},\"confidence\":0.9},\"speakText\":\"hi\"}}");
    }

    @Test
    void decodesValidTtsRequestAndResponse() {
        // TTS 解耦（协议 v1.1 §4.5）：tts_request → tts_response，独立于音频话语链路
        Map<String, Object> req = GatewayCodec.decode(
                "{\"type\":\"tts_request\",\"payload\":{\"text\":\"好的，空调已打开\",\"segmentId\":\"tts-1\"}}");
        assertEquals("tts_request", req.get("type"));
        Map<?, ?> rp = (Map<?, ?>) req.get("payload");
        assertEquals("好的，空调已打开", rp.get("text"));

        Map<String, Object> res = GatewayCodec.decode(
                "{\"type\":\"tts_response\",\"payload\":{\"mime\":\"audio/wav\",\"dataBase64\":\"AAAA\",\"text\":\"好的，空调已打开\"}}");
        assertEquals("tts_response", res.get("type"));
        assertEquals("AAAA", ((Map<?, ?>) res.get("payload")).get("dataBase64"));
    }

    @Test
    void decodesHelloWithOptionalAuthFields() {
        // M1 多设备加固：hello 可选携带 deviceId/authToken（协议必需字段不变，鉴权在 handler 按配置校验）
        Map<String, Object> msg = GatewayCodec.decode("""
                {"type":"hello","payload":{"client":"autovoice-android","protocolVersion":"1.1","deviceId":"demo-1","authToken":"tok"}}
                """);
        Map<?, ?> payload = (Map<?, ?>) msg.get("payload");
        assertEquals("demo-1", payload.get("deviceId"));
        assertEquals("tok", payload.get("authToken"));
    }

    @Test
    void decodeIsLenientOnUnknownFields() {
        // 宽松解析：extra 字段透传，不拒绝
        Map<String, Object> msg = GatewayCodec.decode("""
                {"type":"hello","payload":{"client":"autovoice-android","protocolVersion":"1.0","sessionId":"demo-1","extra":"x"}}
                """);
        assertTrue(((Map<?, ?>) msg.get("payload")).containsKey("extra"));
    }

    // ---------- decode：非法消息 ----------

    @Test
    void rejectsBadJson() {
        assertThrows(IllegalArgumentException.class, () -> GatewayCodec.decode("not json"));
        assertThrows(IllegalArgumentException.class, () -> GatewayCodec.decode(""));
    }

    @Test
    void rejectsMissingType() {
        assertThrows(IllegalArgumentException.class, () -> GatewayCodec.decode("{\"payload\":{}}"));
    }

    @Test
    void rejectsUnknownType() {
        assertThrows(IllegalArgumentException.class,
                () -> GatewayCodec.decode("{\"type\":\"bogus\",\"payload\":{}}"));
    }

    @Test
    void rejectsUnknownReplyKind() {
        assertThrows(IllegalArgumentException.class,
                () -> GatewayCodec.decode("{\"type\":\"reply\",\"payload\":{\"kind\":\"bogus\"}}"));
    }

    @Test
    void rejectsMissingRequiredFieldsPerKind() {
        // audio_start 缺 sampleRate（protocol.md §3.2 字段必需）
        assertThrows(IllegalArgumentException.class,
                () -> GatewayCodec.decode("{\"type\":\"audio_start\",\"payload\":{\"sessionId\":\"demo-1\",\"channels\":1,\"encoding\":\"pcm_s16le\"}}"));
        // reply/audio 缺 dataBase64（schema 必需）
        assertThrows(IllegalArgumentException.class,
                () -> GatewayCodec.decode("{\"type\":\"reply\",\"payload\":{\"kind\":\"audio\",\"mime\":\"audio/wav\"}}"));
        // hello 缺 client（protocol.md §3.1 字段必需；sessionId 可选、服务端采纳，不算缺）
        assertThrows(IllegalArgumentException.class,
                () -> GatewayCodec.decode("{\"type\":\"hello\",\"payload\":{\"protocolVersion\":\"1.0\"}}"));
        // tts_request 缺 text（protocol.md §4.5 字段必需）
        assertThrows(IllegalArgumentException.class,
                () -> GatewayCodec.decode("{\"type\":\"tts_request\",\"payload\":{}}"));
        // tts_response 缺 dataBase64（schema 必需）
        assertThrows(IllegalArgumentException.class,
                () -> GatewayCodec.decode("{\"type\":\"tts_response\",\"payload\":{\"mime\":\"audio/wav\"}}"));
    }

    // ---------- encode ----------

    @Test
    void encodeReplyAudioOmitsNullsAndFiltersUnknownFields() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "audio");
        payload.put("mime", "audio/wav");
        payload.put("dataBase64", "AAAA");
        payload.put("speakText", "已为您把空调调到24度");
        payload.put("intent", null);   // null → 省略，不发送 null
        payload.put("evil", "x");      // 未知字段 → 绝不透传

        String json = GatewayCodec.encode("reply", payload);
        JsonNode root = read(json);
        assertEquals("reply", root.get("type").asText());
        JsonNode p = root.get("payload");
        assertEquals("audio", p.get("kind").asText());
        assertEquals("audio/wav", p.get("mime").asText());
        assertEquals("AAAA", p.get("dataBase64").asText());
        assertEquals("已为您把空调调到24度", p.get("speakText").asText());
        assertFalse(p.has("intent"), "intent 为 null 时必须省略字段");
        assertFalse(p.has("evil"), "未知字段绝不透传");
        assertEquals(4, p.size());
    }

    @Test
    void encodeReplyAudioCarriesIntentObject() {
        Intent intent = Intent.of("1.0", "climate", "set_temperature",
                Map.of("temperature", SlotValue.number(24)), 0.95, "test", null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "audio");
        payload.put("mime", "audio/wav");
        payload.put("dataBase64", "AAAA");
        payload.put("speakText", "hi");
        payload.put("intent", intent);

        JsonNode p = read(GatewayCodec.encode("reply", payload)).get("payload");
        assertEquals("set_temperature", p.get("intent").get("intent").asText());
        assertEquals("1.0", p.get("intent").get("schemaVersion").asText());
        assertEquals("climate", p.get("intent").get("domain").asText());
    }

    @Test
    void encodeReplyAndErrorEchoSegmentIdWhenPresentAndOmitWhenAbsent() {
        // reply：audio_start 携带 segmentId 时原样回显；未携带时省略（可选字段）
        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("kind", "audio");
        audio.put("mime", "audio/wav");
        audio.put("dataBase64", "AAAA");
        audio.put("speakText", "已为您把空调调到24度");
        audio.put("segmentId", "seg-1");
        JsonNode p = read(GatewayCodec.encode("reply", audio)).get("payload");
        assertEquals("seg-1", p.get("segmentId").asText());

        Map<String, Object> audioNoSeg = new LinkedHashMap<>(audio);
        audioNoSeg.remove("segmentId");
        assertFalse(read(GatewayCodec.encode("reply", audioNoSeg)).get("payload").has("segmentId"),
                "无 segmentId 时 reply 不得发送该字段");

        // error：同样支持 segmentId 回显（连接内多轮往返时据此对账）
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("sessionId", "demo-1");
        err.put("code", "INTERNAL");
        err.put("message", "boom");
        err.put("segmentId", "seg-1");
        JsonNode ep = read(GatewayCodec.encode("error", err)).get("payload");
        assertEquals("seg-1", ep.get("segmentId").asText());
        assertEquals("demo-1", ep.get("sessionId").asText());
    }

    @Test
    void encodeReadyAndDecision() {
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("sessionId", "demo-1");
        ready.put("language", "zh-CN");
        ready.put("protocolVersion", "1.0");
        JsonNode p = read(GatewayCodec.encode("ready", ready)).get("payload");
        assertEquals("demo-1", p.get("sessionId").asText());

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("arbiter", "cloud");
        decision.put("route", "llm");
        decision.put("reason", "llm_reply");
        decision.put("utteranceId", "u-1");
        decision.put("timestampMs", 1723104000000L);
        decision.put("evil", 1);
        JsonNode dp = read(GatewayCodec.encode("decision", decision)).get("payload");
        assertEquals("llm_reply", dp.get("reason").asText());
        assertFalse(dp.has("evil"));
    }

    @Test
    void decodesValidPending() {
        // LLM 处理中占位（B5）：S→C 独立消息，两字段均可选，宽松解析
        Map<String, Object> msg = GatewayCodec.decode(TestFixtures.read("gateway-pending.json"));
        assertEquals("pending", msg.get("type"));
        Map<?, ?> payload = (Map<?, ?>) msg.get("payload");
        assertEquals("seg-1", payload.get("segmentId"));
        assertEquals("正在处理，请稍候", payload.get("text"));

        // 仅 segmentId（text 可选）
        Map<String, Object> bare = GatewayCodec.decode(
                "{\"type\":\"pending\",\"payload\":{\"segmentId\":\"seg-2\"}}");
        assertEquals("seg-2", ((Map<?, ?>) bare.get("payload")).get("segmentId"));
    }

    @Test
    void encodePendingOnlyOutputsWhitelistFields() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("segmentId", "seg-1");
        payload.put("text", "正在处理，请稍候");
        payload.put("bogus", "x"); // 未知字段 → 绝不透传

        JsonNode p = read(GatewayCodec.encode("pending", payload)).get("payload");
        assertEquals("seg-1", p.get("segmentId").asText());
        assertEquals("正在处理，请稍候", p.get("text").asText());
        assertFalse(p.has("bogus"));
        assertEquals(2, p.size());

        // segmentId 缺席时省略（可选字段，与 reply 一致）
        Map<String, Object> noSeg = new LinkedHashMap<>(payload);
        noSeg.remove("segmentId");
        assertFalse(read(GatewayCodec.encode("pending", noSeg)).get("payload").has("segmentId"));
    }

    @Test
    void encodeTtsResponseOmitsNullsAndFiltersUnknownFields() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mime", "audio/wav");
        payload.put("dataBase64", "AAAA");
        payload.put("text", "好的，空调已打开");
        payload.put("segmentId", "tts-1");
        payload.put("evil", "x"); // 未知字段 → 绝不透传

        JsonNode p = read(GatewayCodec.encode("tts_response", payload)).get("payload");
        assertEquals("audio/wav", p.get("mime").asText());
        assertEquals("AAAA", p.get("dataBase64").asText());
        assertEquals("好的，空调已打开", p.get("text").asText());
        assertEquals("tts-1", p.get("segmentId").asText());
        assertFalse(p.has("evil"));
        assertEquals(4, p.size());
    }

    @Test
    void encodeAudioStartAndTtsRequestAdmitOptionalUtteranceId() {
        // utteranceId 仅进 FIELD_WHITELIST（不进 REQUIRED_FIELDS）：encode 原样输出该可选字段
        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("sessionId", "s1");
        audio.put("sampleRate", 16000);
        audio.put("channels", 1);
        audio.put("encoding", "pcm_s16le");
        audio.put("utteranceId", "utt-1");
        audio.put("latitude", 30.2741);
        audio.put("longitude", 120.1551);
        JsonNode ap = read(GatewayCodec.encode("audio_start", audio)).get("payload");
        assertEquals("utt-1", ap.get("utteranceId").asText());
        assertEquals(30.2741, ap.get("latitude").asDouble(), 0.000001);
        assertEquals(120.1551, ap.get("longitude").asDouble(), 0.000001);

        Map<String, Object> tts = new LinkedHashMap<>();
        tts.put("text", "打开空调");
        tts.put("utteranceId", "utt-2");
        JsonNode tp = read(GatewayCodec.encode("tts_request", tts)).get("payload");
        assertEquals("utt-2", tp.get("utteranceId").asText());

        // 缺失时字段省略（可选字段语义，与 segmentId 一致）
        Map<String, Object> noUtt = new LinkedHashMap<>(audio);
        noUtt.remove("utteranceId");
        assertFalse(read(GatewayCodec.encode("audio_start", noUtt)).get("payload").has("utteranceId"));
    }

    @Test
    void realtimeChatControlFixturesDecodeAndChatFlagSurvivesEncoding() {
        assertEquals("chat_start", GatewayCodec.decode(
                TestFixtures.read("gateway-chat-start.json")).get("type"));
        assertEquals("chat_finish", GatewayCodec.decode(
                TestFixtures.read("gateway-chat-finish.json")).get("type"));
        assertEquals("chat_ready", GatewayCodec.decode(
                TestFixtures.read("gateway-chat-ready.json")).get("type"));
        assertEquals("chat_speech_started", GatewayCodec.decode(
                TestFixtures.read("gateway-chat-speech-started.json")).get("type"));

        JsonNode start = read(GatewayCodec.encode("audio_reply_start", Map.of(
                "segmentId", "chat-1", "mime", "audio/pcm", "sampleRate", 24000,
                "channels", 1, "encoding", "pcm_s16le", "chat", true))).get("payload");
        assertTrue(start.get("chat").asBoolean());
    }

    @Test
    void encodeRejectsUnknownType() {
        assertThrows(IllegalArgumentException.class, () -> GatewayCodec.encode("bogus", Map.of()));
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}

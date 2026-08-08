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
        // hello 缺 sessionId
        assertThrows(IllegalArgumentException.class,
                () -> GatewayCodec.decode("{\"type\":\"hello\",\"payload\":{\"client\":\"autovoice-android\",\"protocolVersion\":\"1.0\"}}"));
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
    void encodeReadyAndDecision() {
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("sessionId", "demo-1");
        ready.put("language", "zh-CN");
        ready.put("protocolVersion", "1.0");
        JsonNode p = read(GatewayCodec.encode("ready", ready)).get("payload");
        assertEquals("demo-1", p.get("sessionId").asText());

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("arbiter", "cloud");
        decision.put("route", "nlu-traditional");
        decision.put("reason", "nlu_first");
        decision.put("utteranceId", "u-1");
        decision.put("timestampMs", 1723104000000L);
        decision.put("evil", 1);
        JsonNode dp = read(GatewayCodec.encode("decision", decision)).get("payload");
        assertEquals("nlu_first", dp.get("reason").asText());
        assertFalse(dp.has("evil"));
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

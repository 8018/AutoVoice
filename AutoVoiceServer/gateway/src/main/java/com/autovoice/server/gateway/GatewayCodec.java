package com.autovoice.server.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 网关消息编解码器（shared/protocol.md §2-§4，字段定义以 gateway-messages.schema.json 为准）。
 *
 * <p>统一信封 {@code {"type": "...", "payload": {...}}}：</p>
 * <ul>
 *   <li>{@link #decode}：宽松解析（extra 字段透传），但校验信封与协议必需字段——
 *       {@code type} 必填且为已知消息类型；C→S 消息按 protocol.md §3 校验必需字段
 *       （hello → client/protocolVersion，sessionId 由服务端采纳；audio_start → sessionId/sampleRate/channels/encoding；
 *       audio_end → sessionId/durationMs）；reply 按 schema 校验 kind 及其分支必需字段。</li>
 *   <li>{@link #encode}：只输出本 kind 白名单字段，绝不透传未知字段；null 值字段省略不发送。</li>
 * </ul>
 *
 * <p>失败语义：非法输入抛 {@link IllegalArgumentException}，由调用方（VoiceGatewayHandler）
 * 转换为 error 消息下发。</p>
 */
public final class GatewayCodec {

    /** 全部合法消息类型（protocol.md §2 消息总览）。 */
    private static final Set<String> TYPES = Set.of(
            "hello", "audio_start", "audio_end", "ready", "decision", "asr_partial", "reply", "error", "bye",
            "tts_request", "tts_response");

    /** reply 消息的合法 kind。 */
    private static final Set<String> REPLY_KINDS = Set.of("text", "audio", "action");

    /** 每类消息 payload 允许输出的字段白名单（encode 只输出白名单字段）。 */
    private static final Map<String, Set<String>> FIELD_WHITELIST = Map.ofEntries(
            Map.entry("hello", Set.of("client", "protocolVersion", "sessionId", "deviceId", "authToken")),
            Map.entry("audio_start", Set.of("sessionId", "sampleRate", "channels", "encoding", "segmentId", "utteranceId")),
            Map.entry("audio_end", Set.of("sessionId", "durationMs")),
            Map.entry("ready", Set.of("sessionId", "language", "protocolVersion", "serverTime")),
            Map.entry("decision", Set.of("arbiter", "route", "reason", "utteranceId", "timestampMs")),
            Map.entry("asr_partial", Set.of("sessionId", "text", "isFinal")),
            Map.entry("reply", Set.of("kind", "text", "speakText", "mime", "dataBase64", "intent", "segmentId", "asrText")),
            Map.entry("error", Set.of("sessionId", "code", "message", "segmentId")),
            Map.entry("bye", Set.of("sessionId", "reason")),
            Map.entry("tts_request", Set.of("text", "segmentId", "utteranceId")),
            Map.entry("tts_response", Set.of("mime", "dataBase64", "text", "segmentId")));

    /** 按 protocol.md §3 校验的消息必需字段（hello 不含 sessionId：客户端不预生成，服务端采纳）。
     *  tts_response 虽是 S→C 消息，与 reply 一样按下行 schema 校验必需字段。 */
    private static final Map<String, List<String>> REQUIRED_FIELDS = Map.of(
            "hello", List.of("client", "protocolVersion"),
            "audio_start", List.of("sessionId", "sampleRate", "channels", "encoding"),
            "audio_end", List.of("sessionId", "durationMs"),
            "tts_request", List.of("text"),
            "tts_response", List.of("mime", "dataBase64"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GatewayCodec() {
    }

    /**
     * 解码一条 JSON 消息。
     *
     * @return {@code {"type": String, "payload": Map<String,Object>}}；payload 恒为 Map，extra 字段透传
     * @throws IllegalArgumentException JSON 非法、缺 type、未知 type、payload 非对象、
     *                                 或按 kind 校验的必需字段缺失
     */
    public static Map<String, Object> decode(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("gateway message is not valid json: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("gateway message must be a json object");
        }
        JsonNode typeNode = root.get("type");
        if (typeNode == null || !typeNode.isTextual() || typeNode.asText().isEmpty()) {
            throw new IllegalArgumentException("gateway message missing required 'type' field");
        }
        String type = typeNode.asText();
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("unknown gateway message type: " + type);
        }
        JsonNode payloadNode = root.get("payload");
        if (payloadNode == null || !payloadNode.isObject()) {
            throw new IllegalArgumentException("gateway message '" + type + "' requires an object payload");
        }
        for (String field : REQUIRED_FIELDS.getOrDefault(type, List.of())) {
            JsonNode v = payloadNode.get(field);
            if (v == null || v.isNull()) {
                throw new IllegalArgumentException("message '" + type + "' missing required field '" + field + "'");
            }
        }
        if ("reply".equals(type)) {
            validateReply(payloadNode);
        }
        Map<String, Object> payload = MAPPER.convertValue(payloadNode, new TypeReference<Map<String, Object>>() {
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("payload", payload);
        return out;
    }

    /** reply 按 schema 校验 kind 及其分支必需字段（audio → mime/dataBase64；action → intent/speakText）。 */
    private static void validateReply(JsonNode payload) {
        JsonNode kindNode = payload.get("kind");
        String kind = kindNode != null && kindNode.isTextual() ? kindNode.asText() : null;
        if (kind == null || !REPLY_KINDS.contains(kind)) {
            throw new IllegalArgumentException("reply message requires kind in {text, audio, action}, got: " + kind);
        }
        switch (kind) {
            case "audio" -> {
                requireField(payload, "mime");
                requireField(payload, "dataBase64");
            }
            case "action" -> {
                requireField(payload, "intent");
                requireField(payload, "speakText");
            }
            default -> {
                // text：schema 仅要求 kind
            }
        }
    }

    private static void requireField(JsonNode payload, String field) {
        JsonNode v = payload.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("reply message missing required field '" + field + "'");
        }
    }

    /**
     * 编码一条 JSON 消息：只输出本 kind 白名单字段（未知字段绝不透传），
     * null 值字段省略不发送（如 intent 为 null 时省略）。
     *
     * @param type    合法消息类型
     * @param payload payload 字段表（Map），值为 null 的条目被省略
     * @throws IllegalArgumentException type 未知，或 payload 值无法序列化
     */
    public static String encode(String type, Object payload) {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("unknown gateway message type: " + type);
        }
        Set<String> allowed = FIELD_WHITELIST.get(type);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", type);
        ObjectNode payloadNode = root.putObject("payload");
        if (payload instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || entry.getValue() == null) {
                    continue; // 非字符串键或 null 值：一律省略
                }
                if (!allowed.contains(key)) {
                    continue; // 未知字段：绝不透传
                }
                JsonNode valueNode = MAPPER.valueToTree(entry.getValue());
                if (valueNode.isNull()) {
                    continue; // null 字段省略，不发送 null
                }
                payloadNode.set(key, valueNode);
            }
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize gateway message '" + type + "': " + e.getMessage(), e);
        }
    }
}

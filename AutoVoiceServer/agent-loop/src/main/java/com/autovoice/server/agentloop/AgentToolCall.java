package com.autovoice.server.agentloop;

import java.util.Objects;

/** One model-requested function call. */
public record AgentToolCall(String id, String name, String argumentsJson) {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public AgentToolCall {
        id = id == null ? "" : id;
        name = Objects.requireNonNullElse(name, "");
        argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
    }

    public String cacheKey() {
        try {
            var value = JSON.readTree(argumentsJson);
            if (value == null || !value.isObject()) return null;
            return name + "\n" + normalize(value);
        } catch (Exception ignored) {
            return null; // Invalid/ambiguous arguments must never share a cache entry.
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode normalize(com.fasterxml.jackson.databind.JsonNode value) {
        if (value.isObject()) {
            var out = JSON.createObjectNode();
            java.util.List<String> fields = new java.util.ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.stream().sorted().forEach(key -> out.set(key, normalize(value.get(key))));
            return out;
        }
        if (value.isArray()) {
            var out = JSON.createArrayNode();
            value.forEach(item -> out.add(normalize(item)));
            return out; // Waypoint order is meaningful.
        }
        return value;
    }
}

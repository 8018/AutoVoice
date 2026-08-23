package com.autovoice.server.agentloop;

import java.util.Objects;

/** One model-requested function call. */
public record AgentToolCall(String id, String name, String argumentsJson) {
    public AgentToolCall {
        id = id == null ? "" : id;
        name = Objects.requireNonNullElse(name, "");
        argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
    }

    public String cacheKey() {
        return name + "\n" + argumentsJson;
    }
}

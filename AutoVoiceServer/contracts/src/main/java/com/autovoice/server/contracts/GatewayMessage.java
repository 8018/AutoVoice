package com.autovoice.server.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 统一消息信封 {@code {"type": "...", "payload": {...}}}（shared/protocol.md §2）。
 */
public record GatewayMessage(
        @JsonProperty("type") String type,
        @JsonProperty("payload") JsonNode payload) {

    @JsonCreator
    public GatewayMessage {
    }
}

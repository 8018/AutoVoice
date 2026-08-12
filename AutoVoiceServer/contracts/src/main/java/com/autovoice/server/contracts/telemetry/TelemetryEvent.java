package com.autovoice.server.contracts.telemetry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** 链路单阶段事件：stage + 时刻 + 级别 + 自由 payload。 */
public record TelemetryEvent(
        @JsonProperty("stage") String stage,
        @JsonProperty("tsMs") long tsMs,
        @JsonProperty("level") String level,
        @JsonProperty("payload") Map<String, Object> payload) {

    @JsonCreator
    public TelemetryEvent {
    }
}

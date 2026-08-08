package com.autovoice.server.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 决策日志事件（shared/protocol.md §6）：由谁仲裁（arbiter）、走哪条路线（route）、
 * 为什么（reason）、话语唯一 ID（utteranceId）与决策时刻（timestampMs）。
 */
public record DecisionEntry(
        @JsonProperty("arbiter") String arbiter,
        @JsonProperty("route") String route,
        @JsonProperty("reason") String reason,
        @JsonProperty("utteranceId") String utteranceId,
        @JsonProperty("timestampMs") long timestampMs) {

    @JsonCreator
    public DecisionEntry {
    }
}

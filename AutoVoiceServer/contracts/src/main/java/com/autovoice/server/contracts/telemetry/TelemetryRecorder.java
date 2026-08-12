package com.autovoice.server.contracts.telemetry;

import java.util.Map;

/** 链路事件记录器（插桩 SPI）：按 utteranceId 记录单阶段事件。实现可为存储/转发/Noop。 */
public interface TelemetryRecorder {

    void record(String utteranceId, TelemetryEvent event);

    default void record(String utteranceId, String stage, String level, Map<String, Object> payload) {
        record(utteranceId, new TelemetryEvent(stage, System.currentTimeMillis(), level, payload));
    }
}

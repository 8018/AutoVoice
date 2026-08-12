package com.autovoice.server.ttsgateway;

import com.autovoice.server.contracts.telemetry.TelemetryEvent;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用链路事件记录器：捕获 (utteranceId, event) 对，供插桩断言。
 */
final class RecordingRecorder implements TelemetryRecorder {

    final List<String> utteranceIds = new CopyOnWriteArrayList<>();
    final List<TelemetryEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(String utteranceId, TelemetryEvent event) {
        utteranceIds.add(utteranceId);
        events.add(event);
    }
}

package com.autovoice.server.contracts.telemetry;

/** 空实现：telemetry 未启用/测试时装配，record 不做事（零影响）。 */
public final class NoopTelemetryRecorder implements TelemetryRecorder {

    public static final NoopTelemetryRecorder INSTANCE = new NoopTelemetryRecorder();

    private NoopTelemetryRecorder() {
    }

    @Override
    public void record(String utteranceId, TelemetryEvent event) {
    }
}

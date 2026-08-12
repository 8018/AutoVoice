package com.autovoice.server.telemetry;

import com.autovoice.server.contracts.telemetry.TelemetryEvent;

import java.util.List;

/**
 * 单轮明细 JSON 形状 {@code {summary:{...}, events:[...]}}：summary 为 {@link RoundSummary}
 * 聚合视图，events 为全阶段时间线（E2E 与面板依赖此形状）。summary 字段的 delegate
 * 访问器仅 Java 侧方便用，不影响 Jackson 序列化（只序列化 record 组件）。
 */
public record RoundDetail(RoundSummary summary, List<TelemetryEvent> events) {

    public String utteranceId() {
        return summary.utteranceId();
    }

    public String deviceId() {
        return summary.deviceId();
    }

    public String source() {
        return summary.source();
    }

    public long startMs() {
        return summary.startMs();
    }

    public long endMs() {
        return summary.endMs();
    }

    public String localDecision() {
        return summary.localDecision();
    }

    public String cloudDecision() {
        return summary.cloudDecision();
    }

    public String finalDecision() {
        return summary.finalDecision();
    }

    public Boolean ttsCacheHit() {
        return summary.ttsCacheHit();
    }

    public String playbackResult() {
        return summary.playbackResult();
    }

    public String audioPath() {
        return summary.audioPath();
    }
}

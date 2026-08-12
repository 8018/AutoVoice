package com.autovoice.server.telemetry;

/**
 * 单轮聚合摘要（rounds 行）——/rounds 列表与 SSE stream 推送的形状；字段与 rounds 表
 * 聚合列一一对应。决策列（local/cloud/final）在 recordDeviceRound 时由事件推导
 * （对应 stage 末事件 payload 的 route），未推导到则为 null。
 */
public record RoundSummary(String utteranceId, String deviceId, String source,
                           long startMs, long endMs,
                           String localDecision, String cloudDecision, String finalDecision,
                           Boolean ttsCacheHit, String playbackResult, String audioPath) {
}

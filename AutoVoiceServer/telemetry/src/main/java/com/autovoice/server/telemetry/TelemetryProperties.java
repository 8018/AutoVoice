package com.autovoice.server.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code autovoice.telemetry.*} 配置（constructor binding）。dbPath/audioDir 空 → 相对路径
 * 默认（服务器部署由 env 覆盖，见 application.yml 占位符）；retentionDays < 1 → 7。
 */
@ConfigurationProperties(prefix = "autovoice.telemetry")
public record TelemetryProperties(boolean enabled, String dbPath, String audioDir, int retentionDays) {

    public TelemetryProperties {
        if (dbPath == null || dbPath.isBlank()) dbPath = "./telemetry.db";
        if (audioDir == null || audioDir.isBlank()) audioDir = "./telemetry-audio";
        if (retentionDays < 1) retentionDays = 7;
    }
}

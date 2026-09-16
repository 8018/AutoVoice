package com.autovoice.server.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * {@code autovoice.telemetry.*} 配置（constructor binding）。dbPath/audioDir 空 → 相对路径
 * 默认（服务器部署由 env 覆盖，见 application.yml 占位符）；retentionDays < 1 → 7。
 */
@ConfigurationProperties(prefix = "autovoice.telemetry")
public record TelemetryProperties(boolean enabled, String dbPath, String audioDir, int retentionDays,
                                  String accessToken, String adminToken,
                                  boolean audioPersistEnabled, long audioMaxBytes) {

    @ConstructorBinding
    public TelemetryProperties {
        if (dbPath == null || dbPath.isBlank()) dbPath = "./telemetry.db";
        if (audioDir == null || audioDir.isBlank()) audioDir = "./telemetry-audio";
        if (retentionDays < 1) retentionDays = 7;
        accessToken = accessToken == null ? "" : accessToken;
        adminToken = adminToken == null ? "" : adminToken;
        // D14b:原始音频默认不持久化(生产默认);audioMaxBytes<=0 → 1GB 上限
        if (audioMaxBytes <= 0) audioMaxBytes = 1L * 1024 * 1024 * 1024;
    }

    /** 兼容构造(audio 默认不持久化)。 */
    public TelemetryProperties(boolean enabled, String dbPath, String audioDir, int retentionDays,
                               String accessToken, String adminToken) {
        this(enabled, dbPath, audioDir, retentionDays, accessToken, adminToken, false,
                1L * 1024 * 1024 * 1024);
    }

    public TelemetryProperties(boolean enabled, String dbPath, String audioDir, int retentionDays) {
        this(enabled, dbPath, audioDir, retentionDays, "", "");
    }

    public TelemetryProperties(boolean enabled, String dbPath, String audioDir, int retentionDays,
                               String accessToken) {
        this(enabled, dbPath, audioDir, retentionDays, accessToken, "");
    }
}

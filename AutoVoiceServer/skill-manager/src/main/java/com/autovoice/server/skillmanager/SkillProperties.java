package com.autovoice.server.skillmanager;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * skill 平台配置。adminToken：管理界面口令；serviceToken：网关拉取/内部端点共用
 * （与网关 env SKILL_SERVICE_TOKEN 同值）；gatewayWebhookUrl：写操作后推送网关刷新。
 */
@ConfigurationProperties(prefix = "autovoice.skill-manager")
public record SkillProperties(String dbPath, String adminToken, String serviceToken,
                              String gatewayWebhookUrl, long mcpConnectTimeoutMs) {

    public SkillProperties {
        if (dbPath == null || dbPath.isBlank()) dbPath = "./skill-manager.db";
        if (adminToken == null) adminToken = "";
        if (serviceToken == null) serviceToken = "";
        if (gatewayWebhookUrl == null) gatewayWebhookUrl = "";
        if (mcpConnectTimeoutMs < 1) mcpConnectTimeoutMs = 5_000;
    }
}

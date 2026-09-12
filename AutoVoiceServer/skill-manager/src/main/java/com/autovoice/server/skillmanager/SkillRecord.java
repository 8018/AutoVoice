package com.autovoice.server.skillmanager;

/**
 * skill 表记录(与网关 SkillConfig 字段对齐)。
 *
 * <p>D14a:authValue 存放**秘密引用**而非明文——{@code env:NAME} 由部署方注入环境变量,
 * {@code file:/path} 读取受控文件(建议 0600、属主为服务账号);留空表示该 Skill 无需
 * 认证头。无前缀的历史明文值仍可读(兼容),但网关会记录迁移提示。</p>
 */
public record SkillRecord(String id, String name, String description, String scope, String mcpUrl,
                          String authHeader, String authValue, String toolsJson,
                          boolean enabled, long updatedAt) {

    public SkillRecord(String id, String name, String description, String mcpUrl,
                       String authHeader, String authValue, String toolsJson,
                       boolean enabled, long updatedAt) {
        this(id, name, description, "llm", mcpUrl, authHeader, authValue, toolsJson, enabled, updatedAt);
    }
}

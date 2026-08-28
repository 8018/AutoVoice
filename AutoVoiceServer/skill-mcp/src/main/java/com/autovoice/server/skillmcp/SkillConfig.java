package com.autovoice.server.skillmcp;

/**
 * 平台返回的启用 skill 配置（GET /api/skills?enabled=true 的响应元素）。
 * toolsJson 为勾选清单 JSON 文本：[{"name":"...","enabled":true},...]。
 */
public record SkillConfig(String id, String name, String description, String scope, String mcpUrl,
                          String authHeader, String authValue, String toolsJson,
                          boolean enabled, long updatedAt) {

    public SkillConfig(String id, String name, String description, String mcpUrl,
                       String authHeader, String authValue, String toolsJson,
                       boolean enabled, long updatedAt) {
        this(id, name, description, "llm", mcpUrl, authHeader, authValue, toolsJson, enabled, updatedAt);
    }

    public String normalizedScope() {
        return "chat".equals(scope) ? "chat" : "llm";
    }
}

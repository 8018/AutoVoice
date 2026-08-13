package com.autovoice.server.skillmanager;

/** API 响应体。authValue 按视图掩码（管理端 "****" / 网关明文）。 */
public record SkillResponse(String id, String name, String description, String mcpUrl,
                            String authHeader, String authValue, String toolsJson,
                            boolean enabled, long updatedAt) {
}

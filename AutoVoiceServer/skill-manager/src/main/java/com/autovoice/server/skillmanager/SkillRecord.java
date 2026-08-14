package com.autovoice.server.skillmanager;

/** skill 表记录（与网关 SkillConfig 字段对齐；平台侧 authValue 存明文）。 */
public record SkillRecord(String id, String name, String description, String mcpUrl,
                          String authHeader, String authValue, String toolsJson,
                          boolean enabled, long updatedAt) {
}

package com.autovoice.server.skillmanager;

/** 创建/更新请求体。authValue 可选：创建时缺省（null）落库为空字符串；更新时留空=保留旧值。 */
public record SkillRequest(String id, String name, String description, String mcpUrl,
                           String authHeader, String authValue, String toolsJson, Boolean enabled) {
}

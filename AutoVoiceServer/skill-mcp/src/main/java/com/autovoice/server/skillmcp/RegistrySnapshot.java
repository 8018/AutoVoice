package com.autovoice.server.skillmcp;

import java.util.Map;

/**
 * D11a 不可变运行时配置快照:工具连接、工具归属、导航门面与两个 system prompt
 * 属于同一版本,一次性原子发布——请求不会看到"旧 schema 配新连接"的混合版本。
 *
 * <p>版本号单调递增,可用于请求侧租约(取到快照后在该版本内执行)与遥测追溯。</p>
 */
public record RegistrySnapshot(
        long version,
        Map<String, McpToolSession> sessions,
        Map<String, McpToolSession> toolOwners,
        NavigationToolFacade navigationFacade,
        String systemPrompt,
        String chatSystemPrompt,
        long createdAtMs) {

    public RegistrySnapshot {
        sessions = sessions == null ? Map.of() : Map.copyOf(sessions);
        toolOwners = toolOwners == null ? Map.of() : Map.copyOf(toolOwners);
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        chatSystemPrompt = chatSystemPrompt == null ? "" : chatSystemPrompt;
    }

    /** 空快照(启动态/平台不可达时的初值)。 */
    public static RegistrySnapshot empty() {
        return new RegistrySnapshot(0, Map.of(), Map.of(), null, "", "", 0L);
    }

    /** 工具总数(遥测/日志用)。 */
    public int toolCount() {
        return sessions.values().stream().mapToInt(session -> session.tools().size()).sum();
    }
}

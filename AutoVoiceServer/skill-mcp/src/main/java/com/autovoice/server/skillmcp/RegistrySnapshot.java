package com.autovoice.server.skillmcp;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * D11a 不可变运行时配置快照:工具连接、工具归属、导航门面与两个 system prompt
 * 属于同一版本,一次性原子发布——请求不会看到"旧 schema 配新连接"的混合版本。
 *
 * <p>D11b 版本租约:{@link #acquire()} 持有期内,本版本的连接不得被退役
 * (旧连接延迟关闭);租约释放后才允许退役。版本号单调递增,可用于遥测追溯。</p>
 */
public final class RegistrySnapshot {

    private final long version;
    private final Map<String, McpToolSession> sessions;
    private final Map<String, McpToolSession> toolOwners;
    private final NavigationToolFacade navigationFacade;
    private final String systemPrompt;
    private final String chatSystemPrompt;
    private final long createdAtMs;
    private final AtomicInteger activeLeases = new AtomicInteger();

    public RegistrySnapshot(long version, Map<String, McpToolSession> sessions,
                            Map<String, McpToolSession> toolOwners,
                            NavigationToolFacade navigationFacade,
                            String systemPrompt, String chatSystemPrompt, long createdAtMs) {
        this.version = version;
        this.sessions = sessions == null ? Map.of() : Map.copyOf(sessions);
        this.toolOwners = toolOwners == null ? Map.of() : Map.copyOf(toolOwners);
        this.navigationFacade = navigationFacade;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.chatSystemPrompt = chatSystemPrompt == null ? "" : chatSystemPrompt;
        this.createdAtMs = createdAtMs;
    }

    /** 空快照(启动态/平台不可达时的初值)。 */
    public static RegistrySnapshot empty() {
        return new RegistrySnapshot(0, Map.of(), Map.of(), null, "", "", 0L);
    }

    public long version() {
        return version;
    }

    public Map<String, McpToolSession> sessions() {
        return sessions;
    }

    public Map<String, McpToolSession> toolOwners() {
        return toolOwners;
    }

    public NavigationToolFacade navigationFacade() {
        return navigationFacade;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public String chatSystemPrompt() {
        return chatSystemPrompt;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    /** 工具总数(遥测/日志用)。 */
    public int toolCount() {
        return sessions.values().stream().mapToInt(session -> session.tools().size()).sum();
    }

    // ---------- D11b 请求侧租约 ----------

    /**
     * 取一个版本租约:持有期内本快照不得被退役(旧连接延迟关闭);关闭幂等。
     */
    public AutoCloseable acquire() {
        activeLeases.incrementAndGet();
        return () -> activeLeases.updateAndGet(current -> Math.max(0, current - 1));
    }

    public int activeLeases() {
        return activeLeases.get();
    }

    /** 是否已无租约(可安全退役)。 */
    public boolean isIdle() {
        return activeLeases.get() == 0;
    }
}

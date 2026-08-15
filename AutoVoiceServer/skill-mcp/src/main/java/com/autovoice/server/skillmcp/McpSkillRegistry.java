package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * 启用的 skill 内存快照：启动异步首拉 + 定时兜底轮询 + webhook 触发重拉。
 * 平台不可达 → 保留上次成功快照；单 skill MCP 连接失败 → 跳过该 skill。
 * 会话（SegmentPipeline）零感知：每次直接用当前快照。
 */
public final class McpSkillRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(McpSkillRegistry.class);

    private final SkillPlatformClient client;
    private final ToolInjector injector;
    private final SystemPromptStore promptStore;
    private final long pollMs;
    private final long connectTimeoutMs;
    private final BiFunction<SkillConfig, Long, McpToolSession> sessionFactory;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "skill-registry");
        t.setDaemon(true);
        return t;
    });

    private volatile Map<String, McpToolSession> sessions = Map.of();
    /** 不可被外部 skill 覆盖的内置终局工具。 */
    private static final Set<String> RESERVED_TOOL_NAMES = Set.of("car_control", "navigate");
    /** 刷新时原子替换的唯一工具路由，避免调用期按 session 顺序选择同名工具。 */
    private volatile Map<String, McpToolSession> toolOwners = Map.of();
    private volatile long lastRefreshMs;

    public McpSkillRegistry(SkillPlatformClient client, ToolInjector injector,
                            SystemPromptStore promptStore, long pollMs, long connectTimeoutMs,
                            BiFunction<SkillConfig, Long, McpToolSession> sessionFactory) {
        this.client = client;
        this.injector = injector;
        this.promptStore = promptStore;
        this.pollMs = pollMs < 1 ? 600_000 : pollMs;
        this.connectTimeoutMs = connectTimeoutMs < 1 ? 5_000 : connectTimeoutMs;
        this.sessionFactory = sessionFactory;
    }

    /** 启动：异步首拉一次 + 按 pollMs 定时轮询（daemon 线程，不阻塞启动）。 */
    public void start() {
        scheduler.execute(this::refresh);
        scheduler.scheduleWithFixedDelay(this::refresh, pollMs, pollMs, TimeUnit.MILLISECONDS);
    }

    /** webhook 通知后立即异步重拉。 */
    public void refreshAsync() {
        scheduler.execute(this::refresh);
    }

    /** 同步重拉（start 的调度与测试都走它）。 */
    public synchronized void refresh() {
        try {
            refreshInternal();
        } catch (RuntimeException e) {
            // 顶层守卫：任何未预期异常都不能穿透到 scheduleWithFixedDelay（ScheduledExecutor
            // 会静默取消后续轮询）；保留旧快照继续服务
            LOG.warn("skill registry refresh failed, keep {} sessions", sessions.size(), e);
        }
    }

    private void refreshInternal() {
        List<SkillConfig> configs;
        try {
            configs = client.fetchEnabled();
        } catch (IOException e) {
            LOG.warn("skill platform pull failed, keep {} sessions", sessions.size(), e);
            return; // 平台不可达：保留旧快照
        }
        Map<String, McpToolSession> next = new LinkedHashMap<>();
        Map<String, McpToolSession> nextOwners = new LinkedHashMap<>();
        for (SkillConfig cfg : configs) {
            try {
                McpToolSession s = sessionFactory.apply(cfg, connectTimeoutMs);
                for (String toolName : s.tools().keySet()) {
                    if (RESERVED_TOOL_NAMES.contains(toolName)) {
                        closeAll(next.values());
                        s.close();
                        LOG.warn("skill registry refresh rejected: skill {} uses reserved tool {}",
                                cfg.id(), toolName);
                        return;
                    }
                    McpToolSession owner = nextOwners.putIfAbsent(toolName, s);
                    if (owner != null) {
                        closeAll(next.values());
                        s.close();
                        LOG.warn("skill registry refresh rejected: duplicate tool {} in skills {} and {}",
                                toolName, owner.skillId(), cfg.id());
                        return;
                    }
                }
                next.put(cfg.id(), s);
            } catch (RuntimeException e) {
                LOG.warn("skill {} mcp connect failed, skip", cfg.id(), e);
            }
        }
        Map<String, McpToolSession> old = sessions;
        sessions = next;
        toolOwners = Map.copyOf(nextOwners);
        lastRefreshMs = System.currentTimeMillis();
        String oldPrompt = promptStore.get();
        String prompt = client.fetchSystemPrompt();
        if (prompt != null && !prompt.equals(oldPrompt)) {
            promptStore.set(prompt);
            LOG.info("system prompt updated ({} chars)", prompt.length());
        }
        for (McpToolSession s : old.values()) {
            if (!next.containsValue(s)) {
                s.close();
            }
        }
        LOG.info("skill registry refreshed: {} sessions ({} tools)",
                next.size(), next.values().stream().mapToInt(s -> s.tools().size()).sum());
    }

    /** 注入 LLM 的工具列表（经注入策略，含分级）。 */
    public List<FunctionTool> enabledToolSpecs() {
        List<FunctionTool> all = new ArrayList<>();
        for (McpToolSession s : sessions.values()) {
            all.addAll(s.tools().values());
        }
        return injector.inject(all);
    }

    /** 按工具名路由到所属 session 执行；未知工具抛 McpToolException。 */
    public String callTool(String toolName, String argumentsJson) {
        McpToolSession owner = toolOwners.get(toolName);
        if (owner != null) {
            return owner.callTool(toolName, argumentsJson);
        }
        throw new McpToolException("no skill owns tool: " + toolName);
    }

    public long lastRefreshMs() {
        return lastRefreshMs;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        closeAll(sessions.values());
        sessions = Map.of();
        toolOwners = Map.of();
    }

    private static void closeAll(Iterable<McpToolSession> values) {
        for (McpToolSession s : values) s.close();
    }
}

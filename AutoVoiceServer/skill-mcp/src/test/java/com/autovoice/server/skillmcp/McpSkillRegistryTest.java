package com.autovoice.server.skillmcp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class McpSkillRegistryTest {

    private static FakeMcpServer fake;
    private static String mcpUrl;

    @BeforeAll
    static void startFake() throws IOException {
        fake = new FakeMcpServer();
        mcpUrl = fake.url();
    }

    @AfterAll
    static void stopFake() throws IOException {
        fake.close();
    }

    /** 假平台配置：指向共享 FakeMcpServer；空 toolsJson = 全选（poi_search/route_plan 两个工具）。 */
    private static SkillConfig cfg(String id) {
        return new SkillConfig(id, id, "d", mcpUrl, "", "", "", true, 1L);
    }

    private static SkillConfig cfgWithOnly(String id, String enabledTool) {
        String tools = "[{\"name\":\"poi_search\",\"enabled\":"
                + ("poi_search".equals(enabledTool) ? "true" : "false")
                + "},{\"name\":\"route_plan\",\"enabled\":"
                + ("route_plan".equals(enabledTool) ? "true" : "false") + "}]";
        return new SkillConfig(id, id, "d", mcpUrl, "", "", tools, true, 1L);
    }

    /** 真会话：McpToolSession.connect 走完整 SDK 握手（对 FakeMcpServer，无真实 MCP）。 */
    private static McpToolSession session(SkillConfig cfg) {
        try {
            return McpToolSession.connect(cfg, 5_000);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void refreshBuildsSnapshotFromEnabledSkills() throws Exception {
        FakePlatformClient client = new FakePlatformClient(
                List.of(cfgWithOnly("a", "poi_search"), cfgWithOnly("b", "route_plan")));
        try (McpSkillRegistry reg = new McpSkillRegistry(client, new DirectToolInjector(),
                new SystemPromptStore(), 60_000, 5_000, (c, timeout) -> session(c))) {
            reg.refresh();
            assertEquals(2, reg.enabledToolSpecs().size()); // 两个 skill，各拥有一个唯一工具
            assertEquals("找到 1 个结果：西湖", reg.callTool("poi_search", "{}")); // 路由到所属 session 执行
        }
    }

    @Test
    void duplicateToolRefreshKeepsPreviousSnapshot() throws Exception {
        AtomicInteger pulls = new AtomicInteger();
        FakePlatformClient client = new FakePlatformClient(null) {
            @Override
            public List<SkillConfig> fetchEnabled() {
                if (pulls.incrementAndGet() == 1) return List.of(cfg("stable"));
                return List.of(cfg("conflict-a"), cfg("conflict-b"));
            }
        };
        try (McpSkillRegistry reg = new McpSkillRegistry(client, new DirectToolInjector(),
                new SystemPromptStore(), 60_000, 5_000, (c, timeout) -> session(c))) {
            reg.refresh();
            assertEquals(2, reg.enabledToolSpecs().size());
            reg.refresh();
            assertEquals(2, reg.enabledToolSpecs().size(), "冲突刷新不得替换上次成功快照");
            assertEquals("找到 1 个结果：西湖", reg.callTool("poi_search", "{}"));
        }
    }

    @Test
    void platformDownKeepsOldSnapshot() throws Exception {
        AtomicInteger pulls = new AtomicInteger();
        FakePlatformClient client = new FakePlatformClient(null) {
            @Override
            public List<SkillConfig> fetchEnabled() throws IOException {
                if (pulls.incrementAndGet() == 1) {
                    return List.of(cfg("a"));
                }
                throw new IOException("platform down");
            }
        };
        try (McpSkillRegistry reg = new McpSkillRegistry(client, new DirectToolInjector(),
                new SystemPromptStore(), 60_000, 5_000, (c, timeout) -> session(c))) {
            reg.refresh();
            assertEquals(2, reg.enabledToolSpecs().size());
            reg.refresh(); // 平台挂了
            assertEquals(2, reg.enabledToolSpecs().size()); // 旧快照仍在
        }
    }

    @Test
    void failedSessionSkipsOnlyThatSkill() throws Exception {
        SkillConfig bad = cfg("bad");
        FakePlatformClient client = new FakePlatformClient(List.of(cfg("good"), bad));
        try (McpSkillRegistry reg = new McpSkillRegistry(client, new DirectToolInjector(),
                new SystemPromptStore(), 60_000, 5_000, (c, timeout) -> {
                    if ("bad".equals(c.id())) {
                        throw new IllegalStateException("mcp down");
                    }
                    return session(c);
                })) {
            reg.refresh();
            assertEquals(2, reg.enabledToolSpecs().size()); // 仅 good 的 2 个工具
        }
    }

    @Test
    void callToolUnknownNameThrows() throws Exception {
        FakePlatformClient client = new FakePlatformClient(List.of(cfg("a")));
        try (McpSkillRegistry reg = new McpSkillRegistry(client, new DirectToolInjector(),
                new SystemPromptStore(), 60_000, 5_000, (c, timeout) -> session(c))) {
            reg.refresh();
            assertThrows(McpToolException.class, () -> reg.callTool("ghost", "{}"));
        }
    }

    @Test
    void unexpectedRuntimeExceptionDoesNotPropagate() throws Exception {
        // 未预期 RuntimeException 穿透 refresh 会让 scheduleWithFixedDelay 静默取消轮询：
        // 顶层守卫必须吞掉（仅 warn）
        FakePlatformClient client = new FakePlatformClient(null) {
            @Override
            public List<SkillConfig> fetchEnabled() throws IOException {
                throw new IllegalStateException("boom");
            }
        };
        try (McpSkillRegistry reg = new McpSkillRegistry(client, new DirectToolInjector(),
                new SystemPromptStore(), 60_000, 5_000, (c, timeout) -> session(c))) {
            assertDoesNotThrow(reg::refresh);
            assertEquals(0, reg.enabledToolSpecs().size()); // 空快照继续服务
        }
    }

    @Test
    void refreshPullsSystemPrompt() throws Exception {
        SystemPromptStore store = new SystemPromptStore();
        FakePlatformClient client = new FakePlatformClient(List.of()) {
            @Override public String fetchSystemPrompt() { return "你是助手"; }
        };
        try (McpSkillRegistry reg = new McpSkillRegistry(client, new DirectToolInjector(),
                store, 60_000, 5_000, (c, timeout) -> session(c))) {
            reg.refresh();
            assertEquals("你是助手", store.get());
        }
    }

    @Test
    void fetchPromptFailureKeepsPrevious() throws Exception {
        SystemPromptStore store = new SystemPromptStore();
        store.set("旧值");
        FakePlatformClient client = new FakePlatformClient(List.of()) {
            @Override public String fetchSystemPrompt() { return null; } // 拉取失败
        };
        try (McpSkillRegistry reg = new McpSkillRegistry(client, new DirectToolInjector(),
                store, 60_000, 5_000, (c, timeout) -> session(c))) {
            reg.refresh();
            assertEquals("旧值", store.get());
        }
    }

    @Test
    void platformDownKeepsPrompt() throws Exception {
        SystemPromptStore store = new SystemPromptStore();
        store.set("旧值");
        FakePlatformClient client = new FakePlatformClient(null) {
            @Override public List<SkillConfig> fetchEnabled() throws IOException {
                throw new IOException("down");
            }
        };
        try (McpSkillRegistry reg = new McpSkillRegistry(client, new DirectToolInjector(),
                store, 60_000, 5_000, (c, timeout) -> session(c))) {
            reg.refresh();
            assertEquals("旧值", store.get());
        }
    }

    private static class FakePlatformClient extends SkillPlatformClient {
        private final List<SkillConfig> configs;

        FakePlatformClient(List<SkillConfig> configs) {
            super(new okhttp3.OkHttpClient(), "http://127.0.0.1:1", "t");
            this.configs = configs;
        }

        @Override
        public List<SkillConfig> fetchEnabled() throws IOException {
            return configs;
        }
    }
}

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
        FakePlatformClient client = new FakePlatformClient(List.of(cfg("a"), cfg("b")));
        try (McpSkillRegistry reg = new McpSkillRegistry(client, new DirectToolInjector(),
                60_000, 5_000, (c, timeout) -> session(c))) {
            reg.refresh();
            assertEquals(4, reg.enabledToolSpecs().size()); // 2 skills × 2 工具
            assertEquals("找到 1 个结果：西湖", reg.callTool("poi_search", "{}")); // 路由到所属 session 执行
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
                60_000, 5_000, (c, timeout) -> session(c))) {
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
                60_000, 5_000, (c, timeout) -> {
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
                60_000, 5_000, (c, timeout) -> session(c))) {
            reg.refresh();
            assertThrows(McpToolException.class, () -> reg.callTool("ghost", "{}"));
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

package com.autovoice.server.skillmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.autovoice.server.contracts.FunctionTool;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

class McpToolSessionTest {

    @Test
    void connectsListsFiltersAndCalls() throws Exception {
        try (FakeMcpServer fake = new FakeMcpServer()) {
            SkillConfig cfg = new SkillConfig("amap-maps", "高德地图", "导航",
                    fake.url(), "", "",
                    "[{\"name\":\"poi_search\",\"enabled\":true},{\"name\":\"route_plan\",\"enabled\":false}]",
                    true, 1L);
            try (McpToolSession s = McpToolSession.connect(cfg, 5000)) {
                assertEquals("amap-maps", s.skillId());
                Map<String, FunctionTool> tools = s.tools();
                assertTrue(tools.containsKey("poi_search"));
                assertFalse(tools.containsKey("route_plan")); // 勾选过滤
                assertEquals("找到 1 个结果：西湖", s.callTool("poi_search", "{\"query\":\"西湖\"}"));
                assertEquals(1, fake.callCount.get());
            }
        }
    }

    @Test
    void emptyToolsJsonMeansAllEnabled() throws Exception {
        try (FakeMcpServer fake = new FakeMcpServer()) {
            SkillConfig cfg = new SkillConfig("a", "b", "c", fake.url(), "", "",
                    "", true, 1L);
            try (McpToolSession s = McpToolSession.connect(cfg, 5000)) {
                assertEquals(2, s.tools().size()); // 空勾选清单 = 全选
            }
        }
    }

    @Test
    void listToolsFailureClosesSessionAndThrowsIOException() throws Exception {
        try (FakeMcpServer fake = new FakeMcpServer(true)) {
            SkillConfig cfg = new SkillConfig("amap-maps", "高德地图", "导航",
                    fake.url(), "", "", "", true, 1L);
            IOException ex = assertThrows(IOException.class,
                    () -> McpToolSession.connect(cfg, 5000));
            assertTrue(ex.getMessage().contains("list_tools"),
                    "message should mention list_tools: " + ex.getMessage());
            assertEquals(1, fake.deleteCount.get()); // 失败路径会关闭会话（DELETE 到达假服务器）
        }
    }

    @Test
    void authHeaderInjectedOnEveryRequest() throws Exception {
        try (FakeMcpServer fake = new FakeMcpServer()) {
            SkillConfig cfg = new SkillConfig("amap-maps", "高德地图", "导航",
                    fake.url(), "x-api-key", "secret-1", "", true, 1L);
            try (McpToolSession s = McpToolSession.connect(cfg, 5000)) {
                assertEquals(2, s.tools().size()); // 空勾选清单 = 全选，握手成功
                assertNotNull(fake.lastRequest, "应捕获到 POST 请求");
                assertEquals("secret-1", fake.lastRequest.getHeader("x-api-key"),
                        "认证头应注入到每个请求");
            }
        }
    }

    @Test
    void toolCallErrorThrowsMcpToolException() throws Exception {
        try (FakeMcpServer fake = new FakeMcpServer(false, true)) {
            SkillConfig cfg = new SkillConfig("amap-maps", "高德地图", "导航",
                    fake.url(), "", "", "", true, 1L);
            try (McpToolSession s = McpToolSession.connect(cfg, 5000)) {
                McpToolException ex = assertThrows(McpToolException.class,
                        () -> s.callTool("poi_search", "{\"query\":\"西湖\"}"));
                assertTrue(ex.getMessage().contains("poi_search"),
                        "message should mention tool name: " + ex.getMessage());
                assertEquals(1, fake.callCount.get());
            }
        }
    }

    @Test
    void unreachableServerThrowsIOException() throws Exception {
        // 连接拒绝（127.0.0.1:1 无监听）：SDK 抛 RuntimeException，connect 统一转 IOException
        SkillConfig cfg = new SkillConfig("amap-maps", "高德地图", "导航",
                "http://127.0.0.1:1/mcp", "", "", "", true, 1L);
        assertThrows(IOException.class, () -> McpToolSession.connect(cfg, 1000));
    }
}

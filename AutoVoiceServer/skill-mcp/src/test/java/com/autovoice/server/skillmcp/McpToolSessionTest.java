package com.autovoice.server.skillmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.autovoice.server.contracts.FunctionTool;
import org.junit.jupiter.api.Test;

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
}

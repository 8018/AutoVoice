package com.autovoice.server.skillmanager;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class McpDiscoveryClientTest {

    @Test
    void discoversToolNamesAndDescriptions() throws Exception {
        try (FakeMcpServer fake = new FakeMcpServer()) {
            McpDiscoveryClient client = new McpDiscoveryClient(5_000);
            List<ToolInfo> tools = client.discover(fake.url(), "", "");
            assertEquals(2, tools.size());
            assertEquals("poi_search", tools.get(0).name());
            assertTrue(tools.get(0).description().contains("搜索"));
        }
    }

    @Test
    void unreachableServerThrows() {
        McpDiscoveryClient client = new McpDiscoveryClient(1_000);
        assertThrows(Exception.class, () -> client.discover("http://127.0.0.1:1/mcp", "", ""));
    }
}

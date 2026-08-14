package com.autovoice.server.skillmcp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class McpToolExecutorTest {

    @Test
    void delegatesAndPropagatesUnknownTool() {
        McpToolExecutor exec = new McpToolExecutor((name, args) -> {
            if ("poi_search".equals(name)) {
                return "结果文本";
            }
            throw new IllegalArgumentException("unknown tool: " + name);
        });
        assertEquals("结果文本", exec.execute("poi_search", "{}"));
        assertThrows(RuntimeException.class, () -> exec.execute("nope", "{}"));
    }
}

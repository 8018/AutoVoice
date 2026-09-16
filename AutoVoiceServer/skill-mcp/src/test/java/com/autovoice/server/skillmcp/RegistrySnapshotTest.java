package com.autovoice.server.skillmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autovoice.server.contracts.FunctionTool;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 测试用最小会话:仅承载工具表(不建立真实 MCP 连接)。 */
final class TestSessions {
    static McpToolSession withTools(String skillId, String... toolNames) {
        Map<String, FunctionTool> tools = new LinkedHashMap<>();
        for (String name : toolNames) {
            tools.put(name, new FunctionTool(name, "", "{\"type\":\"object\"}"));
        }
        SkillConfig config = new SkillConfig(skillId, skillId, "", "llm", "http://unused", "", "",
                "", true, 0L);
        return new McpToolSession(config, null, tools);
    }
}

/** D11a 不可变配置快照:同版本内容一致、不可变、空快照语义。 */
class RegistrySnapshotTest {

    @Test
    void emptySnapshotHasNoToolsAndZeroVersion() {
        RegistrySnapshot empty = RegistrySnapshot.empty();
        assertEquals(0, empty.version());
        assertTrue(empty.sessions().isEmpty());
        assertTrue(empty.toolOwners().isEmpty());
        assertEquals(0, empty.toolCount());
        assertEquals("", empty.systemPrompt());
        assertEquals("", empty.chatSystemPrompt());
    }

    @Test
    void mapsAreDefensivelyCopiedAndImmutable() {
        Map<String, McpToolSession> source = new LinkedHashMap<>();
        source.put("skill-a", TestSessions.withTools("skill-a", "t1"));
        RegistrySnapshot snapshot = new RegistrySnapshot(
                1, source, Map.of(), null, "prompt", "chat", 100L);
        source.put("skill-b", TestSessions.withTools("skill-b", "t2")); // 外部修改不影响快照

        assertEquals(1, snapshot.sessions().size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.sessions().put("skill-c", TestSessions.withTools("skill-c")),
                "快照必须是不可变的");
    }

    @Test
    void nullFieldsAreNormalized() {
        RegistrySnapshot snapshot = new RegistrySnapshot(2, null, null, null, null, null, 0L);
        assertTrue(snapshot.sessions().isEmpty());
        assertTrue(snapshot.toolOwners().isEmpty());
        assertEquals("", snapshot.systemPrompt());
        assertEquals("", snapshot.chatSystemPrompt());
    }

    @Test
    void toolsCountAggregatesAcrossSessions() {
        RegistrySnapshot snapshot = new RegistrySnapshot(3,
                Map.of("a", TestSessions.withTools("a", "t1", "t2"),
                        "b", TestSessions.withTools("b", "t3")),
                Map.of(), null, "", "", 0L);
        assertEquals(3, snapshot.toolCount(), "工具总数应跨会话累加");
    }
}

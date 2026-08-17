package com.autovoice.server.skillmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.autovoice.server.contracts.FunctionTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

class ToolInjectorTest {

    private static List<FunctionTool> tools(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> new FunctionTool("tool_" + i, "t" + i, "{}"))
                .collect(Collectors.toList());
    }

    @Test
    void eightOrFewerDirectAll() {
        List<FunctionTool> all = tools(8);
        List<FunctionTool> out = ToolInjectors.forCount(all.size()).inject(all);
        assertEquals(all, out);
    }

    @Test
    void moreThanEightUsesSelectorMetaTools() {
        List<FunctionTool> all = tools(9);
        List<FunctionTool> out = ToolInjectors.forCount(all.size()).inject(all);
        assertEquals(List.of("mcp_tools_get", "mcp_tools_execute"),
                out.stream().map(FunctionTool::name).toList());
    }

    @Test
    void emptyIsDirect() {
        assertTrue(ToolInjectors.forCount(0).inject(List.of()).isEmpty());
    }
}

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
    void moreThanEightStillDirectInV1() {
        List<FunctionTool> all = tools(9);
        List<FunctionTool> out = ToolInjectors.forCount(all.size()).inject(all);
        assertEquals(all, out); // v1：>8 只告警，仍全量注入（selector 为扩展点）
    }

    @Test
    void emptyIsDirect() {
        assertTrue(ToolInjectors.forCount(0).inject(List.of()).isEmpty());
    }
}

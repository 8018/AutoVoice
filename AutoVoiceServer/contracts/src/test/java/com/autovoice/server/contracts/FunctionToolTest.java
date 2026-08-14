package com.autovoice.server.contracts;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class FunctionToolTest {

    @Test
    void recordAccessors() {
        FunctionTool t = new FunctionTool("car_control", "执行车载控制指令",
                "{\"type\":\"object\"}");
        assertEquals("car_control", t.name());
        assertEquals("执行车载控制指令", t.description());
        assertEquals("{\"type\":\"object\"}", t.parametersJson());
    }

    @Test
    void providerAndExecutorCompile() {
        ToolProvider p = () -> List.of(new FunctionTool("a", "", "{}"));
        assertEquals(1, p.enabledTools().size());
        ToolExecutor e = (name, args) -> "ok:" + name;
        assertEquals("ok:a", e.execute("a", "{}"));
    }
}

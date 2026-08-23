package com.autovoice.server.agentloop;

import java.util.Locale;

/** Classifies calls that may safely share a parallel read-only execution group. */
@FunctionalInterface
public interface ToolExecutionPolicy {
    boolean isParallelRead(AgentToolCall call);

    /**
     * Conservative convention for MCP/query tools. Calls that may mutate state form a dependency
     * barrier: all earlier reads finish before them and later reads start after them.
     */
    static ToolExecutionPolicy conservative() {
        return call -> {
            String name = call.name().toLowerCase(Locale.ROOT);
            if (name.equals("mcp_tools_execute") || name.contains("navigate")
                    || name.contains("control") || name.contains("create")
                    || name.contains("update") || name.contains("delete")
                    || name.contains("set_")) {
                return false;
            }
            return name.contains("search") || name.contains("query") || name.startsWith("get_")
                    || name.startsWith("list_") || name.contains("find") || name.contains("geo")
                    || name.contains("around") || name.contains("nearby")
                    || name.contains("distance") || name.contains("weather")
                    || name.equals("resolve_navigation");
        };
    }
}

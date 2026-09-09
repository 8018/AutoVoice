package com.autovoice.server.agentloop;

import com.autovoice.server.contracts.FunctionTool;
import com.autovoice.server.contracts.ToolExecutionTraits;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface ToolExecutionPolicy {
    boolean isParallelRead(AgentToolCall call);

    default boolean cacheSuccess(AgentToolCall call) { return false; }
    default boolean isReadOnly(AgentToolCall call) { return false; }

    static ToolExecutionPolicy conservative() { return call -> false; }

    /** Snapshot trusted declarations; duplicate tool names fail closed. */
    static ToolExecutionPolicy declared(List<FunctionTool> tools) {
        Map<String, ToolExecutionTraits> traits = new HashMap<>();
        for (FunctionTool tool : tools) {
            traits.merge(tool.name(), tool.executionTraits(), (a, b) -> ToolExecutionTraits.UNKNOWN);
        }
        Map<String, ToolExecutionTraits> snapshot = Map.copyOf(traits);
        return new ToolExecutionPolicy() {
            public boolean isReadOnly(AgentToolCall call) {
                return snapshot.getOrDefault(call.name(), ToolExecutionTraits.UNKNOWN).readOnly();
            }
            public boolean isParallelRead(AgentToolCall call) {
                return snapshot.getOrDefault(call.name(), ToolExecutionTraits.UNKNOWN).parallelSafe();
            }
            public boolean cacheSuccess(AgentToolCall call) {
                return snapshot.getOrDefault(call.name(), ToolExecutionTraits.UNKNOWN).cacheSuccess();
            }
        };
    }
}

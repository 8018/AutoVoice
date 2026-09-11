package com.autovoice.server.agentloop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutionRuntimeTest {
    @Test
    void closeStopsBothOwnedPoolsAndIsIdempotent() {
        AgentExecutionRuntime runtime = new AgentExecutionRuntime();

        runtime.close();
        runtime.close();

        assertTrue(runtime.budgets().isShutdown());
        assertTrue(runtime.toolReads().isShutdown());
    }
}

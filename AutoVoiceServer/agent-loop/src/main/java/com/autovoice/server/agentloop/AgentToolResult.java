package com.autovoice.server.agentloop;

/** Tool result kept in the model's source order even when execution is parallel. */
public record AgentToolResult(AgentToolCall call, String content, boolean cached, boolean error) {
}

package com.autovoice.server.skillmcp;

/** MCP 工具调用失败（isError=true 或 SDK 异常）。调用方把 message 作为 tool_result 回 LLM。 */
public final class McpToolException extends RuntimeException {
    public McpToolException(String message) {
        super(message);
    }
}

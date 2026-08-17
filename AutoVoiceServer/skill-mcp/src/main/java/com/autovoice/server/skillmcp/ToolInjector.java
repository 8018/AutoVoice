package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;

import java.util.List;

/**
 * 工具注入策略：决定哪些 MCP 工具 schema 进 LLM 的 tools 数组。
 * ≤8 个工具直接注入；>8 时注入 mcp_tools_get/mcp_tools_execute 两个 meta 工具。
 */
public interface ToolInjector {
    List<FunctionTool> inject(List<FunctionTool> all);
}

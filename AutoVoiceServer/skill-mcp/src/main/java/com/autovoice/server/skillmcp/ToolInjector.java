package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;

import java.util.List;

/**
 * 工具注入策略：决定哪些 MCP 工具 schema 进 LLM 的 tools 数组。
 * 扩展点：首版只有 direct（≤8 全量）；>8 的 selector（mcp_tools_get/mcp_tools_execute
 * 两个 meta 工具）留待后续实现。
 */
public interface ToolInjector {
    List<FunctionTool> inject(List<FunctionTool> all);
}

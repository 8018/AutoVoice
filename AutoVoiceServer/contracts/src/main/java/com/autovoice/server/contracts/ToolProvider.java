package com.autovoice.server.contracts;

import java.util.List;

/** 提供当前应注入 LLM 的启用的工具列表（car_control + MCP 工具合并）。 */
public interface ToolProvider {
    List<FunctionTool> enabledTools();
}

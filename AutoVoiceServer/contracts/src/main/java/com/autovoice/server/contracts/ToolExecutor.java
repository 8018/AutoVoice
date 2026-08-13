package com.autovoice.server.contracts;

/**
 * 执行一次工具调用，返回文本结果（将作为 tool_result 回 LLM 续轮）。
 * 抛 RuntimeException 表示工具失败（错误文本由调用方兜底回 LLM）。
 */
public interface ToolExecutor {
    String execute(String toolName, String argumentsJson);
}

package com.autovoice.server.contracts;

/**
 * OpenAI 兼容 function calling 工具定义（tool schema 的"可执行形式"）。
 * parametersJson 是 tools 数组中 parameters 对象的 JSON 文本。
 */
public record FunctionTool(String name, String description, String parametersJson) {
}

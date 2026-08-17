package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;

import java.util.List;

/** 工具较多时只注入两个 meta 工具，具体 schema 由模型按当前意图查询后再执行。 */
final class SelectorToolInjector implements ToolInjector {

    static final String GET = "mcp_tools_get";
    static final String EXECUTE = "mcp_tools_execute";

    private static final FunctionTool GET_TOOL = new FunctionTool(GET,
            "按当前用户意图搜索可用工具。先调用本工具取得匹配工具的名称、说明和参数 schema。",
            """
            {"type":"object","properties":{"query":{"type":"string","description":"用户当前要完成的任务"}},
            "required":["query"]}
            """);
    private static final FunctionTool EXECUTE_TOOL = new FunctionTool(EXECUTE,
            "执行 mcp_tools_get 返回的某个工具。name 和 arguments 必须符合查询结果中的 schema。",
            """
            {"type":"object","properties":{"name":{"type":"string"},
            "arguments":{"type":"object","additionalProperties":true}},"required":["name","arguments"]}
            """);

    @Override
    public List<FunctionTool> inject(List<FunctionTool> all) {
        return all.isEmpty() ? List.of() : List.of(GET_TOOL, EXECUTE_TOOL);
    }
}

package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.ToolExecutor;

import java.util.function.BiFunction;

/**
 * ToolExecutor 薄适配器：把"按工具名路由执行"的 lambda 包装为契约接口。
 * 生产装配时 lambda = registry 的按名路由（Task 5）；测试注入假 lambda。
 */
public final class McpToolExecutor implements ToolExecutor {

    private final BiFunction<String, String, String> callFn;

    public McpToolExecutor(BiFunction<String, String, String> callFn) {
        this.callFn = callFn;
    }

    @Override
    public String execute(String toolName, String argumentsJson) {
        return callFn.apply(toolName, argumentsJson);
    }
}

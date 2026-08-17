package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;

import java.util.List;

/** 注入策略工厂：按启用工具总数选择策略（≤8 direct，>8 selector）。 */
public final class ToolInjectors {

    /** 全量 direct 注入的上限。 */
    static final int DIRECT_LIMIT = 8;

    private ToolInjectors() {}

    /** 工具较多时只暴露两个 meta 工具，避免每轮请求携带全部 schema。 */
    public static ToolInjector forCount(int toolCount) {
        if (toolCount > DIRECT_LIMIT) {
            org.slf4j.LoggerFactory.getLogger(ToolInjectors.class)
                    .info("启用工具 {} 个超过 direct 上限 {}，使用 selector 注入", toolCount, DIRECT_LIMIT);
            return new SelectorToolInjector();
        }
        return new DirectToolInjector();
    }
}

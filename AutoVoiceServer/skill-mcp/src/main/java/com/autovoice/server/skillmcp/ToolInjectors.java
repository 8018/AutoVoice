package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;

import java.util.List;

/** 注入策略工厂：按启用工具总数选择策略（spec：≤8 direct，>8 selector 预留）。 */
public final class ToolInjectors {

    /** 全量 direct 注入的上限（超过应走 selector，首版未实现）。 */
    static final int DIRECT_LIMIT = 8;

    private ToolInjectors() {}

    /** 首版实现：无论数量都 direct；>DIRECT_LIMIT 时告警日志提示 selector 未实现。 */
    public static ToolInjector forCount(int toolCount) {
        if (toolCount > DIRECT_LIMIT) {
            org.slf4j.LoggerFactory.getLogger(ToolInjectors.class)
                    .warn("启用工具 {} 个超过 direct 上限 {}，selector 策略未实现，仍全量注入", toolCount, DIRECT_LIMIT);
        }
        return new DirectToolInjector();
    }
}

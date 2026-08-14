package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;

import java.util.List;

/** direct 策略：全部工具原样注入（不复制引用）。 */
public final class DirectToolInjector implements ToolInjector {
    @Override
    public List<FunctionTool> inject(List<FunctionTool> all) {
        return all;
    }
}

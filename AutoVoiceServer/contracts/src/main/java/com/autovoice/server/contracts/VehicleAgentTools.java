package com.autovoice.server.contracts;

import java.util.List;

/** Compact built-in terminal tool definitions shared by classic and Omni model adapters. */
public final class VehicleAgentTools {
    public static final String CAR_CONTROL = "car_control";
    public static final String NAVIGATE = "navigate";

    private static final String CAR_SCHEMA = """
            {"type":"object","properties":{
             "domain":{"type":"string","enum":["climate","window"]},
             "action":{"type":"string","enum":["power_on","power_off","set_temperature"]},
             "temperature":{"type":"number"}},"required":["domain","action"]}
            """;
    private static final String NAVIGATE_SCHEMA = """
            {"type":"object","properties":{
             "poiname":{"type":"string"},"lat":{"type":"number"},"lon":{"type":"number"},
             "waypoints":{"type":"array","items":{"type":"object","properties":{
              "poiname":{"type":"string"},"lat":{"type":"number"},"lon":{"type":"number"}},
              "required":["poiname","lat","lon"]}}},"required":["poiname","lat","lon"]}
            """;

    private VehicleAgentTools() {
    }

    public static List<FunctionTool> definitions() {
        // D04:内置终局工具是"已审核提交操作"——模型只产出待执行计划,实际执行在
        // 输出准入层之后的客户端;D07 将其改造为计划生成端口并撤销此批准
        return List.of(
                new FunctionTool(CAR_CONTROL, "控制空调或车窗", CAR_SCHEMA,
                        ToolExecutionTraits.APPROVED_COMMIT),
                new FunctionTool(NAVIGATE,
                        "打开导航页面；多地点时仅展示路线预览，最终目的地填主字段，之前各站按顺序填 waypoints",
                        NAVIGATE_SCHEMA,
                        ToolExecutionTraits.APPROVED_COMMIT));
    }
}

package com.autovoice.server.skillmcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/** Gates charging requests while the runtime lacks an authorized vehicle and station data source. */
final class ChargingTripPlanner {
    private static final Set<String> MODES = Set.of("along_route_compatible", "arrival_soc", "charge_and_rest");

    private ChargingTripPlanner() {
    }

    static String unavailableReason(JsonNode request) {
        JsonNode mode = request.path("mode");
        JsonNode target = request.path("minimumArrivalSoc");
        if (!request.isObject() || !mode.isTextual() || !MODES.contains(mode.asText())
                || (target.isMissingNode() && mode.asText().equals("arrival_soc"))
                || (!target.isMissingNode() && (!target.isNumber()
                    || !Double.isFinite(target.asDouble()) || target.asDouble() < 0 || target.asDouble() > 100))) {
            return "请说明要找沿途适配的充电站、规划到达剩余电量，还是安排充电和休息。到达剩余电量请用0到100的百分数表示。";
        }
        if (mode.asText().equals("along_route_compatible")) {
            return "暂时缺少可核验的行驶路线、车辆充电接口和沿途充电桩兼容数据，无法筛出顺路且适配的站点。"
                    + "充电桩空闲状态未知。";
        }
        return "暂时缺少可核验的行驶路线、带采样时间的车辆荷电状态、车辆与充电桩兼容数据以及经验证的能耗，无法规划这次补能。";
    }
}

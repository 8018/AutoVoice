package com.autovoice.server.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 车控播报话术生成（从 DeepSeekLlmProvider 的 speakTemplate 提炼，供 LLM 工具调用与
 * 离线命令两条语义路共用）：规范化 {@link Intent} → 播报文本。
 *
 * <p>模板与端侧 RuleNluProvider 意图对齐；未知 domain/缺参数兜底。</p>
 */
public final class SpeakTexts {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** temperature 槽位名（与 shared/contracts 的 slots 命名对齐）。 */
    public static final String SLOT_TEMPERATURE = "temperature";

    /** 导航目的地槽位名（spec §4.2：navigation/navigate {poiname, lat, lon}）。 */
    public static final String SLOT_POINAME = "poiname";

    /** 导航途经点槽位名（多目的地"先去A再去B"：string 槽承载 [{poiname,lat,lon}] JSON 文本）。 */
    public static final String SLOT_WAYPOINTS = "waypoints";

    private SpeakTexts() {
    }

    /** 车控/导航意图 → 播报文本；非车控/缺参数时兜底话术。 */
    public static String speak(Intent intent) {
        String device = switch (intent.domain()) {
            case "climate" -> "空调";
            case "window" -> "车窗";
            default -> "设备";
        };
        SlotValue temperature = intent.slots() == null ? null : intent.slots().get(SLOT_TEMPERATURE);
        return switch (intent.intent()) {
            case "power_on" -> "好的，" + device + "已打开";
            case "power_off" -> "好的，" + device + "已关闭";
            case "set_temperature" -> temperature != null && temperature.value() instanceof Number n
                    ? "好的，" + device + "温度已调到" + formatNumber(n) + "度"
                    : "好的，已为您调整" + device;
            case "navigate" -> {
                SlotValue poiname = intent.slots() == null ? null : intent.slots().get(SLOT_POINAME);
                if (poiname == null || !(poiname.value() instanceof String s) || s.isBlank()) {
                    yield "好的，已为您打开导航";
                }
                // 多目的地（先去A再去B）：waypoints JSON 文本提取途经点名 → "先去A、B再去终点的导航"
                SlotValue waypoints = intent.slots() == null ? null : intent.slots().get(SLOT_WAYPOINTS);
                if (waypoints != null && waypoints.value() instanceof String wp && !wp.isBlank()) {
                    String names = waypointNames(wp);
                    if (!names.isBlank()) {
                        yield "好的，已为您规划先去" + names + "再去" + s + "的导航";
                    }
                }
                yield "好的，已为您规划去" + s + "的导航";
            }
            default -> "好的，已为您执行";
        };
    }

    /** 从 waypoints JSON 文本提取途经点名称（顿号连接）；解析失败/无有效名称 → 空串。 */
    private static String waypointNames(String json) {
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray() || arr.isEmpty()) {
                return "";
            }
            List<String> names = new ArrayList<>();
            for (JsonNode wp : arr) {
                String name = wp.path("poiname").asText("");
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
            return String.join("、", names);
        } catch (IOException e) {
            return "";
        }
    }

    /** 温度数值展示：24.0 → "24"，24.5 → "24.5"。 */
    private static String formatNumber(Number value) {
        double d = value.doubleValue();
        if (d == Math.floor(d)) {
            return String.valueOf(value.longValue());
        }
        return String.valueOf(value);
    }
}

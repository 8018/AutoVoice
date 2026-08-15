package com.autovoice.server.contracts;

/**
 * 车控播报话术生成（从 DeepSeekLlmProvider 的 speakTemplate 提炼，供 LLM 工具调用与
 * 离线命令两条语义路共用）：规范化 {@link Intent} → 播报文本。
 *
 * <p>模板与端侧 RuleNluProvider 意图对齐；未知 domain/缺参数兜底。</p>
 */
public final class SpeakTexts {

    /** temperature 槽位名（与 shared/contracts 的 slots 命名对齐）。 */
    public static final String SLOT_TEMPERATURE = "temperature";

    /** 导航目的地槽位名（spec §4.2：navigation/navigate {poiname, lat, lon}）。 */
    public static final String SLOT_POINAME = "poiname";

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
                yield poiname != null && poiname.value() instanceof String s && !s.isBlank()
                        ? "好的，已为您规划去" + s + "的导航"
                        : "好的，已为您打开导航";
            }
            default -> "好的，已为您执行";
        };
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

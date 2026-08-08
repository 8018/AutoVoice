package com.autovoice.server.nlutraditional;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.SlotValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 讯飞语义（AIUI）供应商响应 → Canonical Intent 的归一化器（纯函数）。
 *
 * <p>解析 {@code data.result.intent.{service,intent,slots[]}}，按 {@link #DOMAIN_MAP}
 * 映射表转 Canonical Intent；拒识（code != "0"、缺 service/intent、未知 service/intent、
 * JSON 不可解析）一律返回 {@link Intent#unknown(String)}——归一化永不抛异常，这是
 * 适配器边界的铁律。槽位转换失败（如 number 不可解析）只丢弃该槽位，意图仍有效。</p>
 */
public final class IflytekSemanticNormalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** service → 领域映射表（demo 版，fixture 驱动；新增意图只加表项，不改逻辑）。 */
    static final Map<String, DomainMapping> DOMAIN_MAP = Map.of(
            "空调", new DomainMapping(
                    "climate",
                    Map.of(
                            "调节温度", "set_temperature",
                            "开启", "power_on",
                            "关闭", "power_off"),
                    Map.of(
                            "温度", SlotMapping.number("temperature"),
                            "对象", SlotMapping.enumValue("zone",
                                    Map.of("主驾", "driver", "副驾", "passenger", "全车", "all")))));

    /**
     * 供应商语义 JSON → Canonical Intent。
     *
     * @param vendorJson 讯飞 text_ai 响应体（{@code code}/{@code data.result.intent}）
     * @param source     Intent.source 字段值（如 {@code "nlu.iflytek.api"}）
     */
    public Intent normalize(String vendorJson, String source) {
        try {
            JsonNode root = MAPPER.readTree(vendorJson);
            if (root == null || !"0".equals(root.path("code").asText())) {
                return Intent.unknown(source);
            }
            JsonNode intentNode = root.path("data").path("result").path("intent");
            if (intentNode.isMissingNode() || intentNode.isNull()) {
                return Intent.unknown(source);
            }
            String service = textOrNull(intentNode.get("service"));
            String vendorIntent = textOrNull(intentNode.get("intent"));
            if (service == null || vendorIntent == null) {
                return Intent.unknown(source);
            }
            DomainMapping dm = DOMAIN_MAP.get(service);
            if (dm == null) {
                return Intent.unknown(source);
            }
            String canonicalIntent = dm.intentMap().get(vendorIntent);
            if (canonicalIntent == null) {
                return Intent.unknown(source);
            }
            Map<String, SlotValue> slots = new LinkedHashMap<>();
            JsonNode slotsNode = intentNode.path("slots");
            if (slotsNode.isArray()) {
                for (JsonNode slot : slotsNode) {
                    String name = textOrNull(slot.get("name"));
                    String value = textOrNull(slot.get("value"));
                    SlotMapping mapping = dm.slotMap().get(name);
                    if (name == null || value == null || mapping == null) {
                        continue;
                    }
                    SlotValue converted = mapping.convert(value);
                    if (converted != null) { // 转换失败只丢槽位，不丢意图
                        slots.put(mapping.canonicalName(), converted);
                    }
                }
            }
            return Intent.of("1.0", dm.domain(), canonicalIntent, slots, 1.0, source, vendorJson);
        } catch (Exception e) {
            return Intent.unknown(source);
        }
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 一个供应商 service 的完整映射：canonical domain + intent 映射 + 槽位映射。 */
    record DomainMapping(String domain, Map<String, String> intentMap, Map<String, SlotMapping> slotMap) {
    }

    /** 槽位映射：canonical 槽名 + 转换规则（number 或 enum）。 */
    record SlotMapping(String canonicalName, Kind kind, Map<String, String> enumMap) {

        enum Kind { NUMBER, ENUM }

        static SlotMapping number(String canonicalName) {
            return new SlotMapping(canonicalName, Kind.NUMBER, Map.of());
        }

        static SlotMapping enumValue(String canonicalName, Map<String, String> mapping) {
            return new SlotMapping(canonicalName, Kind.ENUM, Map.copyOf(mapping));
        }

        /**
         * 供应商槽值 → Canonical SlotValue。
         *
         * <p>number：解析失败返回 {@code null}（调用方丢弃该槽位）；enum：命中映射用映射值，
         * 未识别值原样保留为 enum 字符串。</p>
         */
        SlotValue convert(String vendorValue) {
            return switch (kind) {
                case NUMBER -> toNumber(vendorValue);
                case ENUM -> SlotValue.enumValue(enumMap.getOrDefault(vendorValue, vendorValue));
            };
        }

        private static SlotValue toNumber(String v) {
            try {
                return SlotValue.number(Double.parseDouble(v.trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}

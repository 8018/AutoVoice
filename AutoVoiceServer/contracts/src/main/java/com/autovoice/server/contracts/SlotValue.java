package com.autovoice.server.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 槽位值，与 shared/contracts/intent.schema.json 的 slots 内嵌对象对齐。
 *
 * <p>序列化形状：{@code {"type":"number","value":24.0}}，unit 为空时不输出。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SlotValue {

    private final String type;
    private final Object value;
    private final String unit;

    @JsonCreator
    private SlotValue(@JsonProperty("type") String type,
                      @JsonProperty("value") Object value,
                      @JsonProperty("unit") String unit) {
        this.type = type;
        this.value = value;
        this.unit = unit;
    }

    public static SlotValue number(double v) {
        return new SlotValue("number", v, null);
    }

    public static SlotValue enumValue(String v) {
        return new SlotValue("enum", v, null);
    }

    public static SlotValue stringValue(String v) {
        return new SlotValue("string", v, null);
    }

    public static SlotValue bool(boolean v) {
        return new SlotValue("boolean", v, null);
    }

    /** 返回带 unit 的副本（unit 为空则返回 this）。 */
    public SlotValue withUnit(String unit) {
        if (unit == null || unit.equals(this.unit)) {
            return this;
        }
        return new SlotValue(type, value, unit);
    }

    @JsonProperty
    public String type() {
        return type;
    }

    /** number 存 Double，enum/string 存 String，boolean 存 Boolean。 */
    @JsonProperty
    public Object value() {
        return value;
    }

    @JsonProperty
    public String unit() {
        return unit;
    }
}

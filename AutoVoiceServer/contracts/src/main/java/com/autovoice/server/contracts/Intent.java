package com.autovoice.server.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 规范化意图，不可变，与 shared/contracts/intent.schema.json 对齐。
 *
 * <p>record 组件名即 schema 字段名；{@link #of} 等价于规范构造函数，
 * {@link #unknown(String)} 用于无意图可用的兜底，{@link #isUnknown()} 判断兜底意图。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Intent(
        @JsonProperty("schemaVersion") String schemaVersion,
        @JsonProperty("domain") String domain,
        @JsonProperty("intent") String intent,
        @JsonProperty("slots") Map<String, SlotValue> slots,
        @JsonProperty("confidence") double confidence,
        @JsonProperty("source") String source,
        @JsonProperty("rawSemantic") String rawSemantic) {

    @JsonCreator
    public Intent {
    }

    public static Intent of(String schemaVersion, String domain, String intent,
                            Map<String, SlotValue> slots, double confidence,
                            String source, String rawSemantic) {
        return new Intent(schemaVersion, domain, intent, slots, confidence, source, rawSemantic);
    }

    public static Intent unknown(String source) {
        return of("1.0", "unknown", "unknown", Map.of(), 0.0, source, null);
    }

    /** 非 schema 字段，显式忽略，避免 Jackson 把 isUnknown() 当属性序列化。 */
    @JsonIgnore
    public boolean isUnknown() {
        return "unknown".equals(domain);
    }
}

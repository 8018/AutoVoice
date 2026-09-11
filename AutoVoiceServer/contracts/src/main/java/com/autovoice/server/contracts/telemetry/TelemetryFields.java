package com.autovoice.server.contracts.telemetry;

import java.util.Locale;
import java.util.Set;

/**
 * D01b 统一事件字段约定。
 *
 * <p>结构化组件见 {@link TelemetryEvent}(subject/session/turn/request/configVersion/
 * result/reason);本类提供 payload 键常量与敏感键判定。约定:</p>
 * <ul>
 *   <li>不记录密钥:含 token/secret/key/password/credential 的键不应进入 payload;</li>
 *   <li>音频与用户原文不作为默认诊断字段(确需携带时使用显式 opt-in 键并控制长度);</li>
 *   <li>请求 ID 只作关联字段,不成为指标标签(避免无限基数)。</li>
 * </ul>
 */
public final class TelemetryFields {

    public static final String SUBJECT = "subject";
    public static final String SESSION = "session";
    public static final String TURN = "turn";
    public static final String REQUEST = "request";
    public static final String CONFIG_VERSION = "configVersion";
    public static final String RESULT = "result";
    public static final String REASON = "reason";

    private static final Set<String> SENSITIVE_FRAGMENTS =
        Set.of("token", "secret", "password", "credential", "apikey", "api_key", "privatekey");

    private TelemetryFields() {
    }

    /** 键名(小写后)命中敏感片段即判定为敏感键。 */
    public static boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[-_]", "");
        for (String fragment : SENSITIVE_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}

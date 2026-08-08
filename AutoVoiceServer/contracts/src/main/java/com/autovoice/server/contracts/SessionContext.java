package com.autovoice.server.contracts;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 会话上下文（服务端内部对象，非线协议消息）。
 *
 * <p>不可变：attrs 构造时防御性拷贝，{@link #withAttr} 返回新实例。</p>
 */
public final class SessionContext {

    private final String sessionId;
    private final String language;
    private final Map<String, Object> attrs;

    public SessionContext(String sessionId, String language, Map<String, Object> attrs) {
        this.sessionId = sessionId;
        this.language = language;
        this.attrs = attrs == null ? Map.of() : new HashMap<>(attrs);
    }

    public String sessionId() {
        return sessionId;
    }

    public String language() {
        return language;
    }

    public Map<String, Object> attrs() {
        return Collections.unmodifiableMap(attrs);
    }

    public SessionContext withAttr(String key, Object value) {
        Map<String, Object> copy = new HashMap<>(attrs);
        copy.put(key, value);
        return new SessionContext(sessionId, language, copy);
    }
}

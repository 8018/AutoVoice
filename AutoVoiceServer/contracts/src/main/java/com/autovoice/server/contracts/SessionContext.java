package com.autovoice.server.contracts;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 会话上下文(服务端内部对象,非线协议消息)。
 *
 * <p>不可变:attrs 构造时防御性拷贝,{@link #withAttr} 返回新实例。
 * D02a:新增会话所有权与恢复凭据——{@code owner} 为认证主体(设备标识),
 * {@code resumeToken} 为独立于 sessionId 的恢复凭据,时间字段支持过期校验。</p>
 */
public final class SessionContext {

    private final String sessionId;
    private final String language;
    private final Map<String, Object> attrs;
    private final String owner;
    private final String resumeToken;
    private final long createdAtMs;
    private final long lastActiveAtMs;
    private final long expiresAtMs;

    /** 兼容构造:无主会话(demo/本地路径与内部临时对象),所有权与时间字段取零值。 */
    public SessionContext(String sessionId, String language, Map<String, Object> attrs) {
        this(sessionId, language, attrs, "", "", 0L, 0L, 0L);
    }

    public SessionContext(String sessionId, String language, Map<String, Object> attrs,
                          String owner, String resumeToken,
                          long createdAtMs, long lastActiveAtMs, long expiresAtMs) {
        this.sessionId = sessionId;
        this.language = language;
        this.attrs = attrs == null ? Map.of() : new HashMap<>(attrs);
        this.owner = owner == null ? "" : owner;
        this.resumeToken = resumeToken == null ? "" : resumeToken;
        this.createdAtMs = createdAtMs;
        this.lastActiveAtMs = lastActiveAtMs;
        this.expiresAtMs = expiresAtMs;
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

    /** 会话所有者(认证主体);空串 = 无主(demo/本地兼容路径)。 */
    public String owner() {
        return owner;
    }

    /** 会话恢复凭据,独立于可展示的 sessionId;不得写入日志。 */
    public String resumeToken() {
        return resumeToken;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    public long lastActiveAtMs() {
        return lastActiveAtMs;
    }

    public long expiresAtMs() {
        return expiresAtMs;
    }

    public SessionContext withAttr(String key, Object value) {
        Map<String, Object> copy = new HashMap<>(attrs);
        copy.put(key, value);
        return new SessionContext(sessionId, language, copy,
            owner, resumeToken, createdAtMs, lastActiveAtMs, expiresAtMs);
    }

    /** 活跃续期:lastActive 与过期时间同时推进(滑动过期)。 */
    public SessionContext withActivity(long nowMs, long ttlMs) {
        return new SessionContext(sessionId, language, attrs,
            owner, resumeToken, createdAtMs, nowMs, nowMs + ttlMs);
    }
}

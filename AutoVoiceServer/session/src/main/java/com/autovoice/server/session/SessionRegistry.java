package com.autovoice.server.session;

import com.autovoice.server.contracts.SessionContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 会话注册表:sessionId → {@link SessionContext}。
 *
 * <p>线程安全设计:存储用 {@link ConcurrentHashMap}(读并发、无锁);插入序跟踪 + 容量守卫用
 * {@link LinkedHashMap}(accessOrder=false) 覆写 {@code removeEldestEntry},所有写操作
 * (create 登记、remove 清理)串行化在同一把锁下,保证两个结构保持一致。</p>
 *
 * <p>淘汰语义:容量满({@value #MAX_CAPACITY})时移除最旧的插入项(FIFO,LRU 简化;
 * get 不刷新顺序)。</p>
 *
 * <p>D02a 会话所有权:会话绑定认证主体(owner),恢复凭据(resumeToken)独立于 sessionId;
 * {@link #resume} 按 空会话新建之外的四种恢复结果(NOT_FOUND / OWNER_MISMATCH /
 * BAD_CREDENTIAL / EXPIRED)判定,过期会话惰性移除(滑动过期)。resumeToken 不进入任何日志。</p>
 */
public final class SessionRegistry {

    /** 最大会话数,满时自动淘汰最旧的插入项。 */
    public static final int MAX_CAPACITY = 1000;

    /** 会话滑动过期默认时长(24h),构造时可按部署注入覆盖。 */
    public static final long DEFAULT_SESSION_TTL_MS = 24L * 60 * 60 * 1000L;

    /** 恢复判定结果。 */
    public enum ResumeResult { OK, NOT_FOUND, OWNER_MISMATCH, BAD_CREDENTIAL, EXPIRED }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConcurrentHashMap<String, SessionContext> sessions = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final long sessionTtlMs;

    // 纯插入序跟踪(accessOrder=false):超限时 removeEldestEntry 同步从 sessions 淘汰最旧插入项。
    // 值仅占位(=key),只用其键的有序性。
    private final LinkedHashMap<String, String> insertionOrder = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            if (size() <= MAX_CAPACITY) {
                return false;
            }
            sessions.remove(eldest.getKey()); // 容量守卫:淘汰最旧的插入项
            return true;
        }
    };

    private final Object writeLock = new Object();

    public SessionRegistry() {
        this(System::currentTimeMillis, DEFAULT_SESSION_TTL_MS);
    }

    public SessionRegistry(LongSupplier clock, long sessionTtlMs) {
        this.clock = clock;
        this.sessionTtlMs = sessionTtlMs;
    }

    /**
     * 创建并登记一个新会话:sessionId 由 {@link UUID#randomUUID()} 生成。
     *
     * @return 新会话;若生成的 sessionId 与已有会话冲突(UUID 碰撞,理论几乎不可能),返回已有实例
     */
    public SessionContext create(String language) {
        return create(language, "");
    }

    /** 创建绑定认证主体的会话,签发独立恢复凭据。 */
    public SessionContext create(String language, String owner) {
        long now = clock.getAsLong();
        String sessionId = UUID.randomUUID().toString();
        SessionContext ctx = new SessionContext(sessionId, language, Map.of(),
            owner, newResumeToken(), now, now, now + sessionTtlMs);
        synchronized (writeLock) {
            SessionContext existing = sessions.putIfAbsent(sessionId, ctx);
            if (existing != null) {
                return existing;
            }
            insertionOrder.put(sessionId, sessionId); // 超过 MAX_CAPACITY 时触发淘汰
        }
        return ctx;
    }

    /**
     * 恢复判定(D02a):按 owner 与 resumeToken 校验,过期会话移除后返回 {@code EXPIRED}。
     * 通过时刷新滑动过期并返回最新实例。
     */
    public ResumeResult resume(String sessionId, String owner, String resumeToken) {
        SessionContext stored = sessions.get(sessionId);
        if (stored == null) {
            return ResumeResult.NOT_FOUND;
        }
        long now = clock.getAsLong();
        if (now > stored.expiresAtMs()) {
            remove(sessionId);
            return ResumeResult.EXPIRED;
        }
        if (!stored.owner().isBlank() && !stored.owner().equals(owner)) {
            return ResumeResult.OWNER_MISMATCH;
        }
        if (resumeToken == null || !MessageDigest.isEqual(
                resumeToken.getBytes(StandardCharsets.UTF_8),
                stored.resumeToken().getBytes(StandardCharsets.UTF_8))) {
            return ResumeResult.BAD_CREDENTIAL;
        }
        sessions.put(sessionId, stored.withActivity(now, sessionTtlMs));
        return ResumeResult.OK;
    }

    /** 按 sessionId 取会话;不存在(含已被淘汰)时返回 null。 */
    public SessionContext get(String sessionId) {
        return sessions.get(sessionId);
    }

    /** 移除会话;不存在时是幂等 no-op。同步清理插入序跟踪结构,避免残留占位。 */
    public void remove(String sessionId) {
        synchronized (writeLock) {
            sessions.remove(sessionId);
            insertionOrder.remove(sessionId); // O(1),保持跟踪结构与存储一致
        }
    }

    /** 当前登记中的会话数。 */
    public int size() {
        return sessions.size();
    }

    private static String newResumeToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}

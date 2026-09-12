package com.autovoice.server.skillmanager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * D03b 管理端服务端会话存储(单实例内存版):
 *
 * <ul>
 *   <li>登录签发随机 32 字节会话令牌,令牌原文只存在于浏览器 cookie;</li>
 *   <li>内存只保存 SHA-256(令牌) → 过期时间,避免内存泄露直读令牌;</li>
 *   <li>校验即滑动续期;过期会话惰性移除;注销撤销后令牌立即失效;</li>
 *   <li>重启后会话全部失效(单实例语义,业务恢复归 D15)。</li>
 * </ul>
 */
final class AdminSessionStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final LongSupplier clock;
    private final long ttlMs;
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    AdminSessionStore(LongSupplier clock, long ttlMs) {
        this.clock = clock;
        this.ttlMs = ttlMs;
    }

    /** 签发新会话,返回随机令牌(原文仅交给调用方写入 cookie)。 */
    String create() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        sessions.put(hash(token), clock.getAsLong() + ttlMs);
        return token;
    }

    /** 校验令牌:存在、未过期即通过并滑动续期;过期/未知拒绝,过期项惰性移除。 */
    boolean validate(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String key = hash(token);
        Long expiresAt = sessions.get(key);
        if (expiresAt == null) {
            return false;
        }
        long now = clock.getAsLong();
        if (now > expiresAt) {
            sessions.remove(key);
            return false;
        }
        sessions.put(key, now + ttlMs);
        return true;
    }

    /** 注销撤销:令牌立即失效。 */
    void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        sessions.remove(hash(token));
    }

    int size() {
        return sessions.size();
    }

    private static String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

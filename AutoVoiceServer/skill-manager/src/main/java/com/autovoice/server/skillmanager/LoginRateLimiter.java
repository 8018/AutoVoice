package com.autovoice.server.skillmanager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * D03b 管理登录限流(内存固定窗口,单实例语义):同一来源 IP 窗口内失败次数达到上限后拒绝,
 * 窗口过期自动恢复;登录成功清零计数。来源取 X-Forwarded-For 首值或远端地址。
 */
final class LoginRateLimiter {

    private static final int DEFAULT_WINDOW_MS = 60_000;
    private static final int DEFAULT_MAX_FAILURES = 5;

    private record Window(long startMs, int failures) {
    }

    private final LongSupplier clock;
    private final long windowMs;
    private final int maxFailures;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    LoginRateLimiter(LongSupplier clock) {
        this(clock, DEFAULT_WINDOW_MS, DEFAULT_MAX_FAILURES);
    }

    LoginRateLimiter(LongSupplier clock, long windowMs, int maxFailures) {
        this.clock = clock;
        this.windowMs = windowMs;
        this.maxFailures = maxFailures;
    }

    /** 是否允许本次登录尝试(在失败计数尚未超限时)。 */
    boolean tryAcquire(String ip) {
        long now = clock.getAsLong();
        Window window = windows.compute(ip, (key, current) -> {
            if (current == null || now - current.startMs() >= windowMs) {
                return new Window(now, 0);
            }
            return current;
        });
        return window.failures() < maxFailures;
    }

    /** 登录失败:失败计数 +1。 */
    void onFailure(String ip) {
        long now = clock.getAsLong();
        windows.compute(ip, (key, current) -> {
            if (current == null || now - current.startMs() >= windowMs) {
                return new Window(now, 1);
            }
            return new Window(current.startMs(), current.failures() + 1);
        });
    }

    /** 登录成功:清零该来源计数。 */
    void reset(String ip) {
        windows.remove(ip);
    }
}

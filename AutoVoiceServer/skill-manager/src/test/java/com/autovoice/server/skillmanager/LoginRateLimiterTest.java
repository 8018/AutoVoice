package com.autovoice.server.skillmanager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autovoice.server.contracts.testing.TestClock;
import org.junit.jupiter.api.Test;

/** D03b 登录限流:固定窗口内失败次数上限,窗口过期自动恢复。 */
class LoginRateLimiterTest {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_FAILURES = 5;

    private final TestClock clock = new TestClock(0L);
    private final LoginRateLimiter limiter = new LoginRateLimiter(clock, WINDOW_MS, MAX_FAILURES);

    @Test
    void blocksAfterMaxFailuresWithinWindow() {
        for (int i = 0; i < MAX_FAILURES; i++) {
            assertTrue(limiter.tryAcquire("1.2.3.4"));
            limiter.onFailure("1.2.3.4");
        }
        assertFalse(limiter.tryAcquire("1.2.3.4"), "窗口内失败超限应拒绝");
        assertTrue(limiter.tryAcquire("5.6.7.8"), "其他 IP 不受影响");
    }

    @Test
    void windowExpiryRestoresAccess() {
        for (int i = 0; i < MAX_FAILURES; i++) {
            limiter.tryAcquire("1.2.3.4");
            limiter.onFailure("1.2.3.4");
        }
        clock.advance(WINDOW_MS + 1);
        assertTrue(limiter.tryAcquire("1.2.3.4"), "窗口过期应恢复");
    }

    @Test
    void successResetsFailureCounter() {
        for (int i = 0; i < MAX_FAILURES - 1; i++) {
            limiter.tryAcquire("1.2.3.4");
            limiter.onFailure("1.2.3.4");
        }
        limiter.reset("1.2.3.4");
        for (int i = 0; i < MAX_FAILURES; i++) {
            assertTrue(limiter.tryAcquire("1.2.3.4"));
            limiter.onFailure("1.2.3.4");
        }
    }
}

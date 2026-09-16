package com.autovoice.server.offlinecommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autovoice.server.contracts.testing.TestClock;
import org.junit.jupiter.api.Test;

/** D08a 引擎健康状态机:连续失败熔断、冷却后半开探测、成功复位。 */
class EngineHealthTest {

    private static final int FAILURE_THRESHOLD = 3;
    private static final long COOLDOWN_MS = 5_000;

    private final TestClock clock = new TestClock(0);
    private final EngineHealth health = new EngineHealth(FAILURE_THRESHOLD, COOLDOWN_MS, clock);

    @Test
    void healthyEngineAcceptsWork() {
        assertTrue(health.tryAcquire());
        assertFalse(health.isOpen());
    }

    @Test
    void consecutiveFailuresOpenCircuit() {
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            assertTrue(health.tryAcquire());
            health.onFailure();
        }
        assertTrue(health.isOpen(), "连续失败达到阈值应熔断");
        assertFalse(health.tryAcquire(), "熔断期不再接收任务(明确降级,不排队)");
    }

    @Test
    void successResetsFailureCount() {
        health.tryAcquire();
        health.onFailure();
        health.tryAcquire();
        health.onSuccess();
        health.tryAcquire();
        health.onFailure();
        assertFalse(health.isOpen(), "成功应清零连续失败计数");
    }

    @Test
    void cooldownAllowsHalfOpenProbe() {
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            health.tryAcquire();
            health.onFailure();
        }
        assertFalse(health.tryAcquire());
        clock.advance(COOLDOWN_MS + 1);
        assertTrue(health.tryAcquire(), "冷却后允许一次半开探测");
        health.onSuccess();
        assertFalse(health.isOpen(), "探测成功应恢复");
        assertTrue(health.tryAcquire());
    }

    @Test
    void constantFailuresKeepCircuitOpen() {
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            health.tryAcquire();
            health.onFailure();
        }
        clock.advance(COOLDOWN_MS + 1);
        assertTrue(health.tryAcquire());
        health.onFailure(); // 探测失败
        assertTrue(health.isOpen(), "探测失败应重新熔断");
        assertFalse(health.tryAcquire());
    }

    @Test
    void failureCountsReportedForTelemetry() {
        health.tryAcquire();
        health.onFailure();
        assertEquals(1, health.consecutiveFailures());
    }
}

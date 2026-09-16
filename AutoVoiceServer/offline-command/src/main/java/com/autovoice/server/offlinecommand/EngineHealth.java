package com.autovoice.server.offlinecommand;

import java.util.function.LongSupplier;

/**
 * D08a 单个原生引擎的健康状态机:
 *
 * <ul>
 *   <li>连续失败(含调用方超时未结束)达到阈值 → 熔断(OPEN),期间 {@link #tryAcquire} 拒绝,</li>
 *   <li>明确降级而不是继续排队等同一个卡死引擎;</li>
 *   <li>冷却期后允许一次半开探测(HALF_OPEN):成功复位,失败立即重新熔断;</li>
 *   <li>任意成功清零连续失败计数。</li>
 * </ul>
 *
 * <p>调用方超时与执行结束是两件事:超时只终结返回 Future,不证明引擎空闲;
 * 引擎侧通过 {@link #onFailure()} 在"执行确实未在期限内结束"时上报。</p>
 */
final class EngineHealth {

    private final int failureThreshold;
    private final long cooldownMs;
    private final LongSupplier clock;

    private int consecutiveFailures;
    private long openUntilMs = Long.MIN_VALUE;
    private boolean probeInFlight;

    EngineHealth(int failureThreshold, long cooldownMs, LongSupplier clock) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.cooldownMs = Math.max(1, cooldownMs);
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    /** 是否接收任务:健康期直接放行;熔断期冷却内拒绝,冷却后放行一次半开探测。 */
    synchronized boolean tryAcquire() {
        if (consecutiveFailures < failureThreshold) {
            return true; // 健康
        }
        if (clock.getAsLong() < openUntilMs) {
            return false; // 熔断冷却中:明确降级,不排队
        }
        if (probeInFlight) {
            return false; // 半开探测在途
        }
        probeInFlight = true; // 冷却已过:放行一次探测
        return true;
    }

    synchronized void onSuccess() {
        consecutiveFailures = 0;
        openUntilMs = Long.MIN_VALUE;
        probeInFlight = false;
    }

    synchronized void onFailure() {
        consecutiveFailures++;
        probeInFlight = false;
        if (consecutiveFailures >= failureThreshold) {
            openUntilMs = clock.getAsLong() + cooldownMs;
        }
    }

    /** 熔断状态:连续失败达到阈值且尚未被成功复位(冷却到期后仍需探测成功才算恢复)。 */
    synchronized boolean isOpen() {
        return consecutiveFailures >= failureThreshold;
    }

    synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }
}

package com.autovoice.server.app.health;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * D12a 服务就绪与排空状态(不访问付费依赖,健康探针安全):
 *
 * <ul>
 *   <li>存活(live):进程在运行即可——与非就绪状态区分,避免"坏依赖被反复重启";</li>
 *   <li>就绪(ready):全部**关键**组件已登记且为 READY;可降级组件失败不摘除业务
 *       (单个可选依赖挂掉不应导致整体下线);</li>
 *   <li>排空(draining):停止接入新工作,等待在途结束;超过期限按期限结束,不无限等待。</li>
 * </ul>
 */
public final class ServiceReadiness {

    /** 组件状态。 */
    public enum State { PENDING, READY, FAILED }

    private final Map<String, State> critical = new ConcurrentHashMap<>();
    private final Map<String, State> degradable = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final long drainTimeoutMs;

    private volatile boolean draining;
    private volatile long drainStartedAtMs;
    private final AtomicInteger inFlight = new AtomicInteger();

    public ServiceReadiness(LongSupplier clock, long drainTimeoutMs) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.drainTimeoutMs = Math.max(1, drainTimeoutMs);
    }

    /**
     * 声明**必需**组件(PENDING 起步):该组件未 READY 前服务不就绪。
     *
     * <p>必须显式声明必需组件——装配方未声明任何必需组件视为接线缺陷,
     * {@link #isReady()} 一律返回 false(fail-closed),避免"忘了接线"被
     * 静默解释成"永远就绪"(D16 验收发现的实际缺陷)。</p>
     */
    public void registerCritical(String name) {
        critical.putIfAbsent(name, State.PENDING);
    }

    /** 登记可降级组件(失败仅记录,不摘除业务)。 */
    public void registerDegradable(String name) {
        degradable.putIfAbsent(name, State.PENDING);
    }

    public void markReady(String name) {
        if (critical.containsKey(name)) {
            critical.put(name, State.READY);
        } else if (degradable.containsKey(name)) {
            degradable.put(name, State.READY);
        }
    }

    public void markFailed(String name, String reason) {
        if (critical.containsKey(name)) {
            critical.put(name, State.FAILED);
        } else if (degradable.containsKey(name)) {
            degradable.put(name, State.FAILED);
        }
    }

    /** 存活:进程在运行(恒真;保留方法以便端点表达语义)。 */
    public boolean isLive() {
        return true;
    }

    /**
     * 就绪判定(三个条件全部满足才 UP):
     * <ol>
     *   <li>未处于排空;</li>
     *   <li>**已声明至少一个必需组件**(未声明 = 接线缺陷 → 不就绪);</li>
     *   <li>全部必需组件为 READY(任一未就绪/失败 → 不就绪)。</li>
     * </ol>
     * 可降级组件不参与该判定:单个可选依赖失败不摘除整体业务。
     */
    public boolean isReady() {
        if (draining) {
            return false;
        }
        if (critical.isEmpty()) {
            return false; // 接线缺陷:未声明任何必需组件
        }
        return critical.values().stream().allMatch(state -> state == State.READY);
    }

    public synchronized void beginDraining() {
        if (draining) {
            return;
        }
        draining = true;
        drainStartedAtMs = clock.getAsLong();
    }

    public boolean isDraining() {
        return draining;
    }

    /** 是否接收新工作(排空中不接收)。 */
    public boolean acceptsNewWork() {
        return !draining;
    }

    public void onWorkStarted() {
        inFlight.incrementAndGet();
    }

    public void onWorkFinished() {
        inFlight.updateAndGet(current -> Math.max(0, current - 1));
    }

    /** 排空是否结束:无在途工作,或已超过排空期限。 */
    public boolean drainComplete() {
        if (!draining) {
            return false;
        }
        if (inFlight.get() == 0) {
            return true;
        }
        return clock.getAsLong() - drainStartedAtMs >= drainTimeoutMs;
    }

    public int inFlight() {
        return inFlight.get();
    }

    public int componentCount() {
        return critical.size() + degradable.size();
    }

    /** 诊断用快照(不含任何敏感信息)。 */
    public String snapshot() {
        StringBuilder sb = new StringBuilder();
        critical.forEach((name, state) -> sb.append(name).append('=').append(state).append(", "));
        degradable.forEach((name, state) -> sb.append(name).append('=').append(state).append(", "));
        if (sb.length() >= 2) {
            sb.setLength(sb.length() - 2);
        }
        return sb.toString();
    }
}

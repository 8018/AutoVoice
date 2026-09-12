package com.autovoice.server.offlinecommand.supervisor;

import java.util.function.LongSupplier;

/**
 * D09a 原生引擎进程监督器(与 SDK 无关,进程行为经 {@link WorkerProcess} 抽象):
 *
 * <ul>
 *   <li>崩溃检测:worker 存活判定失败 → 判定不健康,拒绝服务(由上层降级);</li>
 *   <li>指数退避重启:base × 2^(连续崩溃次数),避免崩溃循环立即重启;</li>
 *   <li>重启风暴熔断:连续崩溃达上限后停止重启(需 {@link #onStable()} 复位);</li>
 *   <li>世代隔离:每次重启推进 generation,旧世代在途结果必须丢弃(不污染新引擎)。</li>
 * </ul>
 *
 * <p>真实 SDK 的 worker 主类与服务器演练属 D09b(待环境),本类只承载可本地验证的
 * 生命周期状态机(见 docs/d09-vendor-constraint-review.md)。</p>
 */
public final class OfflineEngineSupervisor {

    /** 被监督的 worker 进程抽象(生产:真实子进程;测试:可编排假实现)。 */
    public interface WorkerProcess {
        boolean start();
        boolean isAlive();
        void destroy();
    }

    private final WorkerProcess worker;
    private final LongSupplier clock;
    private final long restartBaseMs;
    private final int restartMax;

    private long generation;
    private boolean started;
    private int consecutiveCrashes;
    private boolean crashPending;      // 已检测到崩溃并安排了退避
    private long nextRestartAtMs;      // 退避到期的绝对时刻

    public OfflineEngineSupervisor(WorkerProcess worker, LongSupplier clock,
                                   long restartBaseMs, int restartMax) {
        this.worker = java.util.Objects.requireNonNull(worker, "worker");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.restartBaseMs = Math.max(1, restartBaseMs);
        this.restartMax = Math.max(1, restartMax);
    }

    /** 启动 worker;成功推进世代并复位崩溃计数。 */
    public synchronized boolean start() {
        if (started && worker.isAlive()) {
            return true;
        }
        if (!worker.start()) {
            started = false;
            return false;
        }
        started = true;
        generation++;
        consecutiveCrashes = 0;
        crashPending = false;
        return true;
    }

    public synchronized boolean isHealthy() {
        return started && worker.isAlive();
    }

    /**
     * 崩溃检测入口(由上层健康轮询/存活判定失败时调用):累计崩溃次数并安排指数退避。
     * 幂等:worker 已不健康时重复调用不会重复计数。
     */
    public synchronized void onCrashDetected() {
        if (isHealthy()) {
            return;
        }
        if (crashPending) {
            return; // 本次崩溃已登记(退避已安排)
        }
        consecutiveCrashes++;
        scheduleNextRestart();
    }

    /** 重启尝试:退避未到期或已熔断时返回 false。 */
    public synchronized boolean tryRestart() {
        if (isHealthy()) {
            return true;
        }
        if (isCircuitOpen()) {
            return false;
        }
        if (clock.getAsLong() < nextRestartAtMs) {
            return false; // 退避中
        }
        if (!worker.start()) {
            crashPending = false; // 启动失败视为一次崩溃,重新安排退避
            onCrashDetected();
            return false;
        }
        started = true;
        generation++;
        crashPending = false; // 重启成功:等待下一次崩溃检测
        return true;
    }

    /** 稳定运行确认(由上层在观察窗口后调用):复位退避与熔断计数。 */
    public synchronized void onStable() {
        consecutiveCrashes = 0;
        crashPending = false;
    }

    /** 已熔断:连续崩溃达到上限,停止重启避免风暴。 */
    public synchronized boolean isCircuitOpen() {
        return consecutiveCrashes >= restartMax;
    }

    public synchronized long generation() {
        return generation;
    }

    /** 世代校验:只接受当前世代的结果,丢弃重启前的在途结果。 */
    public synchronized boolean acceptsResult(long resultGeneration) {
        return resultGeneration == generation;
    }

    public synchronized void close() {
        started = false;
        worker.destroy();
    }

    private void scheduleNextRestart() {
        // 第一次崩溃等待 base,第二次 2×base,依次指数增长(上限 2^16)
        long backoff = restartBaseMs * (1L << Math.min(consecutiveCrashes - 1, 16));
        nextRestartAtMs = clock.getAsLong() + backoff;
        crashPending = true;
    }
}

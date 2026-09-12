package com.autovoice.server.offlinecommand;

import com.autovoice.server.contracts.OfflineCommandProvider;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.contracts.telemetry.TelemetryStages;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 离线识别引擎池（多设备加固 M3）。
 *
 * 设计：N 个独立 worker（每个一个 NativeOfflineCommandProvider，各自 JNI 桥实例与串行
 * 执行队列），会话级 sticky 分配——同一 sessionId 恒路由到同一 worker（顺序稳定、互不
 * 干扰），不同 worker 之间可并行；Semaphore(N) 超载快速失败——池满时该句话语直接降级
 * ASR/LLM 链路（空结果），绝不排队阻塞消息线程。
 *
 * 语义与单实例一致：永不抛异常，失败/超载返回已完成空结果（上层 OfflineCommandService
 * 不感知池的存在）。C++ 侧当前以全局互斥兜底串行识别（见 autovoice_offline_esr.cpp），
 * 服务器实测确认会话级隔离后可去锁并行。
 */
public final class OfflineEnginePool implements OfflineCommandProvider, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OfflineEnginePool.class);

    private final List<OfflineCommandProvider> workers;
    private final Semaphore permits;
    /** 链路事件记录器（Task 4 插桩：offline_pool；telemetry 禁用时是 Noop）。 */
    private final TelemetryRecorder recorder;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * D08a 每 worker 一个许可 + 健康状态:许可绑定实际执行生命周期——调用方超时只终结
     * 返回 Future,执行未结束则不归还许可;连续失败达到阈值熔断该 worker,冷却后半开探测。
     * 这样"引擎卡死"表现为该 worker 明确不可用,而不是把任务继续排进同一个死引擎。
     */
    private final List<Semaphore> workerPermits;
    private final List<EngineHealth> workerHealth;

    /** D08a 参数:连续失败阈值与熔断冷却时长(毫秒)。 */
    static final int FAILURE_THRESHOLD = 3;
    static final long COOLDOWN_MS = 60_000;
    static final long DEFAULT_ENGINE_TIMEOUT_MS = 30_000;

    private final long engineTimeoutMs;

    /**
     * @param recorder 链路事件记录器（Task 4 起）。池降级事件按调用方透传的 utteranceId 记录
     *                 （时间线"离线池"阶段依赖此贯通）。
     */
    public OfflineEnginePool(List<OfflineCommandProvider> workers, TelemetryRecorder recorder) {
        this(workers, recorder, DEFAULT_ENGINE_TIMEOUT_MS);
    }

    /**
     * @param engineTimeoutMs D08a 引擎监督超时:worker 在该期限内未返回即视为该引擎失败
     *                        (调用方拿到空结果,不阻塞;连续失败触发熔断)
     */
    public OfflineEnginePool(List<OfflineCommandProvider> workers, TelemetryRecorder recorder,
                             long engineTimeoutMs) {
        this.engineTimeoutMs = Math.max(1, engineTimeoutMs);
        if (workers == null || workers.isEmpty()) {
            throw new IllegalArgumentException("offline engine pool requires at least one worker");
        }
        this.workers = List.copyOf(workers);
        this.permits = new Semaphore(workers.size());
        this.recorder = recorder;
        this.workerPermits = new java.util.ArrayList<>(workers.size());
        this.workerHealth = new java.util.ArrayList<>(workers.size());
        for (int i = 0; i < workers.size(); i++) {
            workerPermits.add(new Semaphore(1));
            workerHealth.add(new EngineHealth(FAILURE_THRESHOLD, COOLDOWN_MS, System::currentTimeMillis));
        }
    }

    @Override
    public CompletableFuture<Optional<String>> recognize(byte[] pcm16k, SessionContext ctx) {
        // 旧入口（无 utteranceId）：降级事件无从归属，交由 record(null, …) 丢弃，不产生幽灵 round
        return recognize(pcm16k, ctx, null);
    }

    @Override
    public CompletableFuture<Optional<String>> recognize(byte[] pcm16k, SessionContext ctx,
                                                         String utteranceId) {
        if (closed.get()) return CompletableFuture.completedFuture(Optional.empty());
        if (!permits.tryAcquire()) {
            LOG.info("offline engine busy, skip (pool={}, session={})", workers.size(), ctx.sessionId());
            recorder.record(utteranceId, TelemetryStages.OFFLINE_POOL, "warn",
                    Map.of("reason", "busy", "poolSize", workers.size()));
            return CompletableFuture.completedFuture(Optional.empty());
        }
        int index;
        try {
            index = Math.floorMod(ctx.sessionId().hashCode(), workers.size());
        } catch (RuntimeException e) {
            permits.release();
            LOG.warn("offline engine pool routing failed, skip: {}", String.valueOf(e.getMessage()));
            recorder.record(utteranceId, TelemetryStages.OFFLINE_POOL, "warn",
                    Map.of("reason", "routing_failed", "error", String.valueOf(e.getMessage()),
                            "poolSize", workers.size()));
            return CompletableFuture.completedFuture(Optional.empty());
        }
        // D08a:该 worker 的许可绑定执行生命周期;熔断中或执行未结束 → 明确降级
        if (!workerHealth.get(index).tryAcquire() || !workerPermits.get(index).tryAcquire()) {
            permits.release();
            LOG.info("offline engine worker {} unavailable, degrade (health={})",
                    index, workerHealth.get(index).consecutiveFailures());
            recorder.record(utteranceId, TelemetryStages.OFFLINE_POOL, "warn",
                    Map.of("reason", "engine_unavailable", "worker", index,
                            "consecutiveFailures", workerHealth.get(index).consecutiveFailures()));
            return CompletableFuture.completedFuture(Optional.empty());
        }
        OfflineCommandProvider worker = workers.get(index);
        CompletableFuture<Optional<String>> future;
        try {
            future = worker.recognize(pcm16k, ctx, utteranceId);
        } catch (RuntimeException e) {
            permits.release();
            workerPermits.get(index).release();
            workerHealth.get(index).onFailure();
            LOG.warn("offline engine worker rejected recognize, skip: {}", String.valueOf(e.getMessage()));
            recorder.record(utteranceId, TelemetryStages.OFFLINE_POOL, "warn",
                    Map.of("reason", "worker_rejected", "error", String.valueOf(e.getMessage()),
                            "poolSize", workers.size()));
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return future.orTimeout(engineTimeoutMs, TimeUnit.MILLISECONDS).handle((result, err) -> {
            permits.release();
            if (err != null) {
                // D08a:异常(含 native 超时兜底)记为该 worker 的失败;连续失败触发熔断
                workerHealth.get(index).onFailure();
                workerPermits.get(index).release();
                LOG.warn("offline engine worker {} failed, skip: {}", index, String.valueOf(err.getMessage()));
                recorder.record(utteranceId, TelemetryStages.OFFLINE_POOL, "warn",
                        Map.of("reason", "worker_failed", "error", String.valueOf(err.getMessage()),
                                "worker", index,
                                "consecutiveFailures", workerHealth.get(index).consecutiveFailures()));
                return Optional.<String>empty();
            }
            workerHealth.get(index).onSuccess();
            workerPermits.get(index).release();
            return result == null ? Optional.<String>empty() : result;
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (OfflineCommandProvider worker : workers) {
            if (worker instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception error) {
                    LOG.warn("offline engine worker close failed: {}", String.valueOf(error.getMessage()));
                }
            }
        }
    }
}

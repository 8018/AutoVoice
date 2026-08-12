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
public final class OfflineEnginePool implements OfflineCommandProvider {

    private static final Logger LOG = LoggerFactory.getLogger(OfflineEnginePool.class);

    private final List<OfflineCommandProvider> workers;
    private final Semaphore permits;
    /** 链路事件记录器（Task 4 插桩：offline_pool；telemetry 禁用时是 Noop）。 */
    private final TelemetryRecorder recorder;

    /**
     * @param recorder 链路事件记录器（Task 4 起）。池事件以 sessionId 关联——utteranceId 不在
     *                 OfflineCommandProvider 接口内（plan 已声明的取舍，二期再精确化）。
     */
    public OfflineEnginePool(List<OfflineCommandProvider> workers, TelemetryRecorder recorder) {
        if (workers == null || workers.isEmpty()) {
            throw new IllegalArgumentException("offline engine pool requires at least one worker");
        }
        this.workers = List.copyOf(workers);
        this.permits = new Semaphore(workers.size());
        this.recorder = recorder;
    }

    @Override
    public CompletableFuture<Optional<String>> recognize(byte[] pcm16k, SessionContext ctx) {
        if (!permits.tryAcquire()) {
            LOG.info("offline engine busy, skip (pool={}, session={})", workers.size(), ctx.sessionId());
            recorder.record(ctx.sessionId(), TelemetryStages.OFFLINE_POOL, "warn",
                    Map.of("reason", "busy", "poolSize", workers.size()));
            return CompletableFuture.completedFuture(Optional.empty());
        }
        OfflineCommandProvider worker;
        try {
            worker = workers.get(Math.floorMod(ctx.sessionId().hashCode(), workers.size()));
        } catch (RuntimeException e) {
            permits.release();
            LOG.warn("offline engine pool routing failed, skip: {}", String.valueOf(e.getMessage()));
            recorder.record(ctx.sessionId(), TelemetryStages.OFFLINE_POOL, "warn",
                    Map.of("reason", "routing_failed", "error", String.valueOf(e.getMessage()),
                            "poolSize", workers.size()));
            return CompletableFuture.completedFuture(Optional.empty());
        }
        CompletableFuture<Optional<String>> future;
        try {
            future = worker.recognize(pcm16k, ctx);
        } catch (RuntimeException e) {
            permits.release();
            LOG.warn("offline engine worker rejected recognize, skip: {}", String.valueOf(e.getMessage()));
            recorder.record(ctx.sessionId(), TelemetryStages.OFFLINE_POOL, "warn",
                    Map.of("reason", "worker_rejected", "error", String.valueOf(e.getMessage()),
                            "poolSize", workers.size()));
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return future.handle((result, err) -> {
            permits.release();
            if (err != null) {
                LOG.warn("offline engine worker failed, skip: {}", String.valueOf(err.getMessage()));
                recorder.record(ctx.sessionId(), TelemetryStages.OFFLINE_POOL, "warn",
                        Map.of("reason", "worker_failed", "error", String.valueOf(err.getMessage()),
                                "poolSize", workers.size()));
                return Optional.<String>empty();
            }
            return result == null ? Optional.<String>empty() : result;
        });
    }
}

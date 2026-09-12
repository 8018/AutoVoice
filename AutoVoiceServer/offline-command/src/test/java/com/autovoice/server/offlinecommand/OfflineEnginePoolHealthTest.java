package com.autovoice.server.offlinecommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autovoice.server.contracts.OfflineCommandProvider;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.telemetry.NoopTelemetryRecorder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** D08a 池级健康:监督超时终结调用方等待、卡死引擎熔断降级、健康 worker 不受影响。 */
class OfflineEnginePoolHealthTest {

    private static final long SUPERVISION_MS = 150;

    private static SessionContext ctx(String session) {
        return new SessionContext(session, "zh-CN", Map.of());
    }

    private static OfflineEnginePool pool(List<OfflineCommandProvider> workers) {
        return new OfflineEnginePool(workers, NoopTelemetryRecorder.INSTANCE, SUPERVISION_MS);
    }

    @Test
    void stuckEngineDoesNotBlockCallerAndBreaksCircuit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OfflineCommandProvider stuck = (pcm, context) -> {
            calls.incrementAndGet();
            return new CompletableFuture<>(); // native 调用挂起,永不完成
        };
        OfflineEnginePool pool = pool(List.of(stuck));
        try {
            // 监督超时:调用方在超时内拿到空结果,不被永久阻塞
            for (int i = 0; i < OfflineEnginePool.FAILURE_THRESHOLD; i++) {
                long started = System.nanoTime();
                assertTrue(pool.recognize(new byte[]{1}, ctx("s"), "u" + i)
                        .get(2, TimeUnit.SECONDS).isEmpty());
                long elapsedMs = (System.nanoTime() - started) / 1_000_000;
                assertTrue(elapsedMs < 2_000, "调用方等待不得超过监督超时的数量级");
            }
            assertEquals(OfflineEnginePool.FAILURE_THRESHOLD, calls.get());

            // 熔断:冷却期内不再把任务排进卡死引擎(快速降级)
            long started = System.nanoTime();
            assertTrue(pool.recognize(new byte[]{1}, ctx("s"), "u-late")
                    .get(2, TimeUnit.SECONDS).isEmpty());
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            assertTrue(elapsedMs < SUPERVISION_MS, "熔断后应立即降级,不再等待引擎超时");
            assertEquals(OfflineEnginePool.FAILURE_THRESHOLD, calls.get(), "熔断期不得再调用卡死引擎");
        } finally {
            pool.close();
        }
    }

    @Test
    void healthyWorkerKeepsServingWhileAnotherEngineIsStuck() throws Exception {
        OfflineCommandProvider stuck = (pcm, context) -> new CompletableFuture<>();
        OfflineCommandProvider healthy = (pcm, context) ->
                CompletableFuture.completedFuture(Optional.of("打开空调"));
        OfflineEnginePool pool = pool(List.of(stuck, healthy));
        try {
            String stuckSession = sessionRoutingTo(0, 2);
            String healthySession = sessionRoutingTo(1, 2);
            for (int i = 0; i < OfflineEnginePool.FAILURE_THRESHOLD; i++) {
                pool.recognize(new byte[]{1}, ctx(stuckSession), "u" + i).get(2, TimeUnit.SECONDS);
            }
            // 卡死 worker 已熔断,健康 worker 仍正常返回
            assertEquals(Optional.of("打开空调"),
                    pool.recognize(new byte[]{1}, ctx(healthySession), "u-ok")
                            .get(2, TimeUnit.SECONDS));
        } finally {
            pool.close();
        }
    }

    private static String sessionRoutingTo(int index, int poolSize) {
        for (int i = 0; i < 10_000; i++) {
            String candidate = "session-" + i;
            if (Math.floorMod(candidate.hashCode(), poolSize) == index) {
                return candidate;
            }
        }
        throw new IllegalStateException("no session routes to worker " + index);
    }
}

package com.autovoice.server.offlinecommand;

import com.autovoice.server.contracts.OfflineCommandProvider;
import com.autovoice.server.contracts.SessionContext;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 引擎池行为（fake workers，不触 .so）：sticky 路由 / 超载快速失败 / 释放复用 / 异常空结果。 */
class OfflineEnginePoolTest {

    private static SessionContext ctx(String sessionId) {
        return new SessionContext(sessionId, null, null);
    }

    /** 记录调用次数并返回命中文本的 worker；block 非空时模拟慢识别（占用 worker 至闩放行）。 */
    private static OfflineCommandProvider fake(String tag, AtomicInteger calls, CountDownLatch block) {
        return (pcm, sctx) -> CompletableFuture.supplyAsync(() -> {
            calls.incrementAndGet();
            if (block != null) {
                try {
                    block.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.<String>empty();
                }
            }
            return Optional.of("hit-" + tag + "-" + sctx.sessionId());
        });
    }

    private static OfflineCommandProvider exploding() {
        return (pcm, sctx) -> CompletableFuture.failedFuture(new RuntimeException("engine exploded"));
    }

    private static String hit(String tag, String sessionId) {
        return "hit-" + tag + "-" + sessionId;
    }

    @Test
    void sameSessionIdAlwaysRoutesToSameWorker() {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        OfflineEnginePool pool = new OfflineEnginePool(List.of(fake("a", a, null), fake("b", b, null)));
        SessionContext sid = ctx("device-A-42");
        String expectedTag = Math.floorMod(sid.sessionId().hashCode(), 2) == 0 ? "a" : "b";

        for (int i = 0; i < 4; i++) {
            assertEquals(hit(expectedTag, "device-A-42"),
                    pool.recognize(new byte[16], sid).join().orElse(""));
        }
        assertEquals(4, expectedTag.equals("a") ? a.get() : b.get(), "sticky 会话只应命中同一 worker");
        assertEquals(0, expectedTag.equals("a") ? b.get() : a.get(), "另一 worker 不应被触达");
    }

    @Test
    void poolExhaustedFailsFastWithEmpty() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        OfflineEnginePool pool = new OfflineEnginePool(List.of(
                fake("a", calls, release), fake("b", calls, release)));
        // 与 sticky 无关：两个 worker 各被占住（permits 同步获取，先于 worker 异步执行）
        CompletableFuture<Optional<String>> f1 = pool.recognize(new byte[16], ctx("session-one"));
        CompletableFuture<Optional<String>> f2 = pool.recognize(new byte[16], ctx("session-two"));

        // 第 3 个并发识别：池满 → 立即空结果（快速失败，不排队不阻塞）
        CompletableFuture<Optional<String>> f3 = pool.recognize(new byte[16], ctx("session-three"));
        assertTrue(f3.isDone(), "池满应同步返回已完成的空结果");
        assertEquals(Optional.empty(), f3.join());

        release.countDown(); // 放行两个慢识别
        assertFalse(f1.join().isEmpty(), "占用期内的识别最终应完成");
        assertFalse(f2.join().isEmpty());
        assertEquals(2, calls.get(), "超载话语不应进入任何 worker");
    }

    @Test
    void permitReleasedAfterCompletionAllowsReuse() {
        OfflineEnginePool pool = new OfflineEnginePool(List.of(fake("a", new AtomicInteger(), null)));
        assertFalse(pool.recognize(new byte[16], ctx("session-one")).join().isEmpty());
        // join 返回即 handle 回调已执行（释放许可证）→ 第二次识别可再获许可
        assertFalse(pool.recognize(new byte[16], ctx("session-two")).join().isEmpty(),
                "完成后的许可证应被释放并可复用");
    }

    @Test
    void failingWorkerYieldsEmptyAndReleasesPermit() {
        OfflineEnginePool pool = new OfflineEnginePool(List.of(
                exploding(), fake("a", new AtomicInteger(), null)));
        // 选一个 sticky 命中 exploding（index 0）的 sessionId
        String badSession = findSessionMappingTo(0, "bad-");
        CompletableFuture<Optional<String>> f1 = pool.recognize(new byte[16], ctx(badSession));
        assertEquals(Optional.empty(), f1.join(), "worker 异常 → 空结果（不崩、不抛）");
        assertFalse(f1.isCompletedExceptionally(), "池应吞掉异常，返回已完成空结果");
        assertFalse(pool.recognize(new byte[16], ctx("session-two")).join().isEmpty(),
                "异常 worker 释放许可后池仍可服务其他会话");
    }

    /** 找一个 hashCode floorMod 命中指定 worker index 的 sessionId。 */
    private static String findSessionMappingTo(int workerIndex, String prefix) {
        String sid = prefix;
        while (Math.floorMod(sid.hashCode(), 2) != workerIndex) {
            sid += "x";
        }
        return sid;
    }
}

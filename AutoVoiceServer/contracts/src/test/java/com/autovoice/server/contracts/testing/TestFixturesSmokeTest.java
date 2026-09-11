package com.autovoice.server.contracts.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** D01b 测试夹具自检:夹具本身必须行为确定,后续工作包才敢依赖它们复现竞态。 */
class TestFixturesSmokeTest {

    @Test
    void testClockAdvancesDeterministically() {
        TestClock clock = new TestClock(1_000L);
        assertEquals(1_000L, clock.getAsLong());
        clock.advance(500L);
        assertEquals(1_500L, clock.getAsLong());
        clock.set(0L);
        assertEquals(0L, clock.now());
    }

    @Test
    void futureGateCompletesInChosenOrder() {
        FutureGate<String> gate = new FutureGate<>();
        CompletableFuture<String> first = gate.newFuture();
        CompletableFuture<String> second = gate.newFuture();
        gate.complete(1, "second-wins");
        assertEquals("second-wins", second.join());
        assertFalse(first.isDone());
        gate.fail(0, new IllegalStateException("cut"));
        assertTrue(first.isCompletedExceptionally());
    }

    @Test
    void fakeUpstreamDeliversThenDisconnects() {
        FakeUpstream<String> upstream = new FakeUpstream<>();
        StringBuilder received = new StringBuilder();
        Throwable[] cut = new Throwable[1];
        upstream.subscribe(item -> received.append(item), cause -> cut[0] = cause);
        upstream.emit("a");
        upstream.emit("b");
        IllegalStateException failure = new IllegalStateException("断连");
        upstream.disconnect(failure);
        upstream.emit("c"); // 断连后丢弃
        assertEquals("ab", received.toString());
        assertEquals(failure, cut[0]);
        assertTrue(upstream.isClosed());
    }

    @Test
    void slowDrainBackpressuresUntilManuallyDrained() {
        SlowDrain<Integer> drain = new SlowDrain<>();
        drain.accept(1);
        drain.accept(2);
        assertEquals(2, drain.pending());
        assertEquals(1, drain.drainOne());
        assertEquals(1, drain.pending());
        assertEquals(2, drain.drainOne());
        assertNull(drain.drainOne());
    }
}

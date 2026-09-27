package com.autovoice.server.arbitration;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reproduces the handoff between two drains without relying on a sleep-based race. */
class RaceArbiterDrainTest {
    @Test
    void releasedConsumerCannotClearTheNewConsumerOwnership() throws Exception {
        var scheduler = Executors.newScheduledThreadPool(2);
        var arbiter = new RaceArbiter(60_000, scheduler, entry -> {});
        var afterRelease = new CountDownLatch(1);
        var resumeFirst = new CountDownLatch(1);
        var secondRunning = new CountDownLatch(1);
        var resumeSecond = new CountDownLatch(1);
        var secondDone = new CountDownLatch(1);
        var firstCheck = new AtomicBoolean();
        var messages = new ArrayBlockingQueue<Runnable>(1024) {
            @Override public boolean isEmpty() {
                if (Thread.currentThread().getName().equals("first-drain") &&
                        firstCheck.compareAndSet(false, true)) {
                    afterRelease.countDown();
                    await(resumeFirst);
                }
                return super.isEmpty();
            }
        };
        Field queueField = RaceArbiter.class.getDeclaredField("messages");
        queueField.setAccessible(true);
        queueField.set(arbiter, messages);
        Field flagField = RaceArbiter.class.getDeclaredField("draining");
        flagField.setAccessible(true);
        AtomicBoolean draining = (AtomicBoolean) flagField.get(arbiter);
        Method drain = RaceArbiter.class.getDeclaredMethod("drainMessages");
        drain.setAccessible(true);
        Runnable runDrain = () -> {
            try { drain.invoke(arbiter); }
            catch (Exception failure) { throw new AssertionError(failure); }
        };
        try {
            draining.set(true);
            var first = new Thread(runDrain, "first-drain");
            first.start();
            await(afterRelease);
            messages.offer(() -> {
                secondRunning.countDown();
                await(resumeSecond);
                secondDone.countDown();
            });
            assertTrue(draining.compareAndSet(false, true));
            scheduler.execute(runDrain);
            await(secondRunning);
            resumeFirst.countDown();
            first.join(3_000);
            assertFalse(first.isAlive());
            assertTrue(draining.get(), "the second consumer still owns the drain flag");
            var thirdRan = new AtomicBoolean();
            messages.offer(() -> thirdRan.set(true));
            assertFalse(draining.compareAndSet(false, true), "a third consumer must not start");
            resumeSecond.countDown();
            await(secondDone);
            scheduler.shutdown();
            assertTrue(scheduler.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(thirdRan.get());
        } finally {
            resumeFirst.countDown();
            resumeSecond.countDown();
            scheduler.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}

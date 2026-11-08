package com.autovoice.adapteriflytek

import com.autovoice.voicecore.health.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IflytekRuntimeInitializationTest {
    private fun reporter(events: MutableList<ComponentStatusEvent>) = ComponentReporter(
        ComponentDescriptor("iflytek.runtime", "aikit", "shared", ComponentScope.PROCESS), 0, ComponentStatusSink(events::add))

    @Test
    fun `timeout late auth cannot revive singleton or start another SDK initialization`() {
        val events = mutableListOf<ComponentStatusEvent>()
        val runtime = IflytekRuntimeInitialization(awaitResult = { _, _ -> false })
        runtime.bindReporter(reporter(events))
        var auth: ((Int) -> Unit)? = null
        var starts = 0
        assertThrows(RuntimeInitializationException::class.java) {
            runtime.ensure("credentials-fingerprint", credentialsPresent = true) { callback, _ -> starts++;auth = callback }
        }
        auth!!(0)
        assertThrows(RuntimeInitializationException::class.java) {
            runtime.ensure("credentials-fingerprint", credentialsPresent = true) { _, _ -> starts++ }
        }
        assertEquals(1, starts)
        assertEquals(ErrorCode.INIT_TIMEOUT, events.last().update.error!!.code)
        assertEquals(Lifecycle.FAILED, events.last().update.lifecycle)
    }

    @Test
    fun `shared successful runtime starts once and replays status without reauthorizing`() {
        val events = mutableListOf<ComponentStatusEvent>()
        val runtime = IflytekRuntimeInitialization()
        runtime.bindReporter(reporter(events))
        var starts = 0
        repeat(2) { runtime.ensure("same-fingerprint", true) { callback, _ -> starts++;callback(0) } }
        val replay = mutableListOf<ComponentStatusEvent>()
        runtime.bindReporter(reporter(replay))
        assertEquals(1, starts)
        assertEquals(Lifecycle.READY, replay.last().update.lifecycle)
        assertEquals(1, events.count { it.update.lifecycle == Lifecycle.READY })
    }

    @Test
    fun `concurrent callers share one actual initialization owner`() {
        val runtime = IflytekRuntimeInitialization()
        val started = java.util.concurrent.CountDownLatch(1)
        val callbacks = java.util.concurrent.atomic.AtomicReference<((Int) -> Unit)?>()
        val count = java.util.concurrent.atomic.AtomicInteger()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit { runtime.ensure("same", true) { callback, _ -> count.incrementAndGet();callbacks.set(callback);started.countDown() } }
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val second = pool.submit { runtime.ensure("same", true) { _, _ -> count.incrementAndGet() } }
            callbacks.get()!!(0)
            first.get(2, java.util.concurrent.TimeUnit.SECONDS);second.get(2, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(1, count.get())
        } finally { callbacks.get()?.invoke(0);pool.shutdownNow() }
    }

    @Test
    fun `post ready auth loss changes health and can recover without repeating initialization`() {
        val events = mutableListOf<ComponentStatusEvent>()
        val runtime = IflytekRuntimeInitialization()
        runtime.bindReporter(reporter(events))
        var auth: ((Int) -> Unit)? = null
        var starts = 0
        runtime.ensure("same", true) { callback, _ -> starts++;auth = callback;callback(0) }
        auth!!(103)
        assertNull(events.last().update.lifecycle)
        assertEquals(Health.UNAVAILABLE, events.last().update.health)
        assertThrows(RuntimeInitializationException::class.java) { runtime.ensure("same", true) { _, _ -> starts++ } }
        auth!!(0)
        runtime.ensure("same", true) { _, _ -> starts++ }
        assertEquals(Health.HEALTHY, events.last().update.health)
        assertEquals(1, starts)
    }

    @Test
    fun `credentials and vendor auth failures have one stable process root cause`() {
        val events = mutableListOf<ComponentStatusEvent>()
        val runtime = IflytekRuntimeInitialization()
        runtime.bindReporter(reporter(events))
        assertThrows(RuntimeInitializationException::class.java) { runtime.ensure("", false) { _, _ -> error("must not start") } }
        assertEquals(ErrorCode.SDK_CREDENTIALS_MISSING, events.last().update.error!!.code)
        val authEvents = mutableListOf<ComponentStatusEvent>()
        val denied = IflytekRuntimeInitialization()
        denied.bindReporter(reporter(authEvents))
        assertThrows(RuntimeInitializationException::class.java) { denied.ensure("safe-fingerprint", true) { callback, _ -> callback(101) } }
        assertEquals(ErrorCode.SDK_AUTH_FAILED, authEvents.last().update.error!!.code)
        assertEquals(101, authEvents.last().update.error!!.vendorCode)
    }
}

package com.autovoice.voicecore.health

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ComponentReporterTest {
    @Test
    fun `attempt terminal failure and release reject late success`() {
        val events = mutableListOf<ComponentStatusEvent>()
        val reporter = ComponentReporter(ComponentDescriptor("local.asr", "firered.ctc.int8", "open"), 1, ComponentStatusSink(events::add))
        val first = reporter.start("model_create")
        assertTrue(reporter.report(first, ComponentUpdate.failed(ComponentError(ErrorCode.INIT_TIMEOUT))))
        assertFalse(reporter.report(first, ComponentUpdate.ready()))
        val second = reporter.start("model_create")
        assertFalse(reporter.report(first, ComponentUpdate.ready()))
        assertTrue(reporter.report(second, ComponentUpdate.ready()))
        reporter.close()
        assertFalse(reporter.report(second, ComponentUpdate.ready()))
        assertEquals(listOf(Lifecycle.INITIALIZING, Lifecycle.FAILED, Lifecycle.INITIALIZING, Lifecycle.READY, Lifecycle.RELEASED), events.map { it.update.lifecycle })
    }

    @Test
    fun `old owner close cannot release a replacement attempt`() {
        val events = mutableListOf<ComponentStatusEvent>()
        val reporter = ComponentReporter(ComponentDescriptor("vad", "silero", "iflytek"), 1, ComponentStatusSink(events::add))
        val old = reporter.start("model_create");reporter.report(old, ComponentUpdate.ready())
        val replacement = reporter.start("model_create");reporter.report(replacement, ComponentUpdate.ready())
        assertFalse(reporter.close(old))
        assertTrue(reporter.report(replacement, ComponentUpdate.health(Health.HEALTHY)))
        assertTrue(reporter.close(replacement))
    }

    @Test
    fun `initialization duration uses monotonic clock and health stays independent`() {
        val events = mutableListOf<ComponentStatusEvent>()
        var mono = 10L
        var wall = 1000L
        val reporter = ComponentReporter(ComponentDescriptor("frontend", "rnnoise", "iflytek"), 1,
            ComponentStatusSink(events::add), monotonicMs = { mono }, wallClockMs = { wall })
        val token = reporter.start("native_load")
        mono = 35;wall = -5000
        reporter.report(token, ComponentUpdate.ready())
        mono = 100
        reporter.report(token, ComponentUpdate.health(Health.DEGRADED, ComponentError(ErrorCode.COMPONENT_RUNTIME_FAILED)))
        assertEquals(25L, events.last().durationMs)
        assertNull(events.last().update.lifecycle)
        assertEquals(Health.DEGRADED, events.last().update.health)
        assertEquals(25L, events[1].durationMs)
    }
}

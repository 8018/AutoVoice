package com.autovoice.app.health

import com.autovoice.voicecore.health.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ComponentStatusRegistryTest {
    @Test
    fun `not started disabled and failed dependencies are distinct`() {
        val registry = ComponentStatusRegistry()
        val generation = registry.beginGeneration("open")
        val runtime = registry.reporter(ComponentDescriptor("iflytek.runtime", "aikit", "shared", ComponentScope.PROCESS), generation, emptySet())
        val wake = registry.reporter(ComponentDescriptor("wake", "iflytek.ivw", "open"), generation, setOf("iflytek.runtime"))
        val asr = registry.reporter(ComponentDescriptor("local.asr", "firered.ctc.int8", "open"), generation, emptySet())
        assertEquals(Lifecycle.NOT_STARTED, registry.snapshot.value.components.getValue("local.asr").lifecycle)
        val rt = runtime.start("authorize")
        runtime.report(rt, ComponentUpdate.failed(ComponentError(ErrorCode.SDK_AUTH_FAILED, message = "secret-token", vendorCode = 19)))
        val ready = asr.start("model_create");asr.report(ready, ComponentUpdate.ready())
        assertEquals(Lifecycle.READY, registry.snapshot.value.components.getValue("local.asr").lifecycle)
        val blocked = registry.snapshot.value.components.getValue("wake")
        assertEquals(Lifecycle.BLOCKED, blocked.lifecycle)
        assertEquals("iflytek.runtime", blocked.rootCauseId)
        assertNull(blocked.error)
        assertEquals(1, registry.snapshot.value.components.values.count { it.lifecycle == Lifecycle.FAILED })
        val cloud = registry.reporter(ComponentDescriptor("cloud", "gateway", "open"), generation, emptySet())
        cloud.report(cloud.start("connect"), ComponentUpdate.disabled())
        assertEquals(Lifecycle.DISABLED, registry.snapshot.value.components.getValue("cloud").lifecycle)
        wake.close()
    }

    @Test
    fun `process result survives generation while stale sequence and assembly attempts are ignored`() {
        val registry = ComponentStatusRegistry()
        val first = registry.beginGeneration("iflytek")
        val process = registry.reporter(ComponentDescriptor("iflytek.runtime", "aikit", "shared", ComponentScope.PROCESS), first, emptySet())
        process.report(process.start("authorize"), ComponentUpdate.ready())
        val frontend = registry.reporter(ComponentDescriptor("frontend", "rnnoise", "iflytek"), first, emptySet())
        val old = frontend.start("native_load")
        val second = registry.beginGeneration("iflytek")
        val replacement = registry.reporter(ComponentDescriptor("frontend", "rnnoise", "iflytek"), second, emptySet())
        assertSame(process, registry.reporter(ComponentDescriptor("iflytek.runtime", "aikit", "shared", ComponentScope.PROCESS), second, emptySet()))
        frontend.report(old, ComponentUpdate.ready())
        assertEquals(Lifecycle.NOT_STARTED, registry.snapshot.value.components.getValue("frontend").lifecycle)
        replacement.report(replacement.start("native_load"), ComponentUpdate.ready())
        val event = registry.snapshot.value.recentEvents.last()
        registry.report(event.copy(sequence = event.sequence - 1, update = ComponentUpdate.failed(ComponentError(ErrorCode.MODEL_MISSING))))
        assertEquals(Lifecycle.READY, registry.snapshot.value.components.getValue("frontend").lifecycle)
        assertEquals(Lifecycle.READY, registry.snapshot.value.components.getValue("iflytek.runtime").lifecycle)
    }

    @Test
    fun `health repeats merge history stays bounded and recovery clears banner`() {
        val registry = ComponentStatusRegistry()
        val generation = registry.beginGeneration("iflytek")
        val reporter = registry.reporter(ComponentDescriptor("local.asr", "iflytek.offline", "iflytek"), generation, emptySet())
        val token = reporter.start("initialize");reporter.report(token, ComponentUpdate.ready())
        reporter.report(token, ComponentUpdate.health(Health.UNAVAILABLE, ComponentError(ErrorCode.COMPONENT_RUNTIME_FAILED)))
        assertTrue(CapabilityEvaluator.evaluate(registry.snapshot.value, online = false).summary.contains("本地识别"))
        repeat(20) { reporter.report(token, ComponentUpdate.health(Health.UNAVAILABLE, ComponentError(ErrorCode.COMPONENT_RUNTIME_FAILED))) }
        assertTrue(registry.snapshot.value.mergedHealthEvents >= 20)
        reporter.report(token, ComponentUpdate.health(Health.HEALTHY))
        assertFalse(CapabilityEvaluator.evaluate(registry.snapshot.value, online = false).summary.contains("本地识别暂不可用"))
        repeat(150) { reporter.report(token, ComponentUpdate.health(if (it % 2 == 0) Health.DEGRADED else Health.HEALTHY)) }
        assertTrue(registry.snapshot.value.recentEvents.size <= 128)
    }

    @Test
    fun `structured diagnostics redact arbitrary exceptions secrets urls and transcripts`() {
        val registry = ComponentStatusRegistry()
        val generation = registry.beginGeneration("open")
        val r = registry.reporter(ComponentDescriptor("local.asr", "firered.ctc.int8", "open"), generation, emptySet())
        r.report(r.start("model_create"), ComponentUpdate.failed(ComponentError(ErrorCode.MODEL_LOAD_FAILED,
            message = "secret-api-key authToken=secret-token https://auth/private?token=secret-token 打开我的私人车库",
            suggestion = "/Users/private/model.onnx")))
        val json = ComponentLogSink.diagnosticsJson(registry.snapshot.value)
        for (value in listOf("secret-api-key", "secret-token", "auth/private", "私人车库", "/Users/private")) assertFalse(json.contains(value), json)
        assertTrue(json.contains("MODEL_LOAD_FAILED"))
        assertFalse(json.contains("utteranceId"))
    }
}

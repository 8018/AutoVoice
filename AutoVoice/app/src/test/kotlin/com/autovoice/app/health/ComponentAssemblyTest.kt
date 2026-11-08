package com.autovoice.app.health

import com.autovoice.voicecore.health.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ComponentAssemblyTest {
    @Test
    fun `earliest invalid config remains observable without constructing any adapter`() {
        val registry = ComponentStatusRegistry()
        val reporting = ChannelComponentReporting(registry, "open", registry.beginGeneration("open"))
        reporting.registerDefaults()
        var adapterCreated = false
        val config = reporting.readConfig { throw IllegalArgumentException("secret-config-token") }
        if (config != null) adapterCreated = true
        assertNull(config)
        assertFalse(adapterCreated)
        assertEquals(ErrorCode.CONFIG_INVALID, registry.snapshot.value.components.getValue("config").error!!.code)
        val projection = CapabilityEvaluator.evaluate(registry.snapshot.value, false)
        assertFalse(projection.configUsable)
        assertFalse(projection.canTriggerManual)
        assertTrue(ComponentLogSink.diagnosticsJson(registry.snapshot.value).contains("CONFIG_INVALID"))
    }

    @Test
    fun `explicit text availability follows configured mode and remains independent of audio failure`() {
        val registry = ComponentStatusRegistry()
        val h = ChannelComponentReporting(registry, "open", registry.beginGeneration("open"))
        h.registerDefaults()
        val config = h.reporter("config");config.report(config.start("config_validate"), ComponentUpdate.ready())
        h.unavailable("frontend", ErrorCode.NATIVE_LIBRARY_MISSING)
        assertEquals(CapabilityAvailability.DISABLED, CapabilityEvaluator.evaluate(registry.snapshot.value, false).features["text"])
        assertEquals(CapabilityAvailability.PENDING, CapabilityEvaluator.evaluate(registry.snapshot.value, true).features["text"])
    }

    @Test
    fun `pending lazy components allow the first valid manual trigger`() {
        val registry = ComponentStatusRegistry()
        val reporting = ChannelComponentReporting(registry, "iflytek", registry.beginGeneration("iflytek"))
        reporting.registerDefaults()
        val config = reporting.reporter("config")
        config.report(config.start("config_validate"), ComponentUpdate.ready())
        val projected = CapabilityEvaluator.evaluate(registry.snapshot.value, false)
        assertTrue(projected.canTriggerManual)
        assertEquals(CapabilityAvailability.PENDING, projected.features["local"])
    }
    @Test fun `cloud failure before first handshake is unavailable and recovery preserves audio independence`() {
        val registry = ComponentStatusRegistry()
        val h = ChannelComponentReporting(registry, "open", registry.beginGeneration("open"));h.registerDefaults()
        val config = h.reporter("config");config.report(config.start("config_validate"), ComponentUpdate.ready())
        val cloud = h.reporter("cloud");val token = cloud.start("connect")
        assertEquals(CapabilityAvailability.PENDING, CapabilityEvaluator.evaluate(registry.snapshot.value, true).features["text"])
        cloud.report(token, ComponentUpdate.health(Health.UNAVAILABLE, ComponentError(ErrorCode.COMPONENT_RUNTIME_FAILED)))
        assertEquals(CapabilityAvailability.UNAVAILABLE, CapabilityEvaluator.evaluate(registry.snapshot.value, true).features["text"])
        assertTrue(CapabilityEvaluator.evaluate(registry.snapshot.value, true).canTriggerManual)
        cloud.report(token, ComponentUpdate.ready())
        assertEquals(CapabilityAvailability.AVAILABLE, CapabilityEvaluator.evaluate(registry.snapshot.value, true).features["text"])
    }

}

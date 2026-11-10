package com.autovoice.app.health

import com.autovoice.voicecore.health.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ComponentAssemblyTest {
    @Test fun `bound auxiliary routes honor sticky capture failure while text and wake remain independent`() {
        val registry = ComponentStatusRegistry();val reporting = ChannelComponentReporting(registry,"open",registry.beginGeneration("open"))
        reporting.registerDefaults()
        for (id in listOf("config","iflytek.runtime","frontend","local.asr","local.nlu","vad.aux","aec","wake","cloud")) {
            val r = reporting.reporter(id);r.report(r.start("fixture"),ComponentUpdate.ready())
        }
        val p = CapabilityEvaluator.evaluate(registry.snapshot.value,true,capturePathUnavailable = true,openMicDeviceValidated = false)
        assertEquals(CapabilityAvailability.UNAVAILABLE,p.features["follow_up"])
        assertEquals(CapabilityAvailability.UNAVAILABLE,p.features["barge_in"])
        assertEquals(CapabilityAvailability.AVAILABLE,p.features["wake"])
        assertEquals(CapabilityAvailability.AVAILABLE,p.features["text"])
    }
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

    @Test fun `online mode without configured text route disables text action while enabled route still allows actual retry`() {
        val registry = ComponentStatusRegistry()
        val h = ChannelComponentReporting(registry,"open",registry.beginGeneration("open"));h.registerDefaults()
        val config = h.reporter("config");config.report(config.start("config_validate"),ComponentUpdate.ready())
        val disabled = CapabilityEvaluator.evaluate(registry.snapshot.value,true,cloudRouteEnabled = false)
        assertFalse(disabled.canSubmitText)
        assertEquals(CapabilityAvailability.DISABLED,disabled.features["text"])
        h.unavailable("cloud",ErrorCode.COMPONENT_RUNTIME_FAILED,"connect")
        val retry = CapabilityEvaluator.evaluate(registry.snapshot.value,true,cloudRouteEnabled = true)
        assertTrue(retry.canSubmitText)
        assertEquals(CapabilityAvailability.UNAVAILABLE,retry.features["text"])
        assertFalse(CapabilityEvaluator.evaluate(registry.snapshot.value,false,cloudRouteEnabled = true).canSubmitText)
    }

    @Test fun `sticky processed capture failure blocks new speech but preserves raw native status text and microphone retry`() {
        val registry = ComponentStatusRegistry();val h = ChannelComponentReporting(registry,"open",registry.beginGeneration("open"));h.registerDefaults()
        for (id in listOf("config","frontend","local.asr","local.nlu","cloud")) {
            val r = h.reporter(id);r.report(r.start("create"),ComponentUpdate.ready())
        }
        val failedPath = CapabilityEvaluator.evaluate(registry.snapshot.value,true,cloudRouteEnabled = true,capturePathUnavailable = true)
        assertFalse(failedPath.canTriggerManual)
        assertEquals(CapabilityAvailability.UNAVAILABLE,failedPath.features["local"])
        assertEquals(CapabilityAvailability.UNAVAILABLE,failedPath.features["cloud"])
        assertTrue(failedPath.canSubmitText);assertEquals(CapabilityAvailability.AVAILABLE,failedPath.features["text"])
        assertEquals(Lifecycle.READY,registry.snapshot.value.components.getValue("frontend").lifecycle)
        h.unavailable("capture",ErrorCode.MIC_PERMISSION_DENIED,"capture_open")
        val microphoneRetry = CapabilityEvaluator.evaluate(registry.snapshot.value,true,cloudRouteEnabled = true,capturePathUnavailable = false)
        assertTrue(microphoneRetry.canTriggerManual)
        assertTrue(microphoneRetry.summary.contains("录音权限"))
    }

}

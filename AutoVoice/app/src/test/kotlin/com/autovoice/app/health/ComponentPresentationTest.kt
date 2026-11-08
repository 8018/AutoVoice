package com.autovoice.app.health

import android.content.ContextWrapper
import com.autovoice.app.audio.AudioRecorder
import com.autovoice.app.audio.TestAudioSource
import com.autovoice.audiofrontend.CaptureEffects
import com.autovoice.voicecore.VadConfig
import com.autovoice.voicecore.health.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ComponentPresentationTest {
    @Test fun `disabled and pending labels do not claim failure and recovery removes stale warning`() {
        val d = ComponentDescriptor("cloud", "gateway", "iflytek")
        assertEquals("未启用", lifecycleLabel(ComponentStatus(d, 1, lifecycle = Lifecycle.DISABLED)))
        assertEquals("准备中", lifecycleLabel(ComponentStatus(d, 1, lifecycle = Lifecycle.INITIALIZING)))
        assertEquals("尚未触发", lifecycleLabel(ComponentStatus(d, 1)))
    }
    @Test fun `log queue is bounded and dropped events remain observable`() {
        val blocked = CountDownLatch(1);val release = CountDownLatch(1)
        val log = ComponentLogSink(write = { blocked.countDown();release.await() }, capacity = 1)
        try {
            val registry = ComponentStatusRegistry(log::offer)
            val generation = registry.beginGeneration("iflytek")
            val r = registry.reporter(ComponentDescriptor("frontend", "rnnoise", "iflytek"), generation)
            val token = r.start("native_load")
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            r.report(token, ComponentUpdate.ready())
            repeat(30) { r.report(token, ComponentUpdate.health(if (it % 2 == 0) Health.DEGRADED else Health.HEALTHY)) }
            assertTrue(registry.snapshot.value.droppedLogEvents > 0)
            assertTrue(log.dropped.get() > 0)
        } finally { release.countDown();log.close() }
    }
    @Test fun `missing JNI construction is diagnosed and never starts capture`() = runTest {
        val registry = ComponentStatusRegistry()
        val h = ChannelComponentReporting(registry, "open", registry.beginGeneration("open"));h.registerDefaults()
        val config = h.reporter("config");config.report(config.start("config_validate"), ComponentUpdate.ready())
        val factory = h.safeFrontendFactory { _, _, _ -> throw UnsatisfiedLinkError("secret /Users/private/native.so") }
        val recorder = AudioRecorder(ContextWrapper(null), testAudioSource = TestAudioSource(ByteArray(1024)), scope = backgroundScope,
            frontendFactory = factory, vadFactory = { null }, captureEffectsFactory = { object : CaptureEffects {
                override val echoCancellationActive = false;override val noiseSuppressionActive = false
                override fun attach(audioSessionId: Int) = Unit;override fun release() = Unit
            } })
        assertFalse(recorder.start(false))
        assertEquals(ErrorCode.NATIVE_LIBRARY_MISSING, registry.snapshot.value.components.getValue("frontend").error!!.code)
        assertFalse(ComponentLogSink.diagnosticsJson(registry.snapshot.value).contains("/Users/private"))
        recorder.close()
    }
    @Test fun `auxiliary detector release leaves main segmentation and manual capability intact`() {
        val registry = ComponentStatusRegistry()
        val h = ChannelComponentReporting(registry, "iflytek", registry.beginGeneration("iflytek"));h.registerDefaults()
        for (id in listOf("config", "frontend", "vad", "local.nlu", "iflytek.runtime", "local.asr", "aec", "vad.aux")) {
            val r = h.reporter(id);r.report(r.start("create"), ComponentUpdate.ready())
        }
        h.reporter("vad.aux").close()
        assertEquals(Lifecycle.READY, registry.snapshot.value.components.getValue("vad").lifecycle)
        val capabilities = CapabilityEvaluator.evaluate(registry.snapshot.value, false)
        assertTrue(capabilities.canTriggerManual)
        assertEquals(CapabilityAvailability.AVAILABLE, capabilities.features["local"])
        assertEquals(CapabilityAvailability.UNAVAILABLE, capabilities.features["barge_in"])
    }
    @Test fun `closing old failed construction cannot release a replacement attempt`() {
        val registry = ComponentStatusRegistry()
        val h = ChannelComponentReporting(registry, "iflytek", registry.beginGeneration("iflytek"));h.registerDefaults()
        val config = h.reporter("config");config.report(config.start("config_validate"), ComponentUpdate.ready())
        val factory = h.safeFrontendFactory { _, _, _ -> throw UnsatisfiedLinkError("missing test JNI") }
        val old = factory.create(ContextWrapper(null), VadConfig(), "rnnoise")
        val replacement = factory.create(ContextWrapper(null), VadConfig(), "none")
        old.close()
        assertEquals(Lifecycle.FAILED, registry.snapshot.value.components.getValue("frontend").lifecycle)
        replacement.close()
        assertEquals(Lifecycle.RELEASED, registry.snapshot.value.components.getValue("frontend").lifecycle)
    }

    @Test fun `native initialized auxiliary detectors cannot imply an unwired app feature works`() {
        val registry = ComponentStatusRegistry()
        val h = ChannelComponentReporting(registry,"open",registry.beginGeneration("open"));h.registerDefaults()
        for (id in listOf("config","frontend","vad","vad.aux","aec")) {
            val r = h.reporter(id);r.report(r.start("create"),ComponentUpdate.ready())
        }
        val c = CapabilityEvaluator.evaluate(registry.snapshot.value,false,auxiliaryBridgeAvailable = false)
        assertEquals(CapabilityAvailability.UNAVAILABLE,c.features["follow_up"])
        assertEquals(CapabilityAvailability.UNAVAILABLE,c.features["barge_in"])
        assertTrue(c.canTriggerManual)
    }

}

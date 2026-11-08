package com.autovoice.audiofrontend

import android.content.ContextWrapper
import com.autovoice.voicecore.VadConfig
import com.autovoice.voicecore.health.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LegacyFrontendReportingTest {
    @Test
    fun `diagnostic rebinding preserves instances and old instance close cannot release current vad`() {
        val events = mutableListOf<ComponentStatusEvent>()
        fun r(id: String, generation: Long) = ComponentReporter(ComponentDescriptor(id, "silero", "iflytek"), generation, ComponentStatusSink(events::add))
        var feeds = 0
        var resets = 0
        var created = 0
        fun vad() = object : com.autovoice.audiofrontend.vad.VadEngine {
            init { created++ }
            private var closed = false
            override val maxProbability = 0f
            override fun feed(pcm16k: ByteArray): Float { check(!closed);feeds++;return 0f }
            override fun reset() { resets++ }
            override fun close() { closed = true }
        }
        val processor = object : com.autovoice.audiofrontend.ecnr.FrontendSignalProcessor {
            override fun process(frame: ShortArray): ShortArray = error("disabled")
            override fun close() = Unit
        }
        val old = LocalAudioFrontendEngine(com.autovoice.audiofrontend.vad.VadSegmenter(vad()), processor, false)
        val sharedFrontend = r("frontend", 1);val sharedVad = r("vad", 1)
        old.attachReporting(sharedFrontend, sharedVad)
        val replacement = LocalAudioFrontendEngine(com.autovoice.audiofrontend.vad.VadSegmenter(vad()), processor, false)
        replacement.attachReporting(sharedFrontend, sharedVad)
        old.close()
        assertEquals(Lifecycle.READY, events.last { it.descriptor.componentId == "vad" }.update.lifecycle)
        replacement.attachReporting(r("frontend", 2), r("vad", 2))
        assertEquals(2, created)
        assertEquals(0, feeds);assertEquals(0, resets)
        assertEquals(1024, replacement.process(ByteArray(1024)).processedPcm.size)
        assertEquals(1, feeds)
        assertEquals("reuse_existing", events.last { it.descriptor.componentId == "vad" }.update.stage)
        replacement.close()
    }

    @Test
    fun `missing vad model is reported while rnnoise stays lazy until first real use`() {
        val events = mutableListOf<ComponentStatusEvent>()
        fun reporter(id: String, provider: String) = ComponentReporter(ComponentDescriptor(id, provider, "iflytek"), 1, ComponentStatusSink(events::add))
        val frontend = AudioFrontendFactory.create(ContextWrapper(null), VadConfig(), "rnnoise",
            frontendReporter = reporter("frontend", "rnnoise"), vadReporter = reporter("vad", "silero"))
        assertFalse(frontend.vadAvailable)
        assertTrue(events.any { it.descriptor.componentId == "vad" && it.update.error?.code == ErrorCode.MODEL_LOAD_FAILED })
        assertFalse(events.any { it.descriptor.componentId == "frontend" && it.update.lifecycle == Lifecycle.READY })
        assertThrows(LinkageError::class.java) { frontend.process(ByteArray(1024)) }
        assertTrue(events.any { it.descriptor.componentId == "frontend" && it.update.error?.code == ErrorCode.NATIVE_LIBRARY_MISSING })
        frontend.close()
    }
}

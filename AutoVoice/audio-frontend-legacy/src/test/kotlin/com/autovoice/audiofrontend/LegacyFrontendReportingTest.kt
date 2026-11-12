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
    @Test fun `real legacy success after initial failure uses a new token without resetting samples`() {
        val events = mutableListOf<ComponentStatusEvent>()
        val reporter = ComponentReporter(ComponentDescriptor("frontend", "rnnoise", "iflytek"), 1, ComponentStatusSink(events::add))
        val frames = mutableListOf<ShortArray>()
        val processor = object : com.autovoice.audiofrontend.ecnr.FrontendSignalProcessor {
            override fun process(frame: ShortArray): ShortArray {
                frames += frame.copyOf()
                if (frames.size == 1) throw UnsatisfiedLinkError("first native call failed")
                return frame
            }
            override fun close() = Unit
        }
        val engine = LocalAudioFrontendEngine(null, processor, true, reporter)
        try {
            assertThrows(LinkageError::class.java) { engine.process(pcm16ShortsToBytes(ShortArray(512) { 7 })) }
            val failed = events.last().token
            assertEquals(Lifecycle.FAILED, events.last().update.lifecycle)
            val output = pcm16BytesToShorts(engine.process(pcm16ShortsToBytes(ShortArray(512) { 9 })).processedPcm)
            assertNull(engine.unavailabilityReason)
            val succeeded = events.lastOrNull { it.update.lifecycle == Lifecycle.READY }
            assertNotNull(succeeded)
            assertNotEquals(failed.attemptId, succeeded!!.token.attemptId)
            assertEquals(Health.HEALTHY, succeeded!!.update.health)
            assertFalse(reporter.report(failed, ComponentUpdate.ready()))
            assertArrayEquals(ShortArray(512) { 7 } + ShortArray(448) { 9 }, output)
            assertEquals(3, frames.size)
            assertArrayEquals(ShortArray(480) { 7 }, frames[0])
            assertArrayEquals(ShortArray(480) { 7 }, frames[1])
            assertArrayEquals(ShortArray(32) { 7 } + ShortArray(448) { 9 }, frames[2])
            assertArrayEquals(ShortArray(64) { 9 }, pcm16BytesToShorts(engine.finishProcessedAudio()))
            assertEquals(4, frames.size)
            assertEquals(2, events.count { it.update.lifecycle == Lifecycle.INITIALIZING })
        } finally { engine.close() }
    }

    @Test fun `repeated failing legacy blocks do not create a new reported retry each block`() {
        val events = mutableListOf<ComponentStatusEvent>()
        val reporter = ComponentReporter(ComponentDescriptor("frontend", "rnnoise", "iflytek"), 1, ComponentStatusSink(events::add))
        var calls = 0
        val processor = object : com.autovoice.audiofrontend.ecnr.FrontendSignalProcessor {
            override fun process(frame: ShortArray): ShortArray { calls++;throw UnsatisfiedLinkError("still unavailable") }
            override fun close() = Unit
        }
        val engine = LocalAudioFrontendEngine(null, processor, true, reporter)
        try {
            repeat(3) { assertThrows(LinkageError::class.java) { engine.process(ByteArray(1024)) } }
            assertEquals(3, calls)
            assertEquals(1, events.count { it.update.lifecycle == Lifecycle.INITIALIZING })
            assertEquals(1, events.count { it.update.lifecycle == Lifecycle.FAILED })
            assertNotNull(engine.unavailabilityReason)
        } finally { engine.close() }
    }

    @Test fun `legacy batches keep 512 sample probability and old event order without extra inference`() {
        var calls = 0
        val vad = object : com.autovoice.audiofrontend.vad.VadEngine {
            override val maxProbability = 0.8f
            override fun feed(pcm16k: ByteArray): Float { calls++;return if (calls == 1) 0.8f else 0.1f }
            override fun reset() { calls = 0 }
            override fun close() = Unit
        }
        val segmenter = com.autovoice.audiofrontend.vad.VadSegmenter(vad,
            com.autovoice.audiofrontend.vad.VoiceActivityGate(minSpeechMs = 32,minSilenceMs = 32),minSegmentBytes = 0)
        val processor = object : com.autovoice.audiofrontend.ecnr.FrontendSignalProcessor {
            override fun process(frame: ShortArray) = error("denoise disabled")
            override fun close() = Unit
        }
        val f = LocalAudioFrontendEngine(segmenter,processor,false)
        try {
            f.startTurn()
            val a = f.process(ByteArray(1024) { 7 });val b = f.process(ByteArray(1024) { 9 })
            assertEquals(0L,a.processedBlocks.single().startSample)
            assertEquals(512L,b.processedBlocks.single().startSample)
            assertEquals(512,a.vadObservations.single().validSamples)
            assertEquals(0.8f,a.vadObservations.single().probability)
            assertEquals(0.1f,b.vadObservations.single().probability)
            assertEquals(listOf(com.autovoice.audiofrontend.vad.VadEvent.SpeechStart,com.autovoice.audiofrontend.vad.VadEvent.SpeechEnd),
                (a.vadObservations + b.vadObservations).flatMap { it.events })
            assertArrayEquals(ByteArray(1024) { 7 } + ByteArray(1024) { 9 },f.finishSegments().single())
            assertEquals(2,calls)
        } finally { f.close() }
    }

}

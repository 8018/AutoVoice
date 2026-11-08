package com.autovoice.adapterwebrtc

import com.autovoice.audiofrontend.*
import com.autovoice.audiofrontend.vad.VadEvent
import com.autovoice.voicecore.VadConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Fake backend checks framing/ownership only; it does not prove WebRTC DSP quality. */
class WebRtcAudioFrontendTest {
    private class FakeBackend : WebRtcBackend {
        val capture = mutableListOf<ShortArray>()
        val valid = mutableListOf<Int>()
        val renders = mutableListOf<ShortArray>()
        val resets = mutableListOf<VadPurpose>()
        var closes = 0
        var speech: (Int, VadPurpose) -> Boolean = { _, _ -> true }
        override fun processCapture(input: ShortArray, delayMs: Int, validSamples: Int): CaptureResult {
            capture += input.copyOf();valid += validSamples
            return CaptureResult(ShortArray(160) { (input[it] + 1).toShort() },
                if (validSamples == 160) VadPurpose.entries.associateWith { speech(capture.size - 1, it) } else emptyMap())
        }
        override fun processRender(input: ShortArray) { renders += input.copyOf() }
        override fun resetVad(purpose: VadPurpose) { resets += purpose }
        override fun close() { closes++ }
    }
    private fun config() = VadConfig(provider = "webrtc.vad", minSpeechMs = 10, minSilenceMs = 10)
    @Test fun `cross 32ms blocks conserve samples and monotonic 10ms coordinates`() {
        val backend = FakeBackend()
        val f = WebRtcAudioFrontend(backend, config())
        val blocks = (0 until 5).flatMap { b -> f.process(pcm(ShortArray(512) { (b * 512 + it).toShort() })).processedBlocks }
        assertEquals(16, backend.capture.size)
        assertEquals((0 until 16).map { it * 160L }, blocks.map { it.startSample })
        assertArrayEquals(ShortArray(2560) { (it + 1).toShort() }, samples(blocks.flatMap { it.pcm.asIterable() }.toByteArray()))
        assertTrue(f.finishBatch().processedBlocks.isEmpty())
        f.close()
    }
    @Test fun `flush returns exact valid tail and padding cannot cause silence event`() {
        val backend = FakeBackend().also { it.speech = { frame, _ -> frame < 3 } }
        val f = WebRtcAudioFrontend(backend, config())
        val first = f.process(pcm(ShortArray(512) { 7 }))
        val tail = f.finishBatch()
        assertEquals(3, first.processedBlocks.size)
        assertEquals(1, tail.processedBlocks.size)
        assertEquals(480L, tail.processedBlocks.single().startSample)
        assertEquals(32, tail.processedBlocks.single().validSamples)
        assertArrayEquals(ShortArray(32) { 8 }, samples(tail.processedBlocks.single().pcm))
        assertTrue(tail.vadObservations.isEmpty())
        assertEquals(listOf(160,160,160,32), backend.valid)
        assertArrayEquals(ShortArray(32) { 7 } + ShortArray(128), backend.capture.last())
        assertTrue(f.finishBatch().processedBlocks.isEmpty())
        f.close()
    }
    @Test fun `one input emits ordered multiple transitions with binary confidence absent`() {
        val backend = FakeBackend().also { it.speech = { frame, _ -> frame % 2 == 0 } }
        val f = WebRtcAudioFrontend(backend, config())
        val observations = f.process(ByteArray(1024)).vadObservations.filter { it.purpose == VadPurpose.SEGMENTATION }
        assertEquals(listOf(0L,160L,320L), observations.map { it.startSample })
        assertEquals(listOf(VadEvent.SpeechStart,VadEvent.SpeechEnd,VadEvent.SpeechStart), observations.flatMap { it.events })
        assertTrue(observations.all { it.probability == null })
        f.close()
    }
    @Test fun `per purpose reset and turn boundary retain one APM and other detector states`() {
        val backend = FakeBackend()
        val f = WebRtcAudioFrontend(backend, config())
        f.process(ByteArray(1024))
        f.resetVad(VadPurpose.FOLLOW_UP)
        f.startTurn()
        val batch = f.process(ByteArray(1024))
        assertEquals(listOf(VadPurpose.FOLLOW_UP,VadPurpose.SEGMENTATION), backend.resets)
        assertEquals(6, backend.capture.size)
        assertTrue(batch.vadObservations.filter { it.purpose == VadPurpose.BARGE_IN }.all { it.events.isEmpty() })
        assertTrue(batch.vadObservations.filter { it.purpose == VadPurpose.FOLLOW_UP }.any { VadEvent.SpeechStart in it.events })
        assertEquals(0, backend.closes)
        f.close();f.close()
        assertEquals(1, backend.closes)
        assertThrows(IllegalStateException::class.java) { f.process(ByteArray(1024)) }
        assertEquals(6, backend.capture.size)
    }
    @Test fun `pre roll uses actual processed sample coordinates and retention overflow is explicit`() {
        val backend = FakeBackend().also { it.speech = { frame, _ -> frame == 1 || frame == 2 } }
        val f = WebRtcAudioFrontend(backend, config(), preRollSamples = 160, maxSegmentSamples = 1600, maxBufferedSamples = 3200)
        f.startTurn();f.process(pcm(ShortArray(512) { (it + 1).toShort() }));f.process(ByteArray(1024));f.finishBatch()
        val segment = samples(f.finishSegments().single())
        assertEquals(640, segment.size)
        assertEquals(2.toShort(), segment.first())
        f.close()
        val bounded = WebRtcAudioFrontend(FakeBackend(), config(), maxSegmentSamples = 640, maxBufferedSamples = 640)
        bounded.startTurn();bounded.process(ByteArray(1024))
        assertThrows(IllegalStateException::class.java) { bounded.process(ByteArray(1024)) }
        bounded.close()
    }
    @Test fun `render queue consumes each real frame once and rejects old playback generation`() {
        val backend = FakeBackend()
        var now = 1_000_000_000L
        val f = WebRtcAudioFrontend(backend, config(), clockNanos = { now })
        assertTrue(f.acceptRender(RenderReferenceFrame(4,0,now,pcm(ShortArray(160) { 4 }))))
        f.invalidateRender(4)
        assertFalse(f.acceptRender(RenderReferenceFrame(4,160,now,ByteArray(320))))
        assertTrue(f.acceptRender(RenderReferenceFrame(5,0,now,pcm(ShortArray(320) { 5 }))))
        f.process(ByteArray(1024))
        assertEquals(2, backend.renders.size)
        assertTrue(backend.renders.all { it.contentEquals(ShortArray(160) { 5 }) })
        assertEquals(com.autovoice.voicecore.health.Health.HEALTHY, f.aecHealth)
        now += 300_000_000
        f.process(ByteArray(1024))
        assertEquals(2, backend.renders.size)
        assertEquals(com.autovoice.voicecore.health.Health.UNAVAILABLE, f.aecHealth)
        assertEquals(com.autovoice.voicecore.health.ErrorCode.RENDER_REFERENCE_STALE, f.renderFailure)
        f.invalidateRender(5)
        assertEquals(com.autovoice.voicecore.health.Health.UNKNOWN, f.aecHealth)
        f.close()
        assertFalse(f.acceptRender(RenderReferenceFrame(6,0,now,ByteArray(320))))
    }
    @Test fun `render overflow is explicit without interrupting capture NS VAD`() {
        val backend = FakeBackend()
        val f = WebRtcAudioFrontend(backend, config(), renderQueueCapacity = 1, clockNanos = { 0 })
        assertTrue(f.acceptRender(RenderReferenceFrame(1,0,0,ByteArray(320))))
        assertFalse(f.acceptRender(RenderReferenceFrame(1,160,0,ByteArray(320))))
        assertEquals(com.autovoice.voicecore.health.ErrorCode.RENDER_QUEUE_OVERFLOW, f.renderFailure)
        assertEquals(3, f.process(ByteArray(1024)).processedBlocks.size)
        assertEquals(3, backend.capture.size)
        assertEquals(com.autovoice.voicecore.health.Health.UNAVAILABLE, f.aecHealth)
        assertTrue(backend.renders.isEmpty())
        f.close()
    }
    @Test fun `fresh but discontinuous reference never restores echo health without a new generation`() {
        val backend = FakeBackend()
        val f = WebRtcAudioFrontend(backend, config(), clockNanos = { 1_000_000_000 })
        f.acceptRender(RenderReferenceFrame(1,0,1_000_000_000,ByteArray(320)))
        f.process(ByteArray(1024))
        assertEquals(com.autovoice.voicecore.health.Health.HEALTHY,f.aecHealth)
        f.acceptRender(RenderReferenceFrame(1,320,1_000_000_000,ByteArray(320))) // Missing160 real samples.
        f.process(ByteArray(1024))
        assertEquals(com.autovoice.voicecore.health.Health.UNAVAILABLE,f.aecHealth)
        assertEquals(1,backend.renders.size)
        f.acceptRender(RenderReferenceFrame(1,160,1_000_000_000,ByteArray(320)))
        f.process(ByteArray(1024))
        assertEquals(com.autovoice.voicecore.health.Health.UNAVAILABLE,f.aecHealth)
        f.invalidateRender(1)
        assertTrue(f.acceptRender(RenderReferenceFrame(2,0,1_000_000_000,ByteArray(320))))
        f.process(ByteArray(1024))
        assertEquals(com.autovoice.voicecore.health.Health.HEALTHY,f.aecHealth)
        f.close()
    }
    @Test fun `factory reports actual native creation failure and never manufactures usable audio`() {
        val events = mutableListOf<com.autovoice.voicecore.health.ComponentStatusEvent>()
        val reporter = com.autovoice.voicecore.health.ComponentReporter(com.autovoice.voicecore.health.ComponentDescriptor("frontend","webrtc.apm","open"),1,
            com.autovoice.voicecore.health.ComponentStatusSink(events::add))
        val f = WebRtcFrontend.createWithBackend(config(),frontendReporter = reporter) { throw UnsatisfiedLinkError("missing JNI symbol") }
        assertNotNull(f.unavailabilityReason)
        assertFalse(f.vadAvailable)
        assertEquals(com.autovoice.voicecore.health.ErrorCode.NATIVE_SYMBOL_MISMATCH,events.last().update.error!!.code)
        assertFalse(events.any { it.update.lifecycle == com.autovoice.voicecore.health.Lifecycle.READY })
        assertThrows(IllegalStateException::class.java) { f.process(ByteArray(1024)) }
        f.close()
    }
    @Test fun `reporting rebind reuses actual detectors and APM without resets or release`() {
        val backend = FakeBackend()
        val events = mutableListOf<com.autovoice.voicecore.health.ComponentStatusEvent>()
        fun r(id: String,g: Long) = com.autovoice.voicecore.health.ComponentReporter(
            com.autovoice.voicecore.health.ComponentDescriptor(id,"webrtc.apm","open"),g,com.autovoice.voicecore.health.ComponentStatusSink(events::add))
        val f = WebRtcFrontend.createWithBackend(config(),r("frontend",1),r("vad",1),r("vad.aux",1),r("aec",1)) { backend }
        assertTrue(f.vadAvailable)
        val initialReady = events.count { it.update.lifecycle == com.autovoice.voicecore.health.Lifecycle.READY }
        assertEquals(4,initialReady)
        f.attachReporting(r("frontend",2),r("vad",2))
        f.attachAuxiliaryReporting(r("vad.aux",2),r("aec",2))
        assertTrue(backend.resets.isEmpty());assertEquals(0,backend.closes)
        assertTrue(events.filter { it.token.generation == 2L }.all { it.update.stage == "reuse_existing" })
        assertEquals(3,f.process(ByteArray(1024)).processedBlocks.size)
        f.close()
        assertEquals(1,backend.closes)
        assertTrue(events.filter { it.update.lifecycle == com.autovoice.voicecore.health.Lifecycle.RELEASED }.all { it.token.generation == 2L })
    }
    @Test fun `long confirmed speech keeps a short final fragment without violating maximum length`() {
        val f = WebRtcAudioFrontend(FakeBackend(),config(),preRollSamples = 0,maxSegmentSamples = 500,maxBufferedSamples = 3200)
        f.startTurn()
        repeat(3) { f.process(ByteArray(1024)) }
        f.finishBatch()
        val segments = f.finishSegments()
        assertEquals(1536,segments.sumOf { it.size / 2 })
        assertTrue(segments.all { it.size / 2 <= 500 })
        f.close()
    }
    @Test fun `higher invalidation retires queued older reference before any render reaches backend`() {
        val backend = FakeBackend()
        val f = WebRtcAudioFrontend(backend,config(),clockNanos = { 0 })
        try {
            assertTrue(f.acceptRender(RenderReferenceFrame(5,0,0,pcm(ShortArray(160) { 5 }))))
            f.invalidateRender(6)
            assertEquals(3,f.process(ByteArray(1024)).processedBlocks.size)
            assertTrue(backend.renders.isEmpty())
            assertEquals(com.autovoice.voicecore.health.Health.UNKNOWN,f.aecHealth)
            assertFalse(f.acceptRender(RenderReferenceFrame(5,160,0,ByteArray(320))))
            assertFalse(f.acceptRender(RenderReferenceFrame(6,0,0,ByteArray(320))))
            assertTrue(f.acceptRender(RenderReferenceFrame(7,0,0,pcm(ShortArray(160) { 7 }))))
            f.process(ByteArray(1024))
            assertEquals(1,backend.renders.size)
            assertArrayEquals(ShortArray(160) { 7 },backend.renders.single())
            assertEquals(com.autovoice.voicecore.health.Health.HEALTHY,f.aecHealth)
            assertTrue(backend.resets.isEmpty());assertEquals(0,backend.closes)
        } finally { f.close() }
    }
    @Test fun `higher invalidation immediately withdraws healthy older reference and clears partial samples`() {
        val backend = FakeBackend()
        val events = mutableListOf<com.autovoice.voicecore.health.ComponentStatusEvent>()
        val reporter = com.autovoice.voicecore.health.ComponentReporter(
            com.autovoice.voicecore.health.ComponentDescriptor("aec","webrtc.apm","open"),1,com.autovoice.voicecore.health.ComponentStatusSink(events::add))
        val f = WebRtcAudioFrontend(backend,config(),clockNanos = { 0 },aecReporter = reporter)
        try {
            f.acceptRender(RenderReferenceFrame(5,0,0,pcm(ShortArray(240) { 5 })))
            f.process(ByteArray(1024)) // One rendered frame plus80 pending real samples.
            assertEquals(com.autovoice.voicecore.health.Health.HEALTHY,f.aecHealth)
            f.acceptRender(RenderReferenceFrame(5,240,0,pcm(ShortArray(80) { 5 })))
            f.invalidateRender(6)
            assertEquals(com.autovoice.voicecore.health.Health.UNKNOWN,f.aecHealth)
            assertEquals(com.autovoice.voicecore.health.Health.UNKNOWN,events.last().update.health)
            assertNull(f.renderFailure)
            f.acceptRender(RenderReferenceFrame(7,0,0,pcm(ShortArray(80) { 7 })))
            f.process(ByteArray(1024))
            assertEquals(1,backend.renders.size) // Old80 queued and old80 pending cannot form a frame.
            assertEquals(com.autovoice.voicecore.health.Health.UNKNOWN,f.aecHealth)
            f.acceptRender(RenderReferenceFrame(7,80,0,pcm(ShortArray(80) { 7 })))
            f.process(ByteArray(1024))
            assertEquals(2,backend.renders.size)
            assertArrayEquals(ShortArray(160) { 7 },backend.renders.last())
            assertEquals(com.autovoice.voicecore.health.Health.HEALTHY,f.aecHealth)
            assertTrue(backend.resets.isEmpty());assertEquals(0,backend.closes)
        } finally { f.close() }
    }
    @Test fun `delayed older invalidation keeps newer active reference usable`() {
        val backend = FakeBackend()
        val f = WebRtcAudioFrontend(backend,config(),clockNanos = { 0 })
        try {
            f.acceptRender(RenderReferenceFrame(7,0,0,pcm(ShortArray(160) { 7 })))
            f.process(ByteArray(1024))
            f.invalidateRender(6);f.invalidateRender(5)
            assertEquals(com.autovoice.voicecore.health.Health.HEALTHY,f.aecHealth)
            assertFalse(f.acceptRender(RenderReferenceFrame(6,0,0,ByteArray(320))))
            assertTrue(f.acceptRender(RenderReferenceFrame(7,160,0,pcm(ShortArray(160) { 7 }))))
            f.process(ByteArray(1024))
            assertEquals(2,backend.renders.size)
            assertTrue(backend.renders.all { it.contentEquals(ShortArray(160) { 7 }) })
            assertEquals(com.autovoice.voicecore.health.Health.HEALTHY,f.aecHealth)
        } finally { f.close() }
    }
    private fun pcm(samples: ShortArray) = ByteArray(samples.size * 2).also { b -> samples.forEachIndexed { i,s -> b[2*i]=s.toByte();b[2*i+1]=(s.toInt() shr 8).toByte() } }
    private fun samples(pcm: ByteArray) = ShortArray(pcm.size / 2) { i -> ((pcm[i*2].toInt() and 255) or (pcm[i*2+1].toInt() shl 8)).toShort() }
}

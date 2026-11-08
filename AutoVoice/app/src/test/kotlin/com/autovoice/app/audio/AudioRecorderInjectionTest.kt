package com.autovoice.app.audio

import com.autovoice.app.health.ChannelComponentReporting
import com.autovoice.app.health.ComponentStatusRegistry
import com.autovoice.voicecore.health.*
import android.content.ContextWrapper
import com.autovoice.audiofrontend.AudioFrontendDiagnostics
import com.autovoice.audiofrontend.AudioFrontendEngine
import com.autovoice.audiofrontend.AudioFrontendFrame
import com.autovoice.audiofrontend.CaptureEffects
import com.autovoice.audiofrontend.vad.VadEngine
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.VadConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.autovoice.audiofrontend.FrontendBatch
import com.autovoice.audiofrontend.PositionedPcmBlock
import com.autovoice.audiofrontend.VadObservation
import com.autovoice.audiofrontend.vad.VadEvent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioRecorderInjectionTest {
    // Catches a recorder rebuilding the hard-coded legacy provider after a mode switch.
    @Test
    fun `mode reconfiguration uses selected frontend and closes previous instance`() = runTest {
        val created = mutableListOf<SelectedFrontend>()
        val configs = mutableListOf<Pair<VadConfig, String>>()
        val recorder = AudioRecorder(
            context = ContextWrapper(null),
            testAudioSource = TestAudioSource(ByteArray(1024) { 7 }),
            scope = backgroundScope,
            frontendFactory = { _, vad, ecnr ->
                configs += vad to ecnr
                SelectedFrontend().also(created::add)
            },
            vadFactory = { null },
            captureEffectsFactory = { SelectedEffects() },
        )
        val selectedVad = VadConfig(threshold = 0.75)
        recorder.configureAudioProcessing(selectedVad, DemoConfig.ECNR_NONE)
        recorder.configureAudioProcessing(selectedVad, DemoConfig.ECNR_NONE)
        assertEquals(listOf(VadConfig() to DemoConfig.ECNR_RNNOISE, selectedVad to DemoConfig.ECNR_NONE), configs)
        assertEquals(1, created[0].closes)
        assertEquals(0, created[1].closes)
        val pcm = mutableListOf<ByteArray>()
        val collector = launch { recorder.pcmBlocks.take(1).toList(pcm) }
        runCurrent()
        assertTrue(recorder.start(false))
        runCurrent()
        recorder.stop()
        collector.join()
        assertEquals(0, created[0].starts)
        assertEquals(1, created[1].starts)
        assertArrayEquals(byteArrayOf(42, 0), pcm.single())
        assertArrayEquals(byteArrayOf(43, 0), recorder.finishProcessedAudio())
        assertArrayEquals(byteArrayOf(7), recorder.finishSegments().single())
        assertEquals(1, created[1].blocks)
        recorder.close()
        recorder.close()
        assertEquals(1, created[1].closes)
    }

    // Catches auxiliary VAD bypassing injection, or changes to the 160 ms trigger.
    @Test
    fun `follow up and barge in use selected vad and effects after reconfiguration`() = runTest {
        val vad = SelectedVad()
        val effects = SelectedEffects()
        var vadCreations = 0
        var effectsCreations = 0
        val recorder = AudioRecorder(
            context = ContextWrapper(null), testAudioSource = null, scope = backgroundScope,
            frontendFactory = { _, _, _ -> SelectedFrontend() },
            vadFactory = { vadCreations++; vad },
            captureEffectsFactory = { effectsCreations++; effects },
        )
        recorder.configureAudioProcessing(VadConfig(threshold = 0.75), DemoConfig.ECNR_NONE)
        val block = ByteArray(1024)
        recorder.setFollowUpListening(true)
        repeat(4) { assertFalse(recorder.detectFollowUpSpeech(block)) }
        assertTrue(recorder.detectFollowUpSpeech(block))
        assertFalse(recorder.detectFollowUpSpeech(block))
        // The JVM has no microphone session; exercise the real effects attachment boundary.
        attachEffects(recorder, 17)
        assertTrue(recorder.openMicBargeInAvailable)
        recorder.setOpenMicBargeInListening(true)
        repeat(4) { assertFalse(recorder.detectOpenMicBargeIn(block)) }
        assertTrue(recorder.detectOpenMicBargeIn(block))
        assertFalse(recorder.detectOpenMicBargeIn(block))
        assertEquals(10, vad.feeds)
        assertEquals(2, vad.resets)
        assertEquals(1, vadCreations)
        assertEquals(1, effectsCreations)
        assertEquals(17, effects.session)
        val releasesBeforeClose = effects.releases
        recorder.close()
        recorder.close()
        assertEquals(1, vad.closes)
        assertEquals(releasesBeforeClose + 1, effects.releases)
    }

    // Catches fallback to legacy VAD when the selected provider reports unavailable.
    @Test
    fun `missing selected vad keeps follow up and barge in unavailable`() = runTest {
        val recorder = AudioRecorder(
            context = ContextWrapper(null), testAudioSource = null, scope = backgroundScope,
            frontendFactory = { _, _, _ -> SelectedFrontend(available = false) },
            vadFactory = { null }, captureEffectsFactory = { SelectedEffects() },
        )
        attachEffects(recorder, 23)
        recorder.setFollowUpListening(true)
        recorder.setOpenMicBargeInListening(true)
        assertFalse(recorder.vadAvailable)
        assertFalse(recorder.openMicBargeInAvailable)
        repeat(10) {
            assertFalse(recorder.detectFollowUpSpeech(ByteArray(1024)))
            assertFalse(recorder.detectOpenMicBargeIn(ByteArray(1024)))
        }
        recorder.close()
    }

    @Test
    fun `unavailable frontend refuses capture without processing and exposes reason`() = runTest {
        var processed = 0
        val frontend = object : AudioFrontendEngine {
            override val unavailabilityReason = "frontend unavailable fixture"
            override val vadAvailable = false
            override val diagnostics = AudioFrontendDiagnostics()
            override fun startTurn() { error("must not start") }
            override fun process(block: ByteArray): AudioFrontendFrame { processed++; error("must not process") }
            override fun finishProcessedAudio() = ByteArray(0)
            override fun finishSegments() = emptyList<ByteArray>()
            override fun close() = Unit
        }
        val recorder = AudioRecorder(
            context = ContextWrapper(null), testAudioSource = TestAudioSource(ByteArray(1024)),
            scope = backgroundScope, frontendFactory = { _, _, _ -> frontend },
            vadFactory = { null }, captureEffectsFactory = { SelectedEffects() },
        )
        assertEquals("frontend unavailable fixture", recorder.frontendUnavailableReason)
        assertFalse(recorder.start(false))
        runCurrent()
        assertEquals(0, processed)
        recorder.close()
    }

    @Test
    fun `same configuration rebinds retained frontend and auxiliary detector without creating instances`() = runTest {
        val registry = ComponentStatusRegistry()
        fun assembly() = ChannelComponentReporting(registry, "iflytek", registry.beginGeneration("iflytek")).also {
            it.registerDefaults()
            val r = it.reporter("config");r.report(r.start("config_validate"), ComponentUpdate.ready())
        }
        val first = assembly()
        var bindings = 0
        var frontendCreations = 0
        var vadCreations = 0
        val frontend = object : AudioFrontendEngine {
            override val vadAvailable = true
            override val diagnostics = AudioFrontendDiagnostics()
            override fun attachReporting(frontendReporter: ComponentReporter, vadReporter: ComponentReporter) {
                bindings++
                frontendReporter.report(frontendReporter.start("reuse_existing"), ComponentUpdate.ready())
                vadReporter.report(vadReporter.start("reuse_existing"), ComponentUpdate.ready())
            }
            override fun startTurn() = Unit
            override fun process(block: ByteArray) = AudioFrontendFrame(block, null)
            override fun finishProcessedAudio() = ByteArray(0)
            override fun finishSegments() = emptyList<ByteArray>()
            override fun close() = Unit
        }
        val recorder = AudioRecorder(ContextWrapper(null), testAudioSource = null, scope = backgroundScope,
            frontendFactory = { _, _, _ -> frontendCreations++;frontend },
            vadFactory = { vadCreations++;SelectedVad() }, captureEffectsFactory = { SelectedEffects() },
            auxVadReporter = first.reporter("vad.aux"))
        val oldAux = first.reporter("vad.aux")
        val second = assembly()
        recorder.configureReporting(second.reporter("capture"), second.reporter("aec"),
            frontend = second.reporter("frontend"), vad = second.reporter("vad"), auxVad = second.reporter("vad.aux"))
        recorder.configureAudioProcessing(VadConfig(), DemoConfig.ECNR_RNNOISE)
        assertEquals(1, frontendCreations)
        assertEquals(1, vadCreations)
        assertEquals(1, bindings)
        assertEquals(Lifecycle.READY, registry.snapshot.value.components.getValue("vad.aux").lifecycle)
        assertEquals("reuse_existing", registry.snapshot.value.components.getValue("frontend").stage)
        oldAux.close()
        assertEquals(Lifecycle.READY, registry.snapshot.value.components.getValue("vad.aux").lifecycle)
        recorder.close()
    }

    @Test fun `recorder consumes all ordered batch events and variable pcm blocks`() = runTest {
        val frontend = object : AudioFrontendEngine {
            override val vadAvailable = true
            override val diagnostics = AudioFrontendDiagnostics()
            override fun startTurn() = Unit
            override fun process(block: ByteArray) = FrontendBatch(listOf(PositionedPcmBlock(0,byteArrayOf(1,0)),PositionedPcmBlock(1,byteArrayOf(2,0))),
                listOf(VadObservation(0,160,true,events = listOf(VadEvent.SpeechStart)),VadObservation(160,160,false,events = listOf(VadEvent.SpeechEnd))))
            override fun finishProcessedAudio() = ByteArray(0)
            override fun finishSegments() = emptyList<ByteArray>()
            override fun close() = Unit
        }
        val recorder = AudioRecorder(ContextWrapper(null),testAudioSource = TestAudioSource(ByteArray(1024)),scope = backgroundScope,
            frontendFactory = { _,_,_ -> frontend },vadFactory = { null },captureEffectsFactory = { SelectedEffects() })
        val events = mutableListOf<VadEvent>();val pcm = mutableListOf<ByteArray>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recorder.vadEvents.collect { events += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recorder.pcmBlocks.collect { pcm += it } }
        try {
            assertTrue(recorder.start(false));runCurrent()
            assertEquals(listOf(VadEvent.SpeechStart,VadEvent.SpeechEnd),events)
            assertEquals(2,pcm.size)
            assertArrayEquals(byteArrayOf(1,0),pcm[0]);assertArrayEquals(byteArrayOf(2,0),pcm[1])
        } finally { recorder.close() }
    }
    @Test fun `frontend owned auxiliary detectors do not allocate a second recorder detector`() = runTest {
        val frontend = object : AudioFrontendEngine {
            override val ownsAuxiliaryVad = true
            override val vadAvailable = true
            override val diagnostics = AudioFrontendDiagnostics()
            override fun startTurn() = Unit
            override fun process(block: ByteArray) = AudioFrontendFrame(block,null)
            override fun finishProcessedAudio() = ByteArray(0)
            override fun finishSegments() = emptyList<ByteArray>()
            override fun close() = Unit
        }
        var auxCreations = 0
        val recorder = AudioRecorder(ContextWrapper(null),testAudioSource = null,scope = backgroundScope,
            frontendFactory = { _,_,_ -> frontend },vadFactory = { auxCreations++;null },captureEffectsFactory = { SelectedEffects() })
        try { assertEquals(0,auxCreations);assertTrue(recorder.vadAvailable) } finally { recorder.close() }
    }

    @Test fun `legacy presence metadata alone never rebuilds the same audio processing`() = runTest {
        val created = mutableListOf<SelectedFrontend>()
        val recorder = AudioRecorder(ContextWrapper(null),testAudioSource = null,scope = backgroundScope,
            frontendFactory = { _,_,_ -> SelectedFrontend().also(created::add) },vadFactory = { null },captureEffectsFactory = { SelectedEffects() })
        try {
            recorder.configureAudioProcessing(VadConfig(thresholdProvided = true),DemoConfig.ECNR_RNNOISE)
            recorder.configureAudioProcessing(VadConfig(thresholdProvided = false),DemoConfig.ECNR_RNNOISE)
            assertEquals(1,created.size)
            assertEquals(0,created.single().closes)
        } finally { recorder.close() }
        assertEquals(1,created.single().closes)
    }

    private fun attachEffects(recorder: AudioRecorder, session: Int) {
        AudioRecorder::class.java.getDeclaredMethod("attachCaptureEffects", Int::class.javaPrimitiveType)
            .also { it.isAccessible = true }.invoke(recorder, session)
    }

    private class SelectedFrontend(private val available: Boolean = true) : AudioFrontendEngine {
        var starts = 0
        var blocks = 0
        var closes = 0
        override val vadAvailable get() = available
        override val diagnostics = AudioFrontendDiagnostics()
        override fun startTurn() { starts++ }
        override fun process(block: ByteArray): AudioFrontendFrame {
            blocks++
            return AudioFrontendFrame(byteArrayOf(42, 0), null)
        }
        override fun finishProcessedAudio() = byteArrayOf(43, 0)
        override fun finishSegments() = listOf(byteArrayOf(7))
        override fun close() { closes++ }
    }
    private class SelectedVad : VadEngine {
        var feeds = 0
        var resets = 0
        var closes = 0
        override val maxProbability = 0.9f
        override fun feed(pcm16k: ByteArray): Float { feeds++; return 0.9f }
        override fun reset() { resets++ }
        override fun close() { closes++ }
    }
    private class SelectedEffects : CaptureEffects {
        var session: Int? = null
        var releases = 0
        override val echoCancellationActive get() = session != null
        override val noiseSuppressionActive get() = session != null
        override fun attach(audioSessionId: Int) { session = audioSessionId }
        override fun release() { session = null; releases++ }
    }
}

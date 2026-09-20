package com.autovoice.audiofrontend

import com.autovoice.audiofrontend.ecnr.FrontendSignalProcessor
import com.autovoice.audiofrontend.vad.VadEngine
import com.autovoice.audiofrontend.vad.VadEvent
import com.autovoice.audiofrontend.vad.VadSegmenter
import com.autovoice.audiofrontend.vad.VoiceActivityGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AudioFrontendEngineTest {
    @Test
    fun `one input block runs vad and signal processing behind one boundary`() {
        val calls = mutableListOf<String>()
        val vad = FakeVad(calls, probability = 0.9f)
        val processor = FakeProcessor(calls)
        val frontend = LocalAudioFrontendEngine(
            segmenter = VadSegmenter(
                vad = vad,
                gate = VoiceActivityGate(minSpeechMs = 32, minSilenceMs = 32),
                minSegmentBytes = 1,
            ),
            denoiser = processor,
            denoiseEnabled = true,
        )

        frontend.startTurn()
        val result = frontend.process(pcmBlock())

        assertEquals(listOf("vad:reset", "vad:feed", "signal:process"), calls)
        assertEquals(VadEvent.SpeechStart, result.vadEvent)
        assertEquals(960, result.processedPcm.size)
        assertEquals(1, frontend.diagnostics.speechStartEvents)
    }

    @Test
    fun `disabled signal processor still keeps vad and returns aligned pcm`() {
        val calls = mutableListOf<String>()
        val frontend = LocalAudioFrontendEngine(
            segmenter = VadSegmenter(
                vad = FakeVad(calls, probability = 0.1f),
                gate = VoiceActivityGate(minSpeechMs = 32),
            ),
            denoiser = FakeProcessor(calls),
            denoiseEnabled = false,
        )

        frontend.startTurn()
        val result = frontend.process(pcmBlock())

        assertEquals(listOf("vad:reset", "vad:feed"), calls)
        assertNull(result.vadEvent)
        assertEquals(960, result.processedPcm.size)
    }

    @Test
    fun `invalid capture frame is rejected at frontend boundary`() {
        val frontend = LocalAudioFrontendEngine(null, FakeProcessor(mutableListOf()), false)
        val error = runCatching { frontend.process(ByteArray(100)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    private fun pcmBlock(): ByteArray = ByteArray(1024).also { block ->
        for (sample in 0 until 512) {
            block[sample * 2] = (sample and 0xff).toByte()
            block[sample * 2 + 1] = (sample ushr 8).toByte()
        }
    }

    private class FakeVad(
        private val calls: MutableList<String>,
        private val probability: Float,
    ) : VadEngine {
        override var maxProbability: Float = 0f
            private set

        override fun feed(pcm16k: ByteArray): Float {
            calls += "vad:feed"
            maxProbability = maxOf(maxProbability, probability)
            return probability
        }

        override fun reset() {
            calls += "vad:reset"
            maxProbability = 0f
        }

        override fun close() = Unit
    }

    private class FakeProcessor(private val calls: MutableList<String>) : FrontendSignalProcessor {
        override fun process(frame: ShortArray): ShortArray {
            calls += "signal:process"
            return frame
        }

        override fun close() = Unit
    }
}

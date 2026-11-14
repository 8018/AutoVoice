package com.autovoice.adapterfirered

import com.autovoice.audiofrontend.vad.VadEvent
import com.autovoice.audiofrontend.vad.VoiceActivityGate
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

internal interface SileroHandle : AutoCloseable {
    fun reset()
    fun compute(samples: FloatArray): Float
}

internal class SenseVoiceDecoder(
    private val vad: SileroHandle,
    private val recognizer: FireRedDecoder,
) : FireRedDecoder {
    override fun decode(samples: FloatArray): String = checkNotNull(decodeWhileValid(samples) { true })

    override fun decodeWhileValid(samples: FloatArray, valid: () -> Boolean): String? {
        if (!valid()) return null
        vad.reset()
        val gate = VoiceActivityGate(THRESHOLD, MIN_SPEECH_MS, MIN_SILENCE_MS, SAMPLE_RATE)
        val frame = FloatArray(CONTEXT_SAMPLES + FRAME_SAMPLES)
        var admitted = false
        for (start in samples.indices step FRAME_SAMPLES) {
            if (!valid()) return null
            // The first frame starts with zero history. Subsequent frames have a full 64 samples.
            if (start > 0) samples.copyInto(frame, 0, start - CONTEXT_SAMPLES, start)
            val end = minOf(start + FRAME_SAMPLES, samples.size)
            samples.copyInto(frame, CONTEXT_SAMPLES, start, end)
            frame.fill(0f, CONTEXT_SAMPLES + end - start)
            val probability = vad.compute(frame)
            check(probability in 0f..1f) { "Silero returned an invalid probability" }
            if (gate.feed(probability) == VadEvent.SpeechStart) admitted = true
        }
        if (!admitted) return ""
        // Recheck the request after VAD and before opening an ASR stream.
        return recognizer.decodeWhileValid(samples, valid)
    }

    override fun close() {
        // Both handles must be released on the serialized owner even if one release fails.
        vad.use { recognizer.close() }
    }

    companion object {
        private const val FRAME_SAMPLES = 512
        private const val CONTEXT_SAMPLES = 64
        private const val SAMPLE_RATE = 16000
        private const val THRESHOLD = 0.5f
        private const val MIN_SPEECH_MS = 64
        private const val MIN_SILENCE_MS = 960

        fun create(model: ValidatedSenseVoiceModel): SenseVoiceDecoder {
            val vad = Vad(config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = model.vadFile.absolutePath,
                    threshold = THRESHOLD,
                    minSilenceDuration = MIN_SILENCE_MS / 1000f,
                    minSpeechDuration = MIN_SPEECH_MS / 1000f,
                    windowSize = FRAME_SAMPLES,
                ),
                sampleRate = SAMPLE_RATE,
            ))
            val handle = object : SileroHandle {
                override fun reset() = vad.reset()
                override fun compute(samples: FloatArray) = vad.compute(samples)
                override fun close() = vad.release()
            }
            val recognizer = try {
                SherpaDecoder.create(OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = model.modelFile.absolutePath,
                        language = "zh",
                        useInverseTextNormalization = true,
                    ),
                    tokens = model.tokensFile.absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                ))
            } catch (error: Throwable) {
                try {
                    handle.close()
                } catch (releaseError: Throwable) {
                    error.addSuppressed(releaseError)
                }
                throw error
            }
            return SenseVoiceDecoder(handle, recognizer)
        }
    }
}

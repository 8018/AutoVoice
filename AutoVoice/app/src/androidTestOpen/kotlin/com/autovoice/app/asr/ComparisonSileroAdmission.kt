package com.autovoice.app.asr

import com.autovoice.audiofrontend.vad.VadEvent
import com.autovoice.audiofrontend.vad.VoiceActivityGate
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

internal const val SILERO_FRAME_SAMPLES = 512
internal const val SILERO_CONTEXT_SAMPLES = 64
internal const val SILERO_THRESHOLD = 0.5f
internal const val SILERO_MIN_SPEECH_MS = 64
internal const val SILERO_MIN_SILENCE_MS = 960

internal enum class AsrPipeline(val id: String) {
    RAW("raw"),
    SILERO("silero"),
}

internal fun parseAsrPipeline(argument: String?): AsrPipeline = when (argument) {
    null, "raw" -> AsrPipeline.RAW
    "silero" -> AsrPipeline.SILERO
    else -> throw IllegalArgumentException("asrPipeline must be raw or silero")
}

internal class ComparisonSileroAdmission(model: VerifiedFile) : AutoCloseable {
    private val vad = Vad(config = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = model.file.absolutePath,
            threshold = SILERO_THRESHOLD,
            minSpeechDuration = SILERO_MIN_SPEECH_MS / 1000f,
            minSilenceDuration = SILERO_MIN_SILENCE_MS / 1000f,
            windowSize = SILERO_FRAME_SAMPLES,
        ),
        sampleRate = ASR_SAMPLE_RATE,
    ))

    fun admits(pcm: FloatArray): Boolean {
        vad.reset()
        return evaluateSileroAdmission(pcm, vad::compute)
    }

    override fun close() = vad.release()
}

internal fun evaluateSileroAdmission(
    pcm: FloatArray,
    compute: (FloatArray) -> Float,
): Boolean {
    val gate = VoiceActivityGate(
        threshold = SILERO_THRESHOLD,
        minSpeechMs = SILERO_MIN_SPEECH_MS,
        minSilenceMs = SILERO_MIN_SILENCE_MS,
        sampleRate = ASR_SAMPLE_RATE,
    )
    val input = FloatArray(SILERO_CONTEXT_SAMPLES + SILERO_FRAME_SAMPLES)
    var speechDetected = false
    for (start in pcm.indices step SILERO_FRAME_SAMPLES) {
        val contextStart = maxOf(0, start - SILERO_CONTEXT_SAMPLES)
        pcm.copyInto(input, SILERO_CONTEXT_SAMPLES - (start - contextStart), contextStart, start)
        val end = minOf(start + SILERO_FRAME_SAMPLES, pcm.size)
        pcm.copyInto(input, SILERO_CONTEXT_SAMPLES, start, end)
        // Only pad the final partial frame. Do not infer from extra silence after the file.
        input.fill(0f, SILERO_CONTEXT_SAMPLES + (end - start))
        val probability = compute(input)
        check(probability in 0f..1f) { "Silero returned an invalid probability" }
        if (gate.feed(probability) == VadEvent.SpeechStart) speechDetected = true
    }
    return speechDetected
}

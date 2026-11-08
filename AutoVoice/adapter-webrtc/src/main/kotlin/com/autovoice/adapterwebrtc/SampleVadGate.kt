package com.autovoice.adapterwebrtc

import com.autovoice.audiofrontend.vad.VadEvent

/** Binary gate on real samples; no Silero probability or 32ms rounding. */
internal class SampleVadGate(private val minSpeechSamples: Long, private val minSilenceSamples: Long) {
    private var hot = 0L
    private var cold = 0L
    private var inSpeech = false
    var onsetSample = 0L
        private set
    fun feed(speech: Boolean, start: Long, samples: Int): List<VadEvent> {
        if (speech) {
            if (hot == 0L) onsetSample = start
            hot += samples;cold = 0
            if (!inSpeech && hot >= minSpeechSamples) { inSpeech = true;return listOf(VadEvent.SpeechStart) }
        } else {
            hot = 0;cold += samples
            if (inSpeech && cold >= minSilenceSamples) { inSpeech = false;cold = 0;return listOf(VadEvent.SpeechEnd) }
        }
        return emptyList()
    }
    fun reset() { hot = 0;cold = 0;inSpeech = false;onsetSample = 0 }
}

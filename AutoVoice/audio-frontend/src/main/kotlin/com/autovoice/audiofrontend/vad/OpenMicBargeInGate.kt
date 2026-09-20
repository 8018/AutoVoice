package com.autovoice.audiofrontend.vad

/**
 * Continuous-speech trigger used for open-microphone interruption and follow-up listening.
 * It emits once after 160 ms of speech and remains closed until explicitly rearmed.
 */
class OpenMicBargeInGate(
    private val gate: VoiceActivityGate = VoiceActivityGate(
        threshold = 0.65f,
        minSpeechMs = 160,
        minSilenceMs = 320,
    ),
) {
    @Volatile
    var listening: Boolean = false
        private set

    @Synchronized
    fun start() {
        gate.reset()
        listening = true
    }

    @Synchronized
    fun stop() {
        listening = false
        gate.reset()
    }

    @Synchronized
    fun feed(probability: Float): Boolean {
        if (!listening) return false
        if (gate.feed(probability) != VadEvent.SpeechStart) return false
        listening = false
        return true
    }
}

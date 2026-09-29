package com.autovoice.voiceengine.api

/**
 * Business-facing control for accepting new recognition input. Disabling input must not invalidate
 * NLU work whose input was already finalized; that work belongs to its request lifecycle.
 */
interface RecognitionControl {
    val recognitionEnabled: Boolean
    fun startRecognition()
    fun stopRecognition()
}

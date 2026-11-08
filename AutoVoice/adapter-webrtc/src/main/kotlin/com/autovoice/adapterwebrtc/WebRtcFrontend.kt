package com.autovoice.adapterwebrtc

import com.autovoice.audiofrontend.*

/** Explicit transition state. No passthrough or simulated WebRTC processing is provided. */
object WebRtcFrontend {
    const val UNAVAILABLE_REASON = "WebRTC APM/VAD native implementation is not installed"
    fun createUnavailable(): AudioFrontendEngine = object : AudioFrontendEngine {
        override val unavailabilityReason = UNAVAILABLE_REASON
        override val vadAvailable = false
        override val diagnostics = AudioFrontendDiagnostics()
        override fun startTurn(): Unit = throw IllegalStateException(UNAVAILABLE_REASON)
        override fun process(block: ByteArray): AudioFrontendFrame = throw IllegalStateException(UNAVAILABLE_REASON)
        override fun finishProcessedAudio() = ByteArray(0)
        override fun finishSegments() = emptyList<ByteArray>()
        override fun close() = Unit
    }
}

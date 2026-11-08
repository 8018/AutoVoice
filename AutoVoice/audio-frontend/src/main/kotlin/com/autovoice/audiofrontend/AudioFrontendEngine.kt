package com.autovoice.audiofrontend

import com.autovoice.audiofrontend.vad.VadEvent

/** A single frame emitted by the microphone audio front end. */
data class AudioFrontendFrame(
    val processedPcm: ByteArray,
    val vadEvent: VadEvent?,
)

/** Diagnostic snapshot for the current capture turn. */
data class AudioFrontendDiagnostics(
    val maxVadProbability: Float? = null,
    val speechStartEvents: Int = 0,
    val speechEndEvents: Int = 0,
    val inputBlocks: Int = 0,
    val openSegmentStart: Int? = null,
)

/**
 * Microphone front-end boundary: raw PCM enters once, signal processing and VAD run together, and
 * callers receive only the processed stream plus VAD observations. Capture and business routing do
 * not know which denoiser or VAD implementation is installed.
 */
interface AudioFrontendEngine : AutoCloseable {
    /** Non-null only when audio processing itself cannot run; independent from ASR readiness. */
    val unavailabilityReason: String? get() = null
    val vadAvailable: Boolean
    val diagnostics: AudioFrontendDiagnostics

    fun startTurn()
    fun process(block: ByteArray): AudioFrontendFrame
    /** Flushes the sub-frame tail while preserving the exact input sample count. */
    fun finishProcessedAudio(): ByteArray
    fun finishSegments(): List<ByteArray>
}

package com.autovoice.audiofrontend

import com.autovoice.voicecore.health.ComponentReporter

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
    /** Rebinds diagnostics only: never recreate/reset APM, VAD, recurrent state or audio grids. */
    fun attachReporting(frontendReporter: ComponentReporter, vadReporter: ComponentReporter) = Unit
    /** Rebind diagnostics for native-owned auxiliary VAD and AEC without altering either engine. */
    fun attachAuxiliaryReporting(auxVadReporter: ComponentReporter, aecReporter: ComponentReporter) = Unit
    val ownsAuxiliaryVad: Boolean get() = false
    val vadAvailable: Boolean
    val diagnostics: AudioFrontendDiagnostics

    fun startTurn()
    fun process(block: ByteArray): FrontendBatch
    /** Flushes the sub-frame tail while preserving the exact input sample count. */
    fun finishProcessedAudio(): ByteArray
    /** Normal input completion: flush before finishSegments; device cancellation discards pending input. */
    fun finishBatch(): FrontendBatch = AudioFrontendFrame(finishProcessedAudio(), null)
    fun finishSegments(): List<ByteArray>
}

package com.autovoice.audiofrontend

import com.autovoice.voicecore.health.ComponentReporter

/** Diagnostic snapshot for the current capture turn. */
data class AudioFrontendDiagnostics(
    val maxVadProbability: Float? = null,
    val speechStartEvents: Int = 0,
    val speechEndEvents: Int = 0,
    val inputBlocks: Int = 0,
    val openSegmentStart: Int? = null,
    /** Genuine segmentation observation, not business input finalization/admission. */
    val observedInputEndSample: Long? = null,
)

/** Bounded processed history and original main observations following a genuine auxiliary confirmation.
 * No request ownership, admission or finalized flag belongs to this audio fact. */
data class AudioInputHistory(val batch: FrontendBatch, val auxiliaryConfirmedAtSample: Long) {
    val observedEndSample: Long? get() = batch.vadObservations.firstOrNull {
        it.purpose == VadPurpose.SEGMENTATION && com.autovoice.audiofrontend.vad.VadEvent.SpeechEnd in it.events &&
            it.startSample+it.validSamples > auxiliaryConfirmedAtSample
    }?.let { it.startSample+it.validSamples }
}

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
    /** True only while processed capture has a usable, current echo cancellation reference. */
    val echoCancellationActive: Boolean get() = false
    /** Persistent signal processing continues outside business inputs. Legacy remains turn-only. */
    val continuousProcessing: Boolean get() = false
    val vadAvailable: Boolean
    val diagnostics: AudioFrontendDiagnostics

    fun startTurn()
    /** Already processed continuous pre-roll; never send it through DSP again. Legacy accepts empty only. */
    fun startTurn(history: AudioInputHistory) { require(history.batch.processedBlocks.isEmpty());startTurn() }
    /** Independent auxiliary state only; never reset APM or another purpose. */
    fun resetVad(purpose: VadPurpose) = Unit
    fun process(block: ByteArray): FrontendBatch
    /** Flushes the sub-frame tail while preserving the exact input sample count. */
    fun finishProcessedAudio(): ByteArray
    /** Normal input completion: flush before finishSegments; device cancellation discards pending input. */
    fun finishBatch(): FrontendBatch = AudioFrontendFrame(finishProcessedAudio(), null)
    fun finishSegments(): List<ByteArray>
    /** Discard only the unfinished input; persistent DSP and pending real samples remain intact. */
    fun abortInput() = Unit
}

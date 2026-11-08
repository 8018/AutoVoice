package com.autovoice.audiofrontend

import com.autovoice.audiofrontend.vad.VadEvent

/** Capture/render positions count real samples, never DSP padding. */
data class PcmFormat(val sampleRate: Int = 16000, val channels: Int = 1, val bitsPerSample: Int = 16)
enum class VadPurpose { SEGMENTATION, FOLLOW_UP, BARGE_IN }
data class PositionedPcmBlock(val startSample: Long, val pcm: ByteArray, val validSamples: Int = pcm.size / 2,
    val format: PcmFormat = PcmFormat()) {
    init { require(startSample >= 0 && validSamples >= 0 && format.channels > 0 && format.bitsPerSample == 16)
        require(validSamples.toLong() * format.channels * 2 == pcm.size.toLong()) }
}
data class VadObservation(val startSample: Long, val validSamples: Int, val speechDetected: Boolean?,
    val probability: Float? = null, val events: List<VadEvent> = emptyList(), val purpose: VadPurpose = VadPurpose.SEGMENTATION)
data class FrontendBatch(val processedBlocks: List<PositionedPcmBlock>, val vadObservations: List<VadObservation>) {
    /** Source compatibility for the historical single-event legacy fixtures. */
    constructor(processedPcm: ByteArray, vadEvent: VadEvent?) : this(
        if (processedPcm.isEmpty()) emptyList() else listOf(PositionedPcmBlock(0, processedPcm)),
        if (vadEvent == null) emptyList() else listOf(VadObservation(0, 512, null, events = listOf(vadEvent))))
    val processedPcm: ByteArray get() {
        val output = ByteArray(processedBlocks.sumOf { it.pcm.size });var offset = 0
        for (block in processedBlocks) { block.pcm.copyInto(output, offset);offset += block.pcm.size }
        return output
    }
    /** Legacy-only view: never silently drop finer-grained Open events. */
    val vadEvent: VadEvent? get() {
        val events = vadObservations.filter { it.purpose == VadPurpose.SEGMENTATION }.flatMap { it.events }
        check(events.size <= 1) { "consume all ordered FrontendBatch observations" }
        return events.singleOrNull()
    }
    companion object { val EMPTY = FrontendBatch(emptyList(), emptyList()) }
}
typealias AudioFrontendFrame = FrontendBatch

data class RenderReferenceFrame(val playbackId: Long, val startSample: Long, val timestampNanos: Long,
    val pcm: ByteArray, val format: PcmFormat = PcmFormat(), val validSamples: Int = pcm.size / 2)
interface RenderReferenceSink {
    /** False explicitly rejects a stale, overflowing or unusable reference. */
    fun acceptRender(frame: RenderReferenceFrame): Boolean
    fun invalidateRender(playbackId: Long)
}

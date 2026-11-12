package com.autovoice.audiofrontend

/** Effects owned by the selected provider and attached to the single AudioRecord session. */
interface CaptureEffects : AutoCloseable {
    val echoCancellationActive: Boolean
    val noiseSuppressionActive: Boolean
    fun attach(audioSessionId: Int)
    fun release()
    override fun close() = release()
}

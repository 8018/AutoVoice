package com.autovoice.audiofrontend

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/** AudioRecord-session front-end effects. Kept here with VAD and software signal processing. */
interface CaptureEffects : AutoCloseable {
    val echoCancellationActive: Boolean
    val noiseSuppressionActive: Boolean
    fun attach(audioSessionId: Int)
    fun release()
    override fun close() = release()
}

class AndroidCaptureEffects : CaptureEffects {
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    override val echoCancellationActive: Boolean get() = echoCanceler?.enabled == true
    override val noiseSuppressionActive: Boolean get() = noiseSuppressor?.enabled == true

    override fun attach(audioSessionId: Int) {
        release()
        echoCanceler = if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(audioSessionId)?.also { it.enabled = true } }
                .onFailure { Log.w(TAG, "AEC initialization failed", it) }
                .getOrNull()
        } else {
            null
        }
        noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(audioSessionId)?.also { it.enabled = true } }
                .onFailure { Log.w(TAG, "platform noise suppression initialization failed", it) }
                .getOrNull()
        } else {
            null
        }
    }

    override fun release() {
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
    }

    private companion object {
        const val TAG = "AndroidCaptureEffects"
    }
}

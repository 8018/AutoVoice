package com.autovoice.adapterwebrtc

import com.autovoice.audiofrontend.VadPurpose

internal data class CaptureResult(val pcm: ShortArray, val speech: Map<VadPurpose, Boolean>)
/** Tests may inject framing fixtures; production uses only the actual JNI backend. */
internal interface WebRtcBackend : AutoCloseable {
    fun processCapture(input: ShortArray, delayMs: Int, validSamples: Int): CaptureResult
    fun processRender(input: ShortArray)
    fun resetVad(purpose: VadPurpose)
}

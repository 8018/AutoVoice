package com.autovoice.adapterwebrtc

import com.autovoice.audiofrontend.VadPurpose

/** Fixed 16kHz mono PCM16, 160 samples. No native types cross this JNI boundary. */
internal object WebRtcNative {
    init { System.loadLibrary("autovoice_frontend_jni") }
    @JvmStatic external fun create(aggressiveness: Int): Long
    @JvmStatic external fun processCapture(handle: Long, input: ShortArray, output: ShortArray, decisions: IntArray, delayMs: Int, validSamples: Int): Int
    @JvmStatic external fun processRender(handle: Long, input: ShortArray): Int
    @JvmStatic external fun resetVad(handle: Long, purpose: Int): Int
    @JvmStatic external fun release(handle: Long)
}
internal class JniWebRtcBackend(aggressiveness: Int) : WebRtcBackend {
    private var handle = WebRtcNative.create(aggressiveness).also { check(it != 0L) { "WebRTC session creation failed" } }
    @Synchronized override fun processCapture(input: ShortArray, delayMs: Int, validSamples: Int): CaptureResult {
        check(handle != 0L)
        val output = ShortArray(160);val decisions = IntArray(3)
        check(WebRtcNative.processCapture(handle,input,output,decisions,delayMs,validSamples) == 0)
        return CaptureResult(output, if (validSamples < 160) emptyMap() else VadPurpose.entries.associateWith { decisions[it.ordinal] == 1 })
    }
    @Synchronized override fun processRender(input: ShortArray) { check(handle != 0L);check(WebRtcNative.processRender(handle,input) == 0) }
    @Synchronized override fun resetVad(purpose: VadPurpose) { check(handle != 0L);check(WebRtcNative.resetVad(handle,purpose.ordinal) == 0) }
    @Synchronized override fun close() { if (handle != 0L) { WebRtcNative.release(handle);handle = 0 } }
}

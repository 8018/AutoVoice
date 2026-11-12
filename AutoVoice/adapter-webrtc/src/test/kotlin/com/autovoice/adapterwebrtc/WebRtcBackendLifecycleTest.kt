package com.autovoice.adapterwebrtc
import com.autovoice.audiofrontend.VadPurpose
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class WebRtcBackendLifecycleTest {
    @Test fun `native factory processing reset and release have one owned thread`() {
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String,Long>>())
        fun mark(name: String) { calls += name to Thread.currentThread().id }
        val b = SingleThreadWebRtcBackend {
            mark("create")
            object : WebRtcBackend {
                override fun processCapture(input: ShortArray, delayMs: Int, validSamples: Int): CaptureResult {
                    mark("capture");return CaptureResult(input,emptyMap())
                }
                override fun processRender(input: ShortArray) { mark("render") }
                override fun resetVad(purpose: VadPurpose) { mark("reset") }
                override fun close() { mark("close") }
            }
        }
        b.processCapture(ShortArray(160),0,160);b.processRender(ShortArray(160));b.resetVad(VadPurpose.BARGE_IN)
        b.close();b.close()
        assertEquals(listOf("create","capture","render","reset","close"),calls.map { it.first })
        assertEquals(1,calls.map { it.second }.distinct().size)
        assertNotEquals(Thread.currentThread().id,calls.first().second)
        assertThrows(IllegalStateException::class.java) { b.processCapture(ShortArray(160),0,160) }
        assertEquals(5,calls.size)
    }
}

package com.autovoice.adapterfirered

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SherpaDecoderTest {
    @Test fun `stream releases exactly once on success accept failure and decode failure`() {
        for (failure in listOf("none","accept","decode")) {
            var streams = 0;var releases = 0;var modelReleases = 0
            val decoder = SherpaDecoder(object : FireRedRecognizer {
                override fun stream(): FireRedStream { streams++;return object : FireRedStream {
                    override fun accept(samples: FloatArray) { if (failure == "accept") error("accept failure") }
                    override fun close() { releases++ }
                } }
                override fun decode(stream: FireRedStream): String { if (failure == "decode") error("decode failure");return "关闭车窗" }
                override fun close() { modelReleases++ }
            })
            if (failure == "none") assertEquals("关闭车窗",decoder.decode(floatArrayOf(0f)))
            else assertThrows(IllegalStateException::class.java) { decoder.decode(floatArrayOf(0f)) }
            assertEquals(1,streams);assertEquals(1,releases);assertEquals(0,modelReleases)
            decoder.close();assertEquals(1,modelReleases)
        }
    }
}

package com.autovoice.app.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Task 18 纯 JVM 测试：wav 头（RIFF 结构断言）。PCM 前端测试属于 :audio-frontend。
 *
 * 真实 AudioRecord / MediaPlayer / TextToSpeech 无 JVM 测试——
 * 由 :app:assembleDebug 编译验证 + Task 22 真机验证。
 */
class AudioRecorderFormatTest {

    // ---------------------------------------------------------------- WAV 头

    @Test
    fun `wav header is 44 bytes with full RIFF structure`() {
        val h = WavHeader.write(dataSize = 1000, sampleRate = 16000, channels = 1, bitsPerSample = 16)
        assertEquals(44, h.size)
        assertEquals("RIFF", ascii(h, 0, 4))
        // RIFF chunk size = 36 + dataSize（data chunk 前的固定开销）
        assertEquals(36L + 1000L, u32le(h, 4))
        assertEquals("WAVE", ascii(h, 8, 4))
        assertEquals("fmt ", ascii(h, 12, 4))
        assertEquals(16L, u32le(h, 16)) // fmt chunk 大小
        assertEquals(1, u16le(h, 20)) // 编码 = PCM
        assertEquals(1, u16le(h, 22)) // 单声道
        assertEquals(16000L, u32le(h, 24)) // sampleRate
        assertEquals(32000L, u32le(h, 28)) // byteRate = 16000 * 1 * 16 / 8
        assertEquals(2, u16le(h, 32)) // blockAlign = 1 * 16 / 8
        assertEquals(16, u16le(h, 34)) // bitsPerSample
        assertEquals("data", ascii(h, 36, 4))
        assertEquals(1000L, u32le(h, 40)) // data 区字节数
    }

    @Test
    fun `wav header defaults match 16k mono 16bit pcm`() {
        val h = WavHeader.write(dataSize = 0)
        assertEquals(44, h.size)
        assertEquals(36L, u32le(h, 4))
        assertEquals(16000L, u32le(h, 24))
        assertEquals(32000L, u32le(h, 28))
        assertEquals(1, u16le(h, 22))
        assertEquals(16, u16le(h, 34))
    }

    // ---------------------------------------------------------------- 工具

    private fun ascii(b: ByteArray, off: Int, len: Int): String =
        b.copyOfRange(off, off + len).toString(Charsets.US_ASCII)

    private fun u16le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32le(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
}

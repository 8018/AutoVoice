package com.autovoice.app.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Task 18 纯 JVM 测试：wav 头（RIFF 结构断言）+ PCM 块切分/尾帧对齐（RNNoise 480 网格）。
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

    // ------------------------------------------------------------- PCM 网格

    @Test
    fun `1024 byte block converts to 512 little-endian samples`() {
        val block = ByteArray(1024)
        block[0] = 0x34
        block[1] = 0x12 // sample0 = 0x1234
        block[2] = 0xFF.toByte()
        block[3] = 0xFF.toByte() // sample1 = -1
        block[4] = 0x00
        block[5] = 0x80.toByte() // sample2 = -32768
        val samples = pcm16BytesToShorts(block)
        assertEquals(512, samples.size)
        assertEquals(0x1234.toShort(), samples[0])
        assertEquals((-1).toShort(), samples[1])
        assertEquals(Short.MIN_VALUE, samples[2])
    }

    @Test
    fun `denoise grid takes first 480 samples and drops 32-sample tail`() {
        val block = ByteArray(1024)
        for (i in 0 until 512) {
            block[i * 2] = (i and 0xFF).toByte()
            block[i * 2 + 1] = ((i shr 8) and 0xFF).toByte()
        }
        val frame = first480Frame(pcm16BytesToShorts(block))
        assertEquals(480, frame.size)
        assertEquals(0.toShort(), frame[0])
        assertEquals(479.toShort(), frame[479])
        // 尾 32 samples（480..511）不进降噪网格
        assertFalse(frame.any { it.toInt() and 0xFFFF == 480 })
    }

    @Test
    fun `denoised grid output is 960 bytes per block`() {
        val block = ByteArray(1024) { it.toByte() }
        val out = pcm16ShortsToBytes(first480Frame(pcm16BytesToShorts(block)))
        assertEquals(960, out.size)
        // 回程 little-endian：out[0] 是帧首 sample 的低字节
        assertEquals(block[0], out[0])
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

package com.autovoice.app.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WavHeader.fix：DashScope sambert 返回的 wav 头尺寸字段错误（RIFF chunkSize/dataSize
 * 声明 ~2GB 而实际仅几十 KB）→ MediaPlayer 读到 EOF 提前截断播报。纯 JVM 验证修复逻辑。
 */
class WavHeaderTest {

    /** 构造标准 wav + 篡改尺寸字段为设备实采的垃圾值（0x7FFFFFBF / 0x7FFFFF9B）。 */
    private fun brokenHeaderWav(dataSize: Int = 8000): ByteArray {
        val wav = WavHeader.write(dataSize) + ByteArray(dataSize) // 标准 44 头 + 数据
        wav[4] = 0xBF.toByte(); wav[5] = 0xFF.toByte(); wav[6] = 0xFF.toByte(); wav[7] = 0x7F.toByte()
        wav[40] = 0x9B.toByte(); wav[41] = 0xFF.toByte(); wav[42] = 0xFF.toByte(); wav[43] = 0x7F.toByte()
        return wav
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    @Test
    fun `broken header sizes get rewritten to actual data length`() {
        val fixed = WavHeader.fix(brokenHeaderWav(8000))
        assertEquals(8000L, readU32(fixed, 40), "dataSize 应按实际数据长度重写")
        assertEquals(36L + 8000, readU32(fixed, 4), "RIFF chunkSize = 36 + dataSize")
        assertEquals("RIFF", String(fixed, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(fixed, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(fixed, 36, 4, Charsets.US_ASCII))
    }

    @Test
    fun `correct header is returned unchanged`() {
        val wav = WavHeader.write(8000) + ByteArray(8000)
        val fixed = WavHeader.fix(wav)
        assertSame(wav, fixed, "头已正确时不复制，原样返回")
        assertEquals(8000L, readU32(fixed, 40))
    }

    @Test
    fun `non-wav data passes through untouched`() {
        val pcm = ByteArray(1000) { it.toByte() }
        assertSame(pcm, WavHeader.fix(pcm), "无 RIFF 标记的数据原样返回")
    }

    @Test
    fun `data shorter than header passes through untouched`() {
        val tiny = byteArrayOf(1, 2, 3)
        assertSame(tiny, WavHeader.fix(tiny), "<44 字节原样返回")
        assertTrue(WavHeader.fix(brokenHeaderWav(10)).size == 10 + 44)
    }
}

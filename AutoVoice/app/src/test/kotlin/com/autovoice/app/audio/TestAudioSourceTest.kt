package com.autovoice.app.audio

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 测试音频源纯逻辑（Task 58）：循环切块、块尾无缝回绕续读。
 * 不触碰 Android（不构造 [TestAudioSource.fromDemoConfig]，只测 [TestAudioSource.nextBlock]）。
 */
class TestAudioSourceTest {

    @Test
    fun `nextBlock cuts 1024B blocks and wraps seamlessly at the tail`() {
        // 2000B 源 = 完整 1024B 块 + 976B 尾块（尾块后 48B 无缝回绕续读源开头）
        val pcm = ByteArray(2000) { i -> (i % 251).toByte() }
        val source = TestAudioSource(pcm)

        val b1 = ByteArray(AudioFormat.BLOCK_BYTES)
        source.nextBlock(b1)
        assertArrayEquals(pcm.copyOfRange(0, 1024), b1, "块 1 = 源前 1024B")

        val b2 = ByteArray(AudioFormat.BLOCK_BYTES)
        source.nextBlock(b2)
        assertArrayEquals(pcm.copyOfRange(1024, 2000), b2.copyOfRange(0, 976), "块 2 前 976B = 源尾")
        assertArrayEquals(pcm.copyOfRange(0, 48), b2.copyOfRange(976, 1024), "块 2 后 48B = 回绕续读源开头 48B")

        val b3 = ByteArray(AudioFormat.BLOCK_BYTES)
        source.nextBlock(b3)
        assertArrayEquals(pcm.copyOfRange(48, 1024), b3.copyOfRange(0, 976), "块 3 从源 48B 处续读（游标连续）")
    }

    @Test
    fun `nextBlock wraps around cleanly on exact block boundary`() {
        val pcm = ByteArray(2048) { i -> (i % 251).toByte() } // 恰好 2 块
        val source = TestAudioSource(pcm)

        val b1 = ByteArray(AudioFormat.BLOCK_BYTES)
        source.nextBlock(b1)
        val b2 = ByteArray(AudioFormat.BLOCK_BYTES)
        source.nextBlock(b2)
        val b3 = ByteArray(AudioFormat.BLOCK_BYTES)
        source.nextBlock(b3) // 第 3 块回绕：从头再切
        assertArrayEquals(pcm.copyOfRange(0, 1024), b3, "块 3 回绕到源开头")
        assertEquals("64ms 预置语音", source.describe()) // 2048B = 64ms @16k
    }

    @Test
    fun `reset rewinds cursor to start of source`() {
        // 回归（Task 58 联调）：游标跨轮不重置会让每轮按下相位随机、VAD 段起点随机
        val pcm = ByteArray(2048) { i -> (i % 251).toByte() }
        val source = TestAudioSource(pcm)

        val b1 = ByteArray(AudioFormat.BLOCK_BYTES)
        source.nextBlock(b1)
        val b2 = ByteArray(AudioFormat.BLOCK_BYTES)
        source.nextBlock(b2) // 游标已回绕到 0
        source.reset() // 显式重置
        val afterReset = ByteArray(AudioFormat.BLOCK_BYTES)
        source.nextBlock(afterReset)
        assertArrayEquals(pcm.copyOfRange(0, 1024), afterReset, "reset 后从源开头重播")
    }
}

package com.autovoice.adapterlocal.ecnr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RnnoiseChunkingTest {

    @Test
    fun `chunks pcm into 480-sample frames`() {
        val pcm = ShortArray(RnnoiseProcessor.FRAME_SIZE * 3) { (it % 32767).toShort() }
        val frames = RnnoiseProcessor().chunk(pcm)
        assertEquals(3, frames.size)
        assertTrue(frames.all { it.size == RnnoiseProcessor.FRAME_SIZE })
        assertTrue(frames[1].contentEquals(pcm.copyOfRange(480, 960)))
    }

    @Test
    fun `drops trailing partial frame`() {
        val pcm = ShortArray(RnnoiseProcessor.FRAME_SIZE * 2 + 100) { 7 }
        val frames = RnnoiseProcessor().chunk(pcm)
        assertEquals(2, frames.size)
        assertEquals(480, frames[0].size)
        assertEquals(480, frames[1].size)
    }

    @Test
    fun `empty pcm yields no frames`() {
        assertEquals(0, RnnoiseProcessor().chunk(ShortArray(0)).size)
    }
}

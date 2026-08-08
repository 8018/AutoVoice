package com.autovoice.adapteriflytek

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FakeCommandAsrProviderTest {
    @Test
    fun `recognize returns fixed command for non-empty pcm`() {
        // 16bit/16K 单声道 pcm 一帧
        val pcm = ByteArray(320)
        assertEquals("打开空调", FakeCommandAsrProvider.recognize(pcm))
    }

    @Test
    fun `recognize returns null for empty pcm`() {
        // 空输入视为无有效语音（与真实引擎 VAD 未检出语音的行为对齐）
        assertNull(FakeCommandAsrProvider.recognize(ByteArray(0)))
    }
}

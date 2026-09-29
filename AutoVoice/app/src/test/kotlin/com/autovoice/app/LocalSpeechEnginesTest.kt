package com.autovoice.app

import com.autovoice.voicecore.AsrSink
import com.autovoice.voicecore.DemoConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocalSpeechEnginesTest {
    @Test
    fun `2C command text belongs to NLU and does not masquerade as ASR`() = runBlocking {
        val audio = byteArrayOf(1, 2)
        assertNull(LocalAsrEngine().recognize("turn-1", audio, AsrSink.NOOP))

        val nlu = LocalNluEngine(DemoConfig.LOCAL_ASR_IFLYTEK) { "打开车窗" }
        val result = nlu.understand("turn-1", audio, null)
        assertEquals("打开车窗", result.recognizedText)
        assertEquals("window", result.intent.domain)
    }
}

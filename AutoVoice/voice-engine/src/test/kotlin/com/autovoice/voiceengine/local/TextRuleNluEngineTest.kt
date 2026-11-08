package com.autovoice.voiceengine.local
import com.autovoice.voicecore.AsrResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class TextRuleNluEngineTest {
    @Test fun `nlu consumes final only and never treats pcm or partial text as recognition`() = runBlocking {
        val nlu = TextRuleNluEngine()
        assertTrue(nlu.understand("turn",byteArrayOf(1,2),null).intent.isUnknown())
        assertTrue(nlu.understand("turn",byteArrayOf(1,2),AsrResult("打开车窗",false)).intent.isUnknown())
        assertTrue(nlu.understand("turn",byteArrayOf(1,2),AsrResult("不要打开车窗",true)).intent.isUnknown())
        val result = nlu.understand("turn",byteArrayOf(1,2),AsrResult("打开车窗",true))
        assertEquals("power_on",result.intent.intent)
        assertNull(result.recognizedText) // ASR owns the sole transcript output.
    }
}

package com.autovoice.voiceengine.local

import com.autovoice.voicecore.AsrSink
import com.autovoice.voicecore.Intent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocalSpeechEnginesTest {
    @Test
    fun `2C command text belongs to NLU and does not masquerade as ASR`() = runBlocking {
        val audio = byteArrayOf(1, 2)
        assertNull(LocalAsrEngine().recognize("turn-1", audio, AsrSink.NOOP))

        val nlu = LocalNluEngine(
            recognizeCommand = { "打开车窗" },
            understandCommand = { Intent("1.0", "window", "open", emptyMap(), 1.0, "test") },
        )
        val result = nlu.understand("turn-1", audio, null)
        assertEquals("打开车窗", result.recognizedText)
        assertEquals("window", result.intent.domain)
    }

    @Test
    fun `vendor failure becomes unknown semantic without inventing transcript`() = runBlocking {
        var failures = 0
        val nlu = LocalNluEngine(
            recognizeCommand = { throw IllegalStateException("SDK unavailable") },
            understandCommand = { Intent.unknown("vehicle") },
            onRecognizerFailure = { failures++ },
        )
        val result = nlu.understand("turn-2", byteArrayOf(1), null)
        assertEquals(1, failures)
        assertNull(result.recognizedText)
        assertEquals("unknown", result.intent.intent)
    }
}

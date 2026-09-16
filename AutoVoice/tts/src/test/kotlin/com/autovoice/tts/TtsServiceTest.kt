package com.autovoice.tts

import com.autovoice.voicecore.AudioReply
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TtsServiceTest {
    @Test
    fun `generation is cached behind the service boundary`() = runTest {
        var generated = 0
        val service = createTtsService(
            synthesizer = TtsSynthesizer { text, _ ->
                generated++
                AudioReply("audio/wav", byteArrayOf(1), text)
            },
            cacheDir = null,
        )

        service.audioFor("hello", "turn-1")
        service.audioFor("hello", "turn-2")

        assertEquals(1, generated)
    }
}

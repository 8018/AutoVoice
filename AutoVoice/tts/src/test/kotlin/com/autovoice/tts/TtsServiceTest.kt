package com.autovoice.tts

import com.autovoice.voicecore.AudioReply
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `output owns generation cache and playback identity`() = runTest {
        var generated = 0
        val played = mutableListOf<Pair<AudioReply, PlaybackIdentity>>()
        val events = mutableListOf<String>()
        val output = createTtsOutput(
            synthesizer = TtsSynthesizer { text, _ ->
                generated++
                AudioReply("audio/wav", byteArrayOf(1), text)
            },
            cacheDir = null,
            driver = TtsPlaybackDriver { reply, identity ->
                played += reply to identity
            },
            scope = this,
            isCurrentTurn = { true },
            events = TtsEventSink { event, _, _ -> events += event },
        )

        output.speak("turn-1", "hello")
        testScheduler.advanceUntilIdle()
        output.speak("turn-2", "hello")
        testScheduler.advanceUntilIdle()

        assertEquals(1, generated)
        assertEquals(listOf("turn-1", "turn-2"), played.map { it.second.turnId })
        assertTrue("tts_cache_hit" in events)
    }
}

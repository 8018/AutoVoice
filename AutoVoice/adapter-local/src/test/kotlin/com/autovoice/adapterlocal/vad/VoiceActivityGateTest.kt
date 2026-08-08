package com.autovoice.adapterlocal.vad

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VoiceActivityGateTest {
    @Test
    fun `speech start after two hot frames`() {
        val g = VoiceActivityGate(threshold = 0.5f, minSpeechMs = 64, minSilenceMs = 960)
        assertEquals(null, g.feed(0.9f))
        assertEquals(VadEvent.SpeechStart, g.feed(0.9f))
    }

    @Test
    fun `speech end after min silence`() {
        val g = VoiceActivityGate(threshold = 0.5f, minSpeechMs = 64, minSilenceMs = 960)
        g.feed(0.9f); g.feed(0.9f) // SpeechStart
        repeat(29) { assertNull(g.feed(0.1f)) }   // 29*32ms=928ms < 960ms
        assertEquals(VadEvent.SpeechEnd, g.feed(0.1f)) // 30*32ms=960ms
    }

    @Test
    fun `no speech for quiet frames`() {
        val g = VoiceActivityGate(threshold = 0.5f, minSpeechMs = 64, minSilenceMs = 960)
        repeat(100) { assertNull(g.feed(0.1f)) }
    }
}

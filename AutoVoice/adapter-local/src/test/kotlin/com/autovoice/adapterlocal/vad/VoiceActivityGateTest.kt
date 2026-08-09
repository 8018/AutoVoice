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

    @Test
    fun `reset clears in-speech residue so next turn fires SpeechStart again`() {
        // 回归：上一轮 SpeechEnd 静音不足时 inSpeech 残留 true，下一轮语音被当成仍在说话，
        // SpeechStart 永不触发 → 段切不出来。reset 后应回到干净状态。
        val g = VoiceActivityGate(threshold = 0.5f, minSpeechMs = 64, minSilenceMs = 960)
        g.feed(0.9f); g.feed(0.9f) // SpeechStart，进入 inSpeech
        // 少量静音（< 960ms）后直接开始新一轮：未触发 SpeechEnd，inSpeech 残留
        repeat(5) { assertNull(g.feed(0.1f)) }

        g.reset()

        assertEquals(null, g.feed(0.9f), "reset 后热帧计数清零，首帧不触发")
        assertEquals(VadEvent.SpeechStart, g.feed(0.9f), "reset 后新一轮语音应重新触发 SpeechStart")
        assertEquals(false, g.feed(0.1f) == VadEvent.SpeechEnd, "新一轮不应立即收到上一轮的 SpeechEnd")
    }
}

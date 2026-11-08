package com.autovoice.voicecore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class WebRtcConfigOptionsTest {
    private fun json(options: String) = """{"mode":"offline","frontend":"webrtc.apm","ecnr":"none","vad":{"provider":"webrtc.vad"$options},"local":{"asr":"firered.ctc.int8","nlu":"rule.text"},"cloud":{"enabled":false,"gatewayUrl":"","waitMs":3000}}"""
    @Test fun `explicit legacy probability presence survives parsing even at historical default`() {
        assertFalse(DemoConfig.fromJson(json("")).vad.thresholdProvided)
        assertTrue(DemoConfig.fromJson(json(",\"threshold\":0.5")).vad.thresholdProvided)
        assertEquals(3, DemoConfig.fromJson(json(",\"aggressiveness\":3")).vad.aggressiveness)
    }
    @Test fun `webrtc aggressiveness is an integer mode rather than a probability`() {
        for (value in listOf("-1","4","1.5","\"2\"")) assertThrows(IllegalArgumentException::class.java) {
            DemoConfig.fromJson(json(",\"aggressiveness\":" + value))
        }
    }
}

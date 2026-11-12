package com.autovoice.voicecore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConfigProviderParsingTest {
    // Catches core parsing rejecting another channel before its composition root can validate it.
    @Test
    fun `open provider configuration parses before channel capability validation`() {
        val cfg = DemoConfig.fromJson("""{"mode":"offline","frontend":"webrtc.apm",
            "vad":{"provider":"webrtc.vad"},"ecnr":"none",
            "local":{"asr":"firered.ctc.int8","nlu":"rule.text"},
            "cloud":{"enabled":false,"gatewayUrl":"","waitMs":3000}}""")
        assertEquals("firered.ctc.int8", cfg.local.asr)
        assertEquals("rule.text", cfg.local.nlu)
    }

    @Test
    fun `unknown provider tokens are preserved for selected channel validation`() {
        val cfg = DemoConfig.fromJson("""{"mode":"offline","ecnr":"unknown",
            "local":{"asr":"unknown","nlu":"unknown"},
            "cloud":{"enabled":false,"gatewayUrl":"","waitMs":3000}}""")
        assertEquals("unknown", cfg.ecnr)
        assertEquals("unknown", cfg.local.asr)
        assertEquals("unknown", cfg.local.nlu)
    }
}

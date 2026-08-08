package com.autovoice.voicecore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigTest {
    @Test
    fun `parses demo full config`() {
        val json = """
            {"mode":"full","vad":{"threshold":0.5,"minSpeechMs":64,"minSilenceMs":960},
             "ecnr":"rnnoise","local":{"asr":"iflytek.offline-cmd","nlu":"rule"},
             "cloud":{"enabled":true,"gatewayUrl":"ws://192.168.1.1:8080/ws","waitMs":2000},
             "mock":{"executor":true}}""".trimIndent()
        val cfg = DemoConfig.fromJson(json)
        assertTrue(cfg.cloud.enabled)
        assertEquals(2000, cfg.cloud.waitMs)
    }

    @Test
    fun `parses demo offline config`() {
        val json = """{"mode":"offline","vad":{"threshold":0.5,"minSpeechMs":64,"minSilenceMs":960},
             "ecnr":"rnnoise","local":{"asr":"iflytek.offline-cmd","nlu":"rule"},
             "cloud":{"enabled":false,"gatewayUrl":"","waitMs":2000},
             "mock":{"executor":true}}""".trimIndent()
        val cfg = DemoConfig.fromJson(json)
        assertFalse(cfg.cloud.enabled)
    }

    @Test
    fun `unknown fields ignored`() {
        val cfg = DemoConfig.fromJson(
            """{"mode":"full","vad":{"threshold":0.5},"ecnr":"rnnoise",
               "local":{"asr":"a","nlu":"b"},
               "cloud":{"enabled":false,"gatewayUrl":"","waitMs":100},
               "mock":{"executor":false},"futureField":123}""",
        )
        assertEquals("full", cfg.mode)
        assertFalse(cfg.cloud.enabled)
    }

    @Test
    fun `missing required fields rejected`() {
        // schema required = [mode, cloud, local]
        assertThrows(IllegalArgumentException::class.java) {
            DemoConfig.fromJson("""{"mode":"full","local":{"asr":"a","nlu":"b"}}""")
        }
        // cloud.enabled / cloud.waitMs 必读
        assertThrows(IllegalArgumentException::class.java) {
            DemoConfig.fromJson(
                """{"mode":"full","local":{"asr":"a","nlu":"b"},"cloud":{"gatewayUrl":""}}""",
            )
        }
    }
}

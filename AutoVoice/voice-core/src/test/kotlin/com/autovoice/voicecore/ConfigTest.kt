package com.autovoice.voicecore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `parses cloud telemetry segment`() {
        // T6：cloud.telemetry 可选段——enabled 控制遥测开关，url 指定数据平台 HTTP 基址
        val cfg = DemoConfig.fromJson(
            """{"mode":"full","vad":{"threshold":0.5},"ecnr":"rnnoise",
               "local":{"asr":"a","nlu":"b"},
               "cloud":{"enabled":true,"gatewayUrl":"ws://h:8080/ws","waitMs":2000,
                        "telemetry":{"enabled":true,"url":"http://telemetry:9090"}},
               "mock":{"executor":false}}""",
        )
        val telemetry = cfg.cloud.telemetry
        assertNotNull(telemetry, "cloud.telemetry 配置后不得为 null")
        assertTrue(telemetry!!.enabled)
        assertEquals("http://telemetry:9090", telemetry.url)
    }

    @Test
    fun `telemetry absent or blank url stays disabled`() {
        val noTelemetry = DemoConfig.fromJson(
            """{"mode":"full","vad":{"threshold":0.5},"ecnr":"rnnoise",
               "local":{"asr":"a","nlu":"b"},
               "cloud":{"enabled":true,"gatewayUrl":"ws://h:8080/ws","waitMs":2000},
               "mock":{"executor":false}}""",
        )
        assertNull(noTelemetry.cloud.telemetry, "未配置 telemetry 段 → null（遥测关闭）")

        val blankUrl = DemoConfig.fromJson(
            """{"mode":"full","vad":{"threshold":0.5},"ecnr":"rnnoise",
               "local":{"asr":"a","nlu":"b"},
               "cloud":{"enabled":true,"gatewayUrl":"ws://h:8080/ws","waitMs":2000,
                        "telemetry":{"enabled":true,"url":""}},
               "mock":{"executor":false}}""",
        )
        assertTrue(blankUrl.cloud.telemetry!!.enabled)
        assertNull(blankUrl.cloud.telemetry!!.url, "url 空白 → null（回落网关地址推导）")
    }
}

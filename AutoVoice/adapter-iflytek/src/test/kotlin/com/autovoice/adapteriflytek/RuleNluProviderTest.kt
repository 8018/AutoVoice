package com.autovoice.adapteriflytek

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuleNluProviderTest {
    @Test
    fun `window command maps to window`() {
        assertEquals("window", RuleNluProvider.understand("打开车窗").domain)
    }

    @Test
    fun `window off command maps to window power off`() {
        val i = RuleNluProvider.understand("关闭车窗")
        assertEquals("window", i.domain)
        assertEquals("power_off", i.intent)
    }

    @Test
    fun `unknown for out-of-scope`() {
        assertTrue(RuleNluProvider.understand("讲个笑话").isUnknown())
    }

    // ------------------------------------------------------ 能力分级（2026-08-15）：空调归云端命令词

    /** 空调别名已删除 → 即使包含“打开”也必须拒识，不能生成不可执行的 misc 意图。 */
    @Test
    fun `ac on command is unknown on-device`() {
        assertTrue(RuleNluProvider.understand("打开空调").isUnknown())
    }

    /** set_temperature 规则已删除 → 空调调温端侧不识别（unknown，拒识/只等云端）。 */
    @Test
    fun `temperature command is unknown on-device`() {
        assertTrue(RuleNluProvider.understand("空调调到24度").isUnknown())
    }

    @Test
    fun `ac off command is unknown on-device`() {
        assertTrue(RuleNluProvider.understand("关闭空调").isUnknown())
    }

    /** SDK 偶发误识别出动作词时，缺少受支持领域仍不能参与端侧仲裁。 */
    @Test
    fun `weather query is unknown on-device`() {
        assertTrue(RuleNluProvider.understand("今天天气").isUnknown())
    }

    @Test
    fun `调至 variant is unknown on-device`() {
        assertTrue(RuleNluProvider.understand("空调调至25度").isUnknown())
    }
}

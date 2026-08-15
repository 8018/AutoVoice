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

    /** 空调别名已删除 → "打开空调" 兜底 misc/power_on（非车窗，不触发端侧直接胜出）。 */
    @Test
    fun `ac on command falls back to misc power on`() {
        val i = RuleNluProvider.understand("打开空调")
        assertEquals("misc", i.domain)
        assertEquals("power_on", i.intent)
    }

    /** set_temperature 规则已删除 → 空调调温端侧不识别（unknown，拒识/只等云端）。 */
    @Test
    fun `temperature command is unknown on-device`() {
        assertTrue(RuleNluProvider.understand("空调调到24度").isUnknown())
    }

    @Test
    fun `ac off command falls back to misc power off`() {
        val i = RuleNluProvider.understand("关闭空调")
        assertEquals("misc", i.domain)
        assertEquals("power_off", i.intent)
    }

    @Test
    fun `调至 variant is unknown on-device`() {
        assertTrue(RuleNluProvider.understand("空调调至25度").isUnknown())
    }
}

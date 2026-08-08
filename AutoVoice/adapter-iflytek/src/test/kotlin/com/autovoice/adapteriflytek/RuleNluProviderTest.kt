package com.autovoice.adapteriflytek

import com.autovoice.voicecore.SlotValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuleNluProviderTest {
    @Test
    fun `ac on command maps to climate power on`() {
        val i = RuleNluProvider.understand("打开空调")
        assertEquals("climate", i.domain)
        assertEquals("power_on", i.intent)
    }

    @Test
    fun `temperature command extracts slot`() {
        val i = RuleNluProvider.understand("空调调到24度")
        assertEquals("set_temperature", i.intent)
        assertEquals(24.0, (i.slots["temperature"] as SlotValue.Number).v, 0.001)
    }

    @Test
    fun `window command maps to window`() {
        assertEquals("window", RuleNluProvider.understand("打开车窗").domain)
    }

    @Test
    fun `unknown for out-of-scope`() {
        assertTrue(RuleNluProvider.understand("讲个笑话").isUnknown())
    }

    @Test
    fun `ac off command maps to climate power off`() {
        val i = RuleNluProvider.understand("关闭空调")
        assertEquals("climate", i.domain)
        assertEquals("power_off", i.intent)
    }

    @Test
    fun `window off command maps to window power off`() {
        val i = RuleNluProvider.understand("关闭车窗")
        assertEquals("window", i.domain)
        assertEquals("power_off", i.intent)
    }

    @Test
    fun `调至 variant also extracts temperature slot`() {
        val i = RuleNluProvider.understand("空调调至25度")
        assertEquals("set_temperature", i.intent)
        assertEquals(25.0, (i.slots["temperature"] as SlotValue.Number).v, 0.001)
    }
}

package com.autovoice.app

import com.autovoice.adapteriflytek.RuleNluProvider
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.SlotValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Task 19 纯 JVM 测试：模拟车控执行器状态机（空调开关/温度 + 车窗开闭）。
 *
 * 意图领域/意图名/槽位与 RuleNluProvider（adapter-iflytek）输出对齐：
 * climate/window × power_on/power_off、set_temperature（槽 temperature）。
 */
class MockVehicleStateTest {

    private fun intent(domain: String, name: String, slots: Map<String, SlotValue> = emptyMap()): Intent =
        Intent(
            schemaVersion = "1.0",
            domain = domain,
            intent = name,
            slots = slots,
            confidence = 1.0,
            source = "test",
        )

    @Test
    fun `set temperature to 24 updates temperature and announces in digits`() {
        val state = MockVehicleState()

        val text = state.apply(
            intent(
                "climate",
                "set_temperature",
                mapOf(RuleNluProvider.TEMPERATURE_SLOT to SlotValue.Number(24.0)),
            ),
        )

        assertEquals("已为您把空调调到24度", text)
        assertEquals(24.0, state.acTemperature)
        assertFalse(state.isAcOn) // 调温度不改变空调开关
    }

    @Test
    fun `climate power on turns ac on and announces`() {
        val state = MockVehicleState()

        assertEquals("已为您打开空调", state.apply(intent("climate", "power_on")))

        assertTrue(state.isAcOn)
        assertEquals(24.0, state.acTemperature) // 开关不改变温度
        assertFalse(state.isWindowsOpen)
    }

    @Test
    fun `climate power off turns ac off and announces`() {
        val state = MockVehicleState()
        state.apply(intent("climate", "power_on"))

        assertEquals("已为您关闭空调", state.apply(intent("climate", "power_off")))

        assertFalse(state.isAcOn)
    }

    @Test
    fun `unknown intent keeps state unchanged and returns null`() {
        val state = MockVehicleState()
        state.apply(intent("climate", "power_on"))
        state.apply(
            intent("climate", "set_temperature", mapOf(RuleNluProvider.TEMPERATURE_SLOT to SlotValue.Number(26.0))),
        )

        assertNull(state.apply(Intent.unknown("rule.nlu")))

        assertTrue(state.isAcOn) // 状态保持，不被未知意图清掉
        assertEquals(26.0, state.acTemperature)
        assertFalse(state.isWindowsOpen)
    }

    @Test
    fun `window open and close announce and toggle windows`() {
        val state = MockVehicleState()

        assertEquals("已为您打开车窗", state.apply(intent("window", "power_on")))
        assertTrue(state.isWindowsOpen)

        assertEquals("已为您关闭车窗", state.apply(intent("window", "power_off")))
        assertFalse(state.isWindowsOpen)
    }

    @Test
    fun `set temperature without number slot returns null and keeps state`() {
        val state = MockVehicleState()
        state.apply(intent("climate", "power_on"))

        assertNull(state.apply(intent("climate", "set_temperature"))) // 槽缺失

        assertTrue(state.isAcOn)
        assertEquals(24.0, state.acTemperature)
    }

    @Test
    fun `non numeric temperature slot is rejected`() {
        val state = MockVehicleState()

        assertNull(
            state.apply(
                intent(
                    "climate",
                    "set_temperature",
                    mapOf(RuleNluProvider.TEMPERATURE_SLOT to SlotValue.StringValue("24")),
                ),
            ),
        )
        assertEquals(24.0, state.acTemperature)
    }

    @Test
    fun `cross domain intent is ignored`() {
        val state = MockVehicleState()
        state.apply(intent("climate", "power_on"))

        // window 域不认识 set_temperature / climate 域的 power_on 不影响车窗
        assertNull(state.apply(intent("window", "set_temperature", mapOf(RuleNluProvider.TEMPERATURE_SLOT to SlotValue.Number(20.0)))))
        assertFalse(state.isWindowsOpen)
        assertTrue(state.isAcOn) // 空调保持开
        assertEquals(24.0, state.acTemperature)
    }
}

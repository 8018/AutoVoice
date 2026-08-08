package com.autovoice.app

import com.autovoice.adapteriflytek.RuleNluProvider
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.SlotValue

/**
 * 模拟车控执行器（Task 19）：空调开关 + 温度（默认 24.0）、车窗开/关（默认关）。
 *
 * [apply] 消费 canonical [Intent]（领域/意图约定与 RuleNluProvider 对齐：
 * climate/window × power_on/power_off、set_temperature，槽 temperature 为 [SlotValue.Number]），
 * 返回播报文本；未知/缺槽意图 → null（状态不变，不播报）。
 */
class MockVehicleState(
    private var acOn: Boolean = false,
    private var acTemperatureC: Double = DEFAULT_TEMPERATURE,
    private var windowsOpen: Boolean = false,
) {

    /** 空调是否开启。 */
    val isAcOn: Boolean get() = acOn

    /** 空调目标温度（默认 24.0）。 */
    val acTemperature: Double get() = acTemperatureC

    /** 车窗是否开启。 */
    val isWindowsOpen: Boolean get() = windowsOpen

    /**
     * 执行一条意图。返回播报文本；意图未知/槽缺失时返回 null 且状态不变。
     */
    fun apply(intent: Intent): String? {
        when (intent.domain) {
            DOMAIN_CLIMATE -> when (intent.intent) {
                INTENT_POWER_ON -> {
                    acOn = true
                    return "已为您打开空调"
                }
                INTENT_POWER_OFF -> {
                    acOn = false
                    return "已为您关闭空调"
                }
                INTENT_SET_TEMPERATURE -> {
                    val number = intent.slots[RuleNluProvider.TEMPERATURE_SLOT]
                    val temperature = (number as? SlotValue.Number)?.v ?: return null
                    acTemperatureC = temperature
                    return "已为您把空调调到${temperature.toDigitsString()}度"
                }
            }
            DOMAIN_WINDOW -> when (intent.intent) {
                INTENT_POWER_ON -> {
                    windowsOpen = true
                    return "已为您打开车窗"
                }
                INTENT_POWER_OFF -> {
                    windowsOpen = false
                    return "已为您关闭车窗"
                }
            }
        }
        return null // 未知意图：保持现状，不播报
    }

    private companion object {
        const val DEFAULT_TEMPERATURE = 24.0

        const val DOMAIN_CLIMATE = "climate"
        const val DOMAIN_WINDOW = "window"
        const val INTENT_POWER_ON = "power_on"
        const val INTENT_POWER_OFF = "power_off"
        const val INTENT_SET_TEMPERATURE = "set_temperature"
    }
}

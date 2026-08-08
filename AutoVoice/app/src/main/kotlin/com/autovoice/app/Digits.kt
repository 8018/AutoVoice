package com.autovoice.app

/**
 * 温度数字直书（非中文数字）的单一事实来源：
 * 24.0 → "24"、25.5 → "25.5"。
 *
 * 播报文案（[MockVehicleState]）与面板展示（`ui/VehiclePanel.kt`）共用此规则，
 * 避免两处实现漂移；Task 20 若调整数字格式只改这里。
 */
fun Double.toDigitsString(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()

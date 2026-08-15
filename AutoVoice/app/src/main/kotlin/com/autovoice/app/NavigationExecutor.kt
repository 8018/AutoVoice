package com.autovoice.app

import com.autovoice.voicecore.Intent
import java.net.URLEncoder

/**
 * 导航执行器（spec §4.2）：把 navigation/navigate 意图（slots: poiname/lat/lon）转成高德
 * 导航 URI（`androidamap://navi?sourceApplication=autovoice&poiname=<名称>&lat=<纬度>&lon=<经度>`），
 * 交给注入的 opener 拉起高德 App。
 *
 * <p>设计：不直接依赖 Android API——[opener] 由装配方注入（生产：MainViewModel 用
 * applicationContext + FLAG_ACTIVITY_NEW_TASK 调 startActivity；JVM 测试注入 fake 断言 URI）。
 * 意图不合法（缺槽位）或拉起失败（高德未安装等）→ 返回 false，调用方据此记 skipped
 * 事件、播报兜底。</p>
 */
class NavigationExecutor(
    private val opener: (String) -> Boolean,
) {

    /** 执行导航意图；非 navigation/navigate、槽位缺失或拉起失败返回 false。 */
    fun execute(intent: Intent): Boolean {
        if (intent.domain != DOMAIN_NAVIGATION || intent.intent != INTENT_NAVIGATE) return false
        val slots = intent.slots ?: return false
        val poiname = (slots[SLOT_POINAME]?.value as? String)?.takeIf { it.isNotBlank() } ?: return false
        val lat = (slots[SLOT_LAT]?.value as? Number)?.toDouble() ?: return false
        val lon = (slots[SLOT_LON]?.value as? Number)?.toDouble() ?: return false
        return opener(buildNaviUri(poiname, lat, lon))
    }

    /** 高德导航 URI（spec §4.2）；poiname 经 URL 编码（中文/空格合法化）。 */
    private fun buildNaviUri(poiname: String, lat: Double, lon: Double): String {
        val encoded = URLEncoder.encode(poiname, Charsets.UTF_8.name())
        return "androidamap://navi?sourceApplication=$SOURCE_APPLICATION&poiname=$encoded&lat=$lat&lon=$lon"
    }

    companion object {
        const val DOMAIN_NAVIGATION = "navigation"
        const val INTENT_NAVIGATE = "navigate"
        const val SLOT_POINAME = "poiname"
        const val SLOT_LAT = "lat"
        const val SLOT_LON = "lon"
        const val SOURCE_APPLICATION = "autovoice"
    }
}

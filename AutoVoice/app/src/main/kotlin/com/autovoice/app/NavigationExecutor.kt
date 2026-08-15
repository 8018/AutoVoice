package com.autovoice.app

import com.autovoice.voicecore.Intent
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * 导航执行器（spec §4.2）：把 navigation/navigate 意图转成高德 URI，交给注入的 opener 拉起高德 App。
 *
 * <p>双协议：</p>
 * <ul>
 *   <li>单目的地（slots: poiname/lat/lon）→ `androidamap://navi?sourceApplication=autovoice&poiname=…&lat=…&lon=…`
 *       （直接开始导航）；</li>
 *   <li>多目的地（waypoints string 槽 = [{poiname,lat,lon}] JSON 文本，用户说"先去A再去B"）→
 *       `amapuri://route/plan?sourceApplication=autovoice&dlat=…&dlon=…&dname=…&vian=N&vialons=…|…&vialats=…|…&vianames=…|…&t=0&dev=0`
 *       （终点=最终目的地、途经=中间目的地，高德原生路线规划页）。</li>
 * </ul>
 *
 * <p>设计：不直接依赖 Android API——[opener] 由装配方注入（生产：MainViewModel 用
 * applicationContext + FLAG_ACTIVITY_NEW_TASK 调 startActivity；JVM 测试注入 fake 断言 URI）。
 * 意图不合法（缺槽位）或拉起失败（高德未安装等）→ 返回 false，调用方据此记 skipped
 * 事件、播报兜底。</p>
 */
class NavigationExecutor(
    private val opener: (String) -> Boolean,
) {

    private val gson = Gson()

    /** 执行导航意图；非 navigation/navigate、槽位缺失或拉起失败返回 false。 */
    fun execute(intent: Intent): Boolean {
        if (intent.domain != DOMAIN_NAVIGATION || intent.intent != INTENT_NAVIGATE) return false
        val slots = intent.slots ?: return false
        val poiname = (slots[SLOT_POINAME]?.value as? String)?.takeIf { it.isNotBlank() } ?: return false
        val lat = (slots[SLOT_LAT]?.value as? Number)?.toDouble() ?: return false
        val lon = (slots[SLOT_LON]?.value as? Number)?.toDouble() ?: return false
        val waypointsJson = slots[SLOT_WAYPOINTS]?.value as? String
        return if (waypointsJson == null || waypointsJson.isBlank()) {
            // 无 waypoints 槽 → 单目的地 navi（直接开始导航）
            opener(buildNaviUri(poiname, lat, lon))
        } else {
            // 多目的地 → route/plan；JSON 非法/空数组 → 不拉起（数据契约破坏，
            // 静默回退单目的地会误导用户以为"先去A"仍生效，记 skipped 由调用方兜底）
            parseWaypoints(waypointsJson)?.let { wp -> opener(buildRoutePlanUri(poiname, lat, lon, wp)) } ?: false
        }
    }

    /** 解析 waypoints string 槽（JSON 文本）；JSON 非法/空数组 → null（不拉起）。 */
    private fun parseWaypoints(json: String): List<Waypoint>? =
        try {
            val list = gson.fromJson(json, Array<Waypoint>::class.java).toList()
            if (list.isEmpty()) null else list
        } catch (e: JsonSyntaxException) {
            null
        }

    /** 单目的地高德导航 URI（spec §4.2）；poiname 经 URL 编码（中文/空格合法化）。 */
    private fun buildNaviUri(poiname: String, lat: Double, lon: Double): String {
        val encoded = URLEncoder.encode(poiname, Charsets.UTF_8.name())
        return "androidamap://navi?sourceApplication=$SOURCE_APPLICATION&poiname=$encoded&lat=$lat&lon=$lon"
    }

    /** 多目的地高德路线规划 URI（amapuri://route/plan，官方途经点协议）：
     *  终点 = 最终目的地（dlat/dlon/dname），途经 = waypoints（vian 数量 + vialons/vialats/vianames，
     *  `|` 分隔且数量一致），驾车 t=0，GCJ-02 无偏移 dev=0。 */
    private fun buildRoutePlanUri(poiname: String, lat: Double, lon: Double, waypoints: List<Waypoint>): String {
        val dname = URLEncoder.encode(poiname, Charsets.UTF_8.name())
        // 先对单个值编码、再以原始 | 连接（分隔符不编码，中文/空格经 encode 合法化）
        val vialons = waypoints.joinToString("|") { it.lon.toString() }
        val vialats = waypoints.joinToString("|") { it.lat.toString() }
        val vianames = waypoints.joinToString("|") {
            URLEncoder.encode(it.poiname, Charsets.UTF_8.name())
        }
        return "amapuri://route/plan?sourceApplication=$SOURCE_APPLICATION" +
                "&dlat=$lat&dlon=$lon&dname=$dname" +
                "&vian=${waypoints.size}&vialons=$vialons&vialats=$vialats&vianames=$vianames" +
                "&t=0&dev=0"
    }

    /** 途经点（多目的地中间站）：名称 + GCJ-02 坐标。 */
    data class Waypoint(val poiname: String, val lat: Double, val lon: Double)

    companion object {
        const val DOMAIN_NAVIGATION = "navigation"
        const val INTENT_NAVIGATE = "navigate"
        const val SLOT_POINAME = "poiname"
        const val SLOT_LAT = "lat"
        const val SLOT_LON = "lon"
        const val SLOT_WAYPOINTS = "waypoints"
        const val SOURCE_APPLICATION = "autovoice"
    }
}

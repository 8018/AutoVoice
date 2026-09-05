package com.autovoice.app

import com.autovoice.voicecore.Intent
import java.net.URLEncoder

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
    private val onCandidates: (List<NavigationCandidate>) -> Unit = {},
    val session: NavigationSession = NavigationSession(),
    private val opener: (String) -> Boolean,
) {


    /** 执行导航意图；非 navigation/navigate、槽位缺失或拉起失败返回 false。 */
    @Synchronized fun execute(intent: Intent): Boolean {
        if (intent.domain != DOMAIN_NAVIGATION) return false
        if (intent.intent == INTENT_CANCEL_NAVIGATION) {
            val selectionId = intent.slots["selectionId"]?.value as? String
            if (session.snapshot.selectionId != null && selectionId != session.snapshot.selectionId) return false
            if (selectionId != null && selectionId != session.snapshot.selectionId) return false
            if (!session.cancelSelection()) return false
            onCandidates(emptyList())
            return true
        }
        if (intent.intent == INTENT_CHOOSE_DESTINATION) {
            val json = intent.slots?.get(SLOT_CANDIDATES)?.value as? String ?: return false
            val candidates = parseCandidates(json) ?: return false
            val selectionId = intent.slots["selectionId"]?.value as? String
            if (selectionId != null && (selectionId.isBlank() || candidates.any { it.candidateId.isBlank() }
                    || candidates.map { it.candidateId }.toSet().size != candidates.size)) return false
            session.offer(candidates, selectionId)
            onCandidates(candidates)
            return true
        }
        if (intent.intent != INTENT_NAVIGATE) return false
        val slots = intent.slots ?: return false
        val poiname = (slots[SLOT_POINAME]?.value as? String)?.takeIf { it.isNotBlank() } ?: return false
        val lat = (slots[SLOT_LAT]?.value as? Number)?.toDouble() ?: return false
        val lon = (slots[SLOT_LON]?.value as? Number)?.toDouble() ?: return false
        val waypointsJson = slots[SLOT_WAYPOINTS]?.value as? String
        val waypoints = if (waypointsJson.isNullOrBlank()) emptyList() else parseWaypoints(waypointsJson) ?: return false
        val trip = runCatching {
            NavigationTrip(NavigationTarget(poiname, lat, lon), waypoints.map {
                NavigationTarget(it.poiname, it.lat, it.lon)
            })
        }.getOrNull() ?: return false
        val selectionId = slots["selectionId"]?.value as? String
        val candidateId = slots["candidateId"]?.value as? String
        val pending = session.snapshot
        if (selectionId != null || candidateId != null || pending.selectionId != null) {
            if (selectionId == null || selectionId != pending.selectionId || candidateId.isNullOrBlank()) return false
            val candidate = pending.candidates.singleOrNull { it.candidateId == candidateId } ?: return false
            if (candidate.poiname != poiname || candidate.lat != lat || candidate.lon != lon || waypoints.isNotEmpty()) return false
        }
        val uri = if (waypoints.isEmpty()) buildNaviUri(poiname, lat, lon)
            else buildRoutePlanUri(poiname, lat, lon, waypoints)
        session.beginHandoff(trip)
        clearCandidatesBeforeOpen()
        val opened = runCatching { opener(uri) }.getOrDefault(false)
        session.finishHandoff(opened)
        return opened
    }

    private fun clearCandidatesBeforeOpen() {
        // 在切到高德前同步关闭应用内候选框。若等 startActivity 返回后再清理，Activity
        // 生命周期切换或并发 UI 更新可能让旧弹窗在返回 AutoVoice 时重新露出。
        onCandidates(emptyList())
    }

    private fun parseCandidates(json: String): List<NavigationCandidate>? =
        runCatching {
            val array = com.google.gson.JsonParser.parseString(json).asJsonArray
            require(!array.isEmpty)
            array.map { element ->
                val item = element.asJsonObject
                require(item["poiname"].asJsonPrimitive.isString)
                require(item["lat"].asJsonPrimitive.isNumber && item["lon"].asJsonPrimitive.isNumber)
                val point = NavigationTarget(item["poiname"].asString, item["lat"].asDouble, item["lon"].asDouble)
                NavigationCandidate(point.name, point.latitude, point.longitude,
                    item.get("address")?.takeUnless { it.isJsonNull }?.asString ?: "",
                    item.get("candidateId")?.takeUnless { it.isJsonNull }?.asString ?: "")
            }
            // Reject the whole list on malformed items; filtering would change spoken ordinals.
        }.getOrNull()

    /** 解析 waypoints string 槽（JSON 文本）；JSON 非法/空数组 → null（不拉起）。 */
    private fun parseWaypoints(json: String): List<Waypoint>? =
        parseCandidates(json)?.map { Waypoint(it.poiname, it.lat, it.lon) }

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

    /** 应用内展示的 POI 候选；坐标只用于用户确认后的最终 navigate 动作。 */
    data class NavigationCandidate(
        val poiname: String,
        val lat: Double,
        val lon: Double,
        val address: String = "",
        val candidateId: String = "",
    )

    companion object {
        const val DOMAIN_NAVIGATION = "navigation"
        const val INTENT_NAVIGATE = "navigate"
        const val INTENT_CHOOSE_DESTINATION = "choose_destination"
        const val INTENT_CANCEL_NAVIGATION = "cancel_navigation"
        const val SLOT_POINAME = "poiname"
        const val SLOT_LAT = "lat"
        const val SLOT_LON = "lon"
        const val SLOT_WAYPOINTS = "waypoints"
        const val SLOT_CANDIDATES = "candidates"
        const val SOURCE_APPLICATION = "autovoice"
    }
}

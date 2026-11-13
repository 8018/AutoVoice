package com.autovoice.voicebusiness.navigation

import com.autovoice.voicecore.Intent
import java.net.URLEncoder

/**
 * 导航执行器（spec §4.2）：把 navigation/navigate 意图转成高德 URI，交给注入的 opener 拉起高德 App。
 *
 * <p>双协议：</p>
 * <ul>
 *   <li>单目的地（slots: poiname/lat/lon）→ `androidamap://navi?sourceApplication=autovoice&poiname=…&lat=…&lon=…&dev=…`
 *       （向高德交接目的地）；</li>
 *   <li>多目的地（waypoints string 槽 = [{poiname,lat,lon}] JSON 文本，用户说"先去A再去B"）→
 *       `amapuri://route/plan?sourceApplication=autovoice&dlat=…&dlon=…&dname=…&vian=N&vialons=…|…&vialats=…|…&vianames=…|…&t=0&dev=0`
 *       （终点=最终目的地、途经=中间目的地，高德原生路线规划页）。</li>
 * </ul>
 *
 * <p>设计：不直接依赖 Android API——[opener] 由装配方注入（生产：MainViewModel 用
 * applicationContext + FLAG_ACTIVITY_NEW_TASK 调 startActivity；JVM 测试注入 fake 断言 URI）。
 * URI 交接只确认系统已接受打开请求，实际导航状态需要地图回调。</p>
 */
class NavigationExecutor(
    private val onCandidates: (List<NavigationCandidate>) -> Unit = {},
    val session: NavigationSession = NavigationSession(),
    private val savedPlace: (SavedPlaceKind) -> SavedNavigationPlace? = { null },
    private val opener: (String) -> Boolean,
) {
    val capabilities: NavigationCapabilities = NavigationCapabilities.URI_HANDOFF
    private val dialoguePolicy = NavigationDialoguePolicy()

    fun abortPendingTask() = session.abortSelection()

    /** Compatibility entry point for callers that only need to know whether the command was adopted. */
    fun execute(intent: Intent, turnId: String = "legacy-navigation-turn"): Boolean =
        when (executeWithResult(intent, turnId).status) {
            NavigationActionStatus.PENDING, NavigationActionStatus.ACCEPTED, NavigationActionStatus.SUCCEEDED -> true
            NavigationActionStatus.UNSUPPORTED, NavigationActionStatus.FAILED -> false
        }

    @Synchronized
    fun executeWithResult(
        intent: Intent,
        turnId: String,
        isGpsCoordinate: Boolean = false,
    ): NavigationActionResult {
        if (intent.domain != DOMAIN_NAVIGATION || !capabilities.supports(intent.intent)) {
            return NavigationActionResult.unsupported(intent, turnId)
        }
        val operationId = NavigationActionResult.operationId(intent, turnId)
        val invalid = NavigationActionResult(
            operationId, NavigationActionStatus.FAILED, "无法识别这个导航请求，请再说一次",
            NavigationActionError.INVALID_REQUEST,
        )
        if (turnId.isBlank()) return invalid
        val stale = NavigationActionResult(
            operationId, NavigationActionStatus.FAILED, "", NavigationActionError.STALE_TASK,
        )
        val operation = intent.slots["navigationOperation"]?.value as? String
        val current = session.snapshot
        if (current.requiresTaskIdentity && operation == null &&
            intent.intent != INTENT_CHOOSE_DESTINATION && intent.intent != INTENT_DRAFT_TRIP) return stale
        if (operation != null) {
            if (operation !in setOf("start_new", "select", "cancel")) return invalid
            val taskId = intent.slots["taskId"]?.value as? String
            if (operation != "start_new" && taskId.isNullOrBlank()) return invalid
            if (operation == "select" && intent.intent != INTENT_NAVIGATE) return invalid
            // A response cannot act on a replacement task, nor revive an already ended task.
            if (taskId != null || current.taskId != null) {
                if (taskId != current.taskId ||
                    (intent.slots["taskRevision"]?.value as? Number)?.toLong() != current.candidateVersion ||
                    intent.slots["interactionId"]?.value != current.interactionId) return stale
            }
            if ((operation == "cancel") != (intent.intent == INTENT_CANCEL_NAVIGATION)) return invalid
        }
        if (intent.intent == INTENT_DRAFT_TRIP) {
            val payload = intent.slots["stops"]?.value as? String ?: return invalid
            return stageDraft(payload, turnId, operationId)
        }
        if (intent.intent == INTENT_CANCEL_NAVIGATION) {
            val selectionId = intent.slots["selectionId"]?.value as? String
            if (current.taskId == null && operation == null && selectionId == null && intent.slots["taskId"] == null) {
                return NavigationActionResult(
                    operationId,
                    NavigationActionStatus.UNSUPPORTED,
                    "请在高德地图内结束导航",
                    NavigationActionError.UNSUPPORTED,
                )
            }
            if (selectionId != current.selectionId) return stale
            if (!session.cancelSelection(selectionId, current.identity())) return stale
            onCandidates(emptyList())
            val message = if (current.draft != null) "已放弃行程草稿" else "已取消本次目的地选择"
            return NavigationActionResult(operationId, NavigationActionStatus.SUCCEEDED, message)
        }
        if (intent.intent == INTENT_CHOOSE_DESTINATION) {
            val json = intent.slots[SLOT_CANDIDATES]?.value as? String ?: return invalid
            val candidates = parseCandidates(json) ?: return invalid
            val selectionId = intent.slots["selectionId"]?.value as? String
            if (!dialoguePolicy.acceptsOffer(selectionId, candidates)) return invalid
            session.offer(candidates, selectionId, turnId,
                requiresTaskIdentity = (intent.slots["taskDialogVersion"]?.value as? Number)?.toInt() == 1)
            onCandidates(candidates)
            return NavigationActionResult(operationId, NavigationActionStatus.PENDING, "")
        }
        val slots = intent.slots
        val poiname = (slots[SLOT_POINAME]?.value as? String)?.takeIf { it.isNotBlank() } ?: return invalid
        val lat = (slots[SLOT_LAT]?.value as? Number)?.toDouble() ?: return invalid
        val lon = (slots[SLOT_LON]?.value as? Number)?.toDouble() ?: return invalid
        val waypointsJson = slots[SLOT_WAYPOINTS]?.value as? String
        val waypoints = if (waypointsJson.isNullOrBlank()) emptyList() else parseWaypoints(waypointsJson) ?: return invalid
        if (isGpsCoordinate && waypoints.isNotEmpty()) return invalid
        if (waypoints.size >= NavigationTripDraft.MAX_STOPS) return tooManyStops(operationId)
        val trip = runCatching {
            NavigationTrip(NavigationTarget(poiname, lat, lon), waypoints.map {
                NavigationTarget(it.poiname, it.lat, it.lon)
            })
        }.getOrNull() ?: return invalid
        val selectionId = slots["selectionId"]?.value as? String
        val candidateId = slots["candidateId"]?.value as? String
        val pending = current
        if (operation == "start_new") {
            if (selectionId != null || candidateId != null) return invalid
            if (pending.taskId != null && !session.abortSelection(
                    com.autovoice.voicebusiness.dialog.TaskEndReason.REPLACED, pending.identity())) return stale
        } else if (selectionId != null || candidateId != null || pending.selectionId != null) {
            if (selectionId == null || selectionId != pending.selectionId || candidateId.isNullOrBlank()) return stale
            val candidate = dialoguePolicy.matchSelection(
                pending,
                selectionId,
                candidateId,
                trip.destination,
                waypoints.isNotEmpty(),
            ) ?: return stale
            if (session.claimSelection(selectionId, candidateId, pending.identity()) != candidate) return stale
        } else if (pending.taskStatus == com.autovoice.voicebusiness.dialog.TaskStatus.WAITING_INPUT) {
            // A fully resolved navigation intent without candidate identity is a fresh request.
            session.abortSelection(com.autovoice.voicebusiness.dialog.TaskEndReason.REPLACED)
        }
        if (trip.waypoints.isNotEmpty()) {
            val stops = (trip.waypoints + trip.destination).map { NavigationDraftStop(it.name, it) }
            val draft = session.offerDraft(stops, turnId) ?: return stale
            return draftPending(operationId, draft)
        }
        return handoff(trip, operationId, isGpsCoordinate)
    }

    @Synchronized
    fun confirmDraft(revision: Long): NavigationActionResult? {
        val current = session.snapshot.draft?.takeIf { it.revision == revision } ?: return null
        val operationId = "draft:$revision"
        val trip = current.trip() ?: return draftPending(operationId, current)
        for (stop in current.stops) {
            val kind = stop.savedKind ?: continue
            val value = runCatching { savedPlace(kind) }.getOrNull()
            if (value != stop.savedValue) {
                if (!session.dismissDraft(revision)) return null
                return draftFailure(operationId, "${kind.label}地址已更改或无法读取，请重新安排行程")
            }
        }
        if (session.claimDraft(revision) == null) return null
        return handoff(trip, operationId)
    }

    private fun stageDraft(payload: String, turnId: String, operationId: String): NavigationActionResult {
        val parsed = runCatching { com.google.gson.JsonParser.parseString(payload).asJsonArray }.getOrNull()
            ?: return draftFailure(operationId, "行程草稿无效，请重新说出所有地点")
        if (parsed.size() > NavigationTripDraft.MAX_STOPS) return tooManyStops(operationId)
        if (parsed.size() < 2) return draftFailure(operationId, "行程草稿需要至少两个地点")
        val stops = mutableListOf<NavigationDraftStop>()
        for (element in parsed) {
            val item = element.takeIf { it.isJsonObject }?.asJsonObject
                ?: return draftFailure(operationId, "行程草稿无效，请重新说出所有地点")
            val query = item.get("query")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.takeIf { it.isNotBlank() && it.length <= 256 }
                ?: return draftFailure(operationId, "行程草稿无效，请重新说出所有地点")
            val saved = item.get("savedPlace")
            if (saved != null) {
                val kind = when (saved.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString) {
                    "home" -> SavedPlaceKind.HOME
                    "work" -> SavedPlaceKind.WORK
                    else -> return draftFailure(operationId, "常用地址类型无效，请重新说出所有地点")
                }
                if (item.has("candidates")) return draftFailure(operationId, "行程草稿无效，请重新说出所有地点")
                val place = try {
                    savedPlace(kind)
                } catch (_: SavedNavigationPlaceStore.StorageUnavailable) {
                    return draftFailure(operationId, "暂时无法读取${kind.label}，请稍后重试")
                } ?: return draftFailure(operationId, "还没有设置${kind.label}，请先在常用地址中设置")
                if (place.isGpsCoordinate) {
                    return draftFailure(operationId, "当前无法将${kind.label}的定位坐标加入多地点路线，请在常用地址中选择地图地点")
                }
                stops += NavigationDraftStop(query, place.target.copy(name = kind.label), savedKind = kind, savedValue = place)
            } else {
                val choices = item.get("candidates")?.takeIf { it.isJsonArray && it.asJsonArray.size() <= 3 }
                    ?.let { parseCandidates(it.toString()) }
                    ?.map { it.copy(candidateId = java.util.UUID.randomUUID().toString()) }
                    ?: return draftFailure(operationId, "未找到第${stops.size + 1}站${query}，请补充地点后重新安排行程")
                val resolved = choices.singleOrNull()?.let { NavigationTarget(it.poiname, it.lat, it.lon) }
                stops += NavigationDraftStop(query, resolved, choices)
            }
        }
        val draft = session.offerDraft(stops, turnId)
            ?: return NavigationActionResult(operationId, NavigationActionStatus.FAILED, "", NavigationActionError.STALE_TASK)
        return draftPending(operationId, draft)
    }

    private fun draftPending(operationId: String, draft: NavigationTripDraft) =
        NavigationActionResult(operationId, NavigationActionStatus.PENDING, draft.prompt())

    private fun tooManyStops(operationId: String) =
        draftFailure(operationId, "一次最多安排${NavigationTripDraft.MAX_STOPS}个地点，请减少地点后重试")

    private fun draftFailure(operationId: String, message: String): NavigationActionResult {
        session.snapshot.draft?.let { session.dismissDraft(it.revision) }
        return NavigationActionResult(operationId, NavigationActionStatus.FAILED, message, NavigationActionError.INVALID_REQUEST)
    }

    private fun handoff(
        trip: NavigationTrip,
        operationId: String,
        isGpsCoordinate: Boolean = false,
    ): NavigationActionResult {
        val target = trip.destination
        val waypoints = trip.waypoints.map { Waypoint(it.name, it.latitude, it.longitude) }
        val uri = if (waypoints.isEmpty()) buildNaviUri(target.name, target.latitude, target.longitude, isGpsCoordinate)
            else buildRoutePlanUri(target.name, target.latitude, target.longitude, waypoints)
        val handoffGeneration = session.beginHandoff(trip)
        clearCandidatesBeforeOpen()
        val opened = runCatching { opener(uri) }.getOrDefault(false)
        session.finishHandoff(handoffGeneration, opened)
        return if (opened) {
            val message = if (waypoints.isEmpty()) "已打开高德地图，请查看导航" else "已打开高德地图，请查看路线规划"
            NavigationActionResult(operationId, NavigationActionStatus.ACCEPTED, message)
        } else {
            NavigationActionResult(
                operationId,
                NavigationActionStatus.FAILED,
                "无法打开高德地图，请检查是否已安装",
                NavigationActionError.MAP_OPEN_FAILED,
            )
        }
    }

    private fun clearCandidatesBeforeOpen() {
        // 在切到高德前同步关闭应用内候选框。若等 startActivity 返回后再清理，Activity
        // 生命周期切换或并发 UI 更新可能让旧弹窗在返回 AutoVoice 时重新露出。
        onCandidates(emptyList())
    }

    private fun NavigationSnapshot.identity(): com.autovoice.voicebusiness.dialog.TaskIdentity? =
        if (taskId != null && interactionId != null)
            com.autovoice.voicebusiness.dialog.TaskIdentity(interactionId, taskId, candidateVersion) else null

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
    private fun buildNaviUri(poiname: String, lat: Double, lon: Double, isGpsCoordinate: Boolean): String {
        val encoded = URLEncoder.encode(poiname, Charsets.UTF_8.name())
        val coordinateType = if (isGpsCoordinate) "&dev=1" else "&dev=0"
        return "androidamap://navi?sourceApplication=$SOURCE_APPLICATION&poiname=$encoded&lat=$lat&lon=$lon$coordinateType"
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
        const val INTENT_DRAFT_TRIP = "draft_trip"
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

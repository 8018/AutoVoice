package com.autovoice.app.navigation

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.View
import com.autovoice.app.navigation.NavigationOperation.*
import com.autovoice.app.navigation.NavigationOutcome.*
import com.autovoice.gatewayclient.NavigationRoutePreviewTimeout
import com.autovoice.voicebusiness.navigation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Date

data class NavigationRuntimeUi(
    val sdkConfigured: Boolean = false,
    val embeddedEnabled: Boolean = false,
    val snapshot: NavigationRouteSnapshot = NavigationRouteSnapshot(),
    val message: String? = null,
    val showMap: Boolean = false,
    val freshProgress: MapProgress? = null,
)

internal class NavigationRuntime(
    private val context: Context,
    private val key: String,
    private val scope: CoroutineScope,
    private val position: () -> NavigationTarget?,
    private val refreshPosition: () -> Unit,
    private val publish: (NavigationRuntimeUi) -> Unit,
    captureOperationRecorder: () -> ((NavigationOperationSample) -> Unit)?,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val session = NavigationRouteSession(SystemClock::elapsedRealtime)
    private val operations = NavigationOperationTracker(SystemClock::elapsedRealtime, captureOperationRecorder)
    private var sdk: AndroidNavigationMap? = null
    @Volatile private var sdkEnabled = false
    @Volatile private var closed = false
    private var previewJob: Job? = null
    private var mapVisible = false
    private var guidanceVolumePercent = 100
    @Volatile var previewRoutes: (suspend (MapPlanRequest) -> List<MapRoute>)? = null
    @Volatile private var latest = NavigationRuntimeUi(sdkConfigured = key.isNotBlank())

    fun enableEmbedded() {
        if (closed || sdkEnabled || key.isBlank()) return
        val adapter = createAmapNavigation(context, key, ::onMapEvent)
        sdk = adapter
        sdkEnabled = adapter != null
        guidanceVolumePercent = 100
        update(if (sdkEnabled) "已启用高德内嵌导航" else "高德内嵌导航初始化失败，仍可使用高德地图")
    }

    /** Returning null leaves normal navigation on the established URI handoff path. */
    fun handleTrip(
        trip: NavigationTrip,
        operationId: String,
        gps: Boolean,
        preview: RoutePreference?,
    ): NavigationActionResult? {
        if (preview == null && !sdkEnabled) return null
        val request = MapPlanRequest(operations.begin(PLAN), trip, null, preview ?: RoutePreference.DEFAULT, gps)
        onMain(onClosed = { operations.finish(request.id, UNKNOWN) }) {
            submitPlan(request, allowStart = preview == null)
        }
        return NavigationActionResult(operationId, NavigationActionStatus.PENDING, "正在查询路线")
    }

    fun previewTrip(trip: NavigationTrip, gps: Boolean = false) {
        handleTrip(trip, "", gps, RoutePreference.DEFAULT)
    }

    private fun submitPlan(request: MapPlanRequest, allowStart: Boolean) {
        val adapter = sdk
        val remote = previewRoutes
        if (session.snapshot.active != null || session.snapshot.starting != null) {
            operations.finish(request.id, REJECTED)
            update("当前导航仍在进行，无法另外规划路线")
            return
        }
        val freshOrigin = position()
        if (freshOrigin == null) {
            operations.finish(request.id, MISSING_LOCATION)
            refreshPosition()
            update("获取近期精确定位后才能规划路线，请稍后重试")
            return
        }
        val plan = request.copy(origin = freshOrigin)
        val previousPlan = session.snapshot.plan?.request?.id
        if (!session.beginPlan(plan, selectable = allowStart && adapter != null)) {
            operations.finish(plan.id, REJECTED)
            return
        }
        operations.finish(previousPlan, CANCELLED)
        previewJob?.cancel()
        update("正在查询路线")
        if (adapter != null) {
            val accepted = runCatching { adapter.plan(plan) }.getOrDefault(false)
            if (!accepted) onMapEvent(NavigationMapEvent.PlanFailed(plan.id))
            else main.postDelayed({ failPlan(plan.id, TIMEOUT) }, PLAN_TIMEOUT_MS)
            return
        }
        if (remote == null) {
            operations.finish(plan.id, UNSUPPORTED)
            if (session.planFailed(plan.id)) update("路线预览需要连接在线服务")
            return
        }
        previewJob = scope.launch {
            try {
                onMapEvent(NavigationMapEvent.Routes(plan.id, remote(plan)))
            } catch (_: NavigationRoutePreviewTimeout) {
                onMain { failPlan(plan.id, TIMEOUT) }
            } catch (e: CancellationException) {
                operations.finish(plan.id, CANCELLED)
                throw e
            } catch (_: Exception) {
                onMapEvent(NavigationMapEvent.PlanFailed(plan.id))
            }
        }
    }

    fun prepareStart(planId: String) {
        val id = operations.begin(PLAN)
        val plan = session.snapshot.plan?.takeIf { it.request.id == planId && !it.selectable }
            ?: return operations.finish(id, REJECTED)
        if (sdk == null) return operations.finish(id, UNSUPPORTED)
        submitPlan(plan.request.copy(id = id), allowStart = true)
    }

    fun changePreference(planId: String, preference: RoutePreference) {
        val id = operations.begin(PLAN)
        val plan = session.snapshot.plan?.takeIf { it.request.id == planId }
            ?: return operations.finish(id, REJECTED)
        submitPlan(plan.request.copy(id = id, preference = preference), plan.selectable)
    }

    fun dismissPlan(planId: String) {
        if (!session.dismissPlan(planId)) return
        operations.finish(planId, CANCELLED)
        previewJob?.cancel()
        update()
    }

    fun start(planId: String, routeId: String) {
        val id = operations.begin(START)
        val adapter = sdk ?: return operations.finish(id, UNSUPPORTED)
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            operations.finish(id, MISSING_LOCATION)
            update("开始导航需要精确定位权限")
            return
        }
        val request = session.reserveStart(id, planId, routeId) ?: return operations.finish(id, REJECTED)
        if (!runCatching { adapter.start(request) }.getOrDefault(false)) {
            onMapEvent(NavigationMapEvent.StartFailed(request))
            return
        }
        update("等待高德确认开始导航")
        main.postDelayed({
            if (session.snapshot.starting == request) {
                operations.finish(id, TIMEOUT)
                update("高德尚未确认开始，导航状态未知")
            }
        }, PLAN_TIMEOUT_MS)
    }

    fun requestStop(): String {
        val operationId = operations.begin(STOP)
        val adapter = sdk ?: return finish(operationId, UNSUPPORTED, "请在高德地图内结束导航")
        val identity = session.reserveStop(operationId) ?: return finish(
            operationId, REJECTED,
            if (session.snapshot.stopping != null) "结束请求已发出，等待高德确认" else "当前没有可确认的导航",
        )
        if (!runCatching { adapter.stop(operationId, identity) }.getOrDefault(false)) {
            session.stopFailed(operationId, identity)
            return finish(operationId, FAILED, "未能提交结束请求")
        }
        // stopNavi has no trusted completion acknowledgement.
        operations.finish(operationId, ACCEPTED)
        update("结束请求已发出，等待高德确认")
        return "结束请求已发出，等待高德确认"
    }

    fun leaveTracking() {
        val previous = session.snapshot
        if (!session.leaveTracking()) return
        operations.finish(previous.starting?.tripId, UNKNOWN)
        operations.finish(previous.search?.operationId, UNKNOWN)
        mapVisible = false
        val old = sdk
        sdk = null
        sdkEnabled = false
        runCatching { old?.close() }
        update("已退出本地跟踪，导航结束状态未知。可重新启用内嵌导航或在高德地图中确认。")
    }

    fun report(message: String) { update(message) }

    fun search(category: MapAlongCategory): String {
        val id = operations.begin(SEARCH)
        val adapter = sdk ?: return finish(id, UNSUPPORTED, "当前无法搜索高德路线沿途地点")
        val previous = session.snapshot.search
        val identity = session.beginSearch(id) ?: return finish(id, REJECTED, "当前没有可查询的活动路线")
        operations.finish(previous?.operationId, CANCELLED)
        if (!runCatching { adapter.search(id, identity, category) }.getOrDefault(false)) {
            session.cancelSearch()
            update("暂时无法读取剩余路线")
            return finish(id, FAILED, "暂时无法读取剩余路线")
        }
        update("正在搜索沿途${category.label}")
        main.postDelayed({
            failSearch(id, identity, TIMEOUT)
        }, PLAN_TIMEOUT_MS)
        return "正在搜索沿途${category.label}"
    }

    fun cancelSearch(): String {
        val id = operations.begin(CANCEL_SEARCH)
        val search = session.snapshot.search
        if (!session.cancelSearch()) return finish(id, REJECTED, "当前没有沿途搜索")
        operations.finish(search?.operationId, CANCELLED)
        update()
        return finish(id, SUCCEEDED, "已放弃沿途搜索")
    }

    fun setMapVisible(visible: Boolean) {
        val id = operations.begin(MAP_VISIBILITY)
        if (visible && session.snapshot.active == null) return operations.finish(id, REJECTED)
        mapVisible = visible
        update()
        operations.finish(id, SUCCEEDED)
    }

    fun createMapView(context: Context): View? = runCatching { sdk?.createView(context) }.getOrNull()
    fun resumeMapView(view: View) { runCatching { sdk?.resumeView(view) } }
    fun pauseMapView(view: View) { runCatching { sdk?.pauseView(view) } }
    fun releaseMapView(view: View) { runCatching { sdk?.releaseView(view) } }

    fun mapAction(action: MapViewAction): String {
        val id = operations.begin(MAP_CONTROL)
        val unavailable = "当前无法调整地图，请打开应用内导航地图"
        val adapter = sdk ?: return finish(id, UNSUPPORTED, unavailable)
        return if (runCatching { adapter.applyView(action) }.getOrDefault(false)) {
            finish(id, ACCEPTED, "已发送地图操作")
        } else finish(id, FAILED, unavailable)
    }

    fun mute(muted: Boolean): String {
        val id = operations.begin(AUDIO_MUTE)
        val unavailable = "当前无法调整导航播报"
        val adapter = sdk ?: return finish(id, UNSUPPORTED, unavailable)
        return if (runCatching { adapter.setGuidanceMuted(muted) }.getOrDefault(false)) {
            finish(id, SUCCEEDED, if (muted) "已静音导航播报" else "已恢复导航播报")
        } else finish(id, FAILED, unavailable)
    }

    fun volume(percent: Int): String {
        val id = operations.begin(AUDIO_VOLUME)
        if (percent !in 0..100) return finish(id, REJECTED, "导航音量请输入0到100")
        val unavailable = "当前无法调整导航播报音量"
        val adapter = sdk ?: return finish(id, UNSUPPORTED, unavailable)
        return if (runCatching { adapter.setGuidanceVolume(percent / 100f) }.getOrDefault(false)) {
            guidanceVolumePercent = percent
            finish(id, SUCCEEDED, "导航播报音量已设为百分之$percent")
        } else finish(id, FAILED, unavailable)
    }

    fun recover(): String {
        val id = operations.begin(PLAN)
        val trip = session.snapshot.lastTrip
            ?: return finish(id, REJECTED, "当前没有可重新规划的应用内行程")
        if (session.snapshot.active != null || session.snapshot.starting != null) {
            return finish(id, REJECTED, "当前导航状态尚未结束")
        }
        submitPlan(MapPlanRequest(id, trip, position(), RoutePreference.DEFAULT), allowStart = true)
        return "正在按原目的地重新规划，请选择路线后开始"
    }

    private fun finish(id: String, outcome: NavigationOutcome, message: String): String {
        operations.finish(id, outcome)
        return message
    }

    private fun recordUnavailable(operation: NavigationOperation, outcome: NavigationOutcome, message: String): String =
        finish(operations.begin(operation), outcome, message)

    /** Voice input can arrive off-main. Read-only answers use a coherent immutable snapshot. */
    fun resolveVoiceCommand(text: String): (() -> String)? {
        val normalized = text.filterNot(Char::isWhitespace).trim { it in "。！？!?.，," }
        val routeIndex = ROUTE_ORDINAL.matchEntire(normalized)?.groupValues?.get(1)?.let { ordinal ->
            listOf("一", "二", "三", "四", "五").indexOf(ordinal).takeIf { it >= 0 }
                ?: ordinal.toIntOrNull()?.minus(1)
        }
        if (routeIndex != null) {
            val plan = latest.snapshot.plan ?: return { recordUnavailable(START, REJECTED, "当前没有可选择的路线列表") }
            if (!plan.selectable) return { recordUnavailable(START, UNSUPPORTED, "这是路线预览，高德地图会在出发时重新规划") }
            val route = plan.routes?.getOrNull(routeIndex)
                ?: return { recordUnavailable(START, REJECTED, "路线列表中没有这条路线") }
            return { onMain { start(plan.request.id, route.id) }; "" }
        }
        val current = latest
        val currentPlan = current.snapshot.plan
        when (val command = parseRouteVoiceCommand(normalized)) {
            is RouteVoiceCommand.Preference -> if (currentPlan != null) {
                val planId = currentPlan.request.id
                return {
                    onMain { changePreference(planId, command.value) }
                    "正在按${command.value.label}查询路线"
                }
            }
            RouteVoiceCommand.PrepareStart -> {
                val plan = currentPlan
                    ?: return { recordUnavailable(START, REJECTED, "当前没有路线预览，请先查询路线") }
                if (!current.embeddedEnabled) {
                    return { recordUnavailable(START, UNSUPPORTED, "当前只有高德路线预览，无法按预览路线启动。请重新发起目的地导航。") }
                }
                if (plan.selectable) return { recordUnavailable(START, REJECTED, "请选择一条路线后开始导航") }
                if (plan.routes == null) return { recordUnavailable(START, REJECTED, "路线还在查询，请稍后确认") }
                return {
                    onMain { prepareStart(plan.request.id) }
                    "已请求重新规划，请选择路线后开始导航"
                }
            }
            null -> Unit
        }
        val presentation = parsePresentationVoiceCommand(normalized)
        if (presentation != null) return { applyPresentationCommand(current, presentation) }
        if (normalized !in COMMANDS &&
            MapAlongCategory.entries.none { normalized in it.commands() }) return null
        return { voiceCommand(normalized, current).orEmpty() }
    }

    private fun voiceCommand(normalized: String, state: NavigationRuntimeUi): String? {
        return when (normalized) {
            "剩余里程", "还剩多少公里", "还要走多远", "导航还剩多远", "还剩多远" ->
                state.liveProgress()?.meters?.let { "剩余${distance(it)}" } ?: "当前无法确认导航剩余里程"
            "剩余时间", "还要多久", "还有多久", "还要多久到", "导航还剩多久" ->
                state.liveProgress()?.seconds?.let { "预计还要${duration(it)}" } ?: "当前无法确认导航剩余时间"
            "几点到", "预计几点到", "预计到达时间", "什么时候到" -> {
                val observed = state.liveProgress()
                val seconds = observed?.seconds
                if (seconds != null) {
                    val arrival = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - observed.observedAtMs) + seconds * 1_000L
                    "预计${DateFormat.getTimeFormat(context).format(Date(arrival))}到达"
                } else "当前无法确认预计到达时间"
            }
            "结束导航", "停止导航" -> control(state, STOP, "请在高德地图内结束导航", ::requestStop)
            "确认结束导航" -> control(state, STOP, "当前没有可结束的应用内导航", ::requestStop)
            "继续导航", "恢复导航", "继续刚才的导航" -> when {
                state.snapshot.active != null -> "当前行程状态尚未结束"
                state.snapshot.lastTrip != null -> "可以按原目的地重新规划，请说按原目的地重新规划"
                else -> "当前无法恢复行程，请重新指定目的地"
            }
            "按原目的地重新规划" -> {
                val trip = state.snapshot.lastTrip
                    ?: return recordUnavailable(PLAN, REJECTED, "没有可重新规划的行程")
                enqueue(PLAN, { session.snapshot.lastTrip === trip }, ::recover)
            }
            "取消沿途搜索", "放弃沿途搜索" -> {
                val search = state.snapshot.search
                    ?: return recordUnavailable(CANCEL_SEARCH, REJECTED, "当前没有沿途搜索")
                enqueue(CANCEL_SEARCH, {
                    session.snapshot.active?.identity == search.identity &&
                        session.snapshot.search?.operationId == search.operationId
                }, ::cancelSearch)
            }
            else -> {
                val category = MapAlongCategory.entries.firstOrNull {
                    normalized in it.commands()
                }
                if (category != null) control(state, SEARCH, "当前无法搜索高德路线沿途地点") { search(category) }
                else null
            }
        }
    }

    private fun applyPresentationCommand(state: NavigationRuntimeUi, command: PresentationVoiceCommand): String {
        return when (command) {
            is PresentationVoiceCommand.Mute -> control(state, AUDIO_MUTE, "当前无法调整导航播报") { mute(command.muted) }
            is PresentationVoiceCommand.Volume -> control(state, AUDIO_VOLUME, "当前无法调整导航播报音量") { volume(command.percent) }
            is PresentationVoiceCommand.AdjustVolume -> control(state, AUDIO_VOLUME, "当前无法调整导航播报音量") {
                volume((guidanceVolumePercent + command.delta).coerceIn(0, 100))
            }
            is PresentationVoiceCommand.View -> control(state, MAP_CONTROL, "当前无法调整地图") { mapAction(command.action) }
            PresentationVoiceCommand.ClearHistory ->
                "AutoVoice没有保存导航搜索历史。如需清除高德地图的记录，请在高德地图内操作。"
        }
    }

    private fun MapAlongCategory.commands() =
        (listOf(label) + if (this == MapAlongCategory.TOILET) listOf("厕所", "卫生间") else emptyList())
            .flatMap { name -> listOf("沿途找$name", "沿途找个$name", "找个沿途$name", "找沿途$name", "搜沿途$name") }

    private fun control(
        state: NavigationRuntimeUi,
        operation: NavigationOperation,
        unavailable: String,
        action: () -> String,
    ): String {
        val identity = state.snapshot.active?.identity
        if (!state.embeddedEnabled) return recordUnavailable(operation, UNSUPPORTED, unavailable)
        if (identity == null || !acceptsNavigationControl(state.snapshot, identity)) {
            return recordUnavailable(operation, REJECTED, unavailable)
        }
        return enqueue(operation, { acceptsNavigationControl(session.snapshot, identity) }, action)
    }

    private fun enqueue(operation: NavigationOperation, isCurrent: () -> Boolean, action: () -> String): String {
        fun execute(): String {
            if (!isCurrent()) return recordUnavailable(operation, REJECTED, "")
            return action().also { update(it) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return if (closed) recordUnavailable(operation, UNKNOWN, "") else execute()
        }
        onMain(onClosed = { recordUnavailable(operation, UNKNOWN, "") }) { execute() }
        return "正在处理导航操作，请查看结果"
    }

    private fun failPlan(id: String, outcome: NavigationOutcome) {
        if (!session.planFailed(id)) return
        operations.finish(id, outcome)
        update(if (outcome == NO_RESULTS) "高德未返回可用路线，请调整地点或偏好" else "暂未取得高德路线，请稍后重试")
    }

    private fun failSearch(id: String, identity: MapTripIdentity, outcome: NavigationOutcome) {
        val search = session.snapshot.search
        if (search?.operationId != id || search.identity != identity || search.results != null) return
        session.cancelSearch()
        operations.finish(id, outcome)
        update("暂未取得沿途地点")
    }

    private fun NavigationRuntimeUi.liveProgress(): MapProgress? =
        freshProgress?.takeIf { SystemClock.elapsedRealtime() - it.observedAtMs in 0..30_000 }

    private fun onMapEvent(event: NavigationMapEvent) = onMain {
        when (event) {
            is NavigationMapEvent.Routes -> {
                if (event.values.isEmpty()) {
                    failPlan(event.operationId, NO_RESULTS)
                } else if (session.routes(event.operationId, event.values)) {
                    operations.finish(event.operationId, SUCCEEDED)
                    update()
                }
            }
            is NavigationMapEvent.PlanFailed -> failPlan(event.operationId, FAILED)
            is NavigationMapEvent.Started -> if (session.started(event.request)) {
                operations.finish(event.request.tripId, SUCCEEDED)
                mapVisible = true
                update("已收到高德开始导航的确认")
            }
            is NavigationMapEvent.StartFailed -> if (session.startFailed(event.request)) {
                operations.finish(event.request.tripId, FAILED)
                update("高德未接受开始请求，请重试")
            }
            is NavigationMapEvent.Progress -> if (session.progress(event.value)) {
                update(keepMessage = true)
                main.removeCallbacks(expireProgress)
                main.postDelayed(expireProgress, 30_001)
            }
            is NavigationMapEvent.RouteChanged -> {
                val search = session.snapshot.search
                if (session.changeRoute(event.previous, event.pathId)) {
                    operations.finish(search?.operationId, CANCELLED)
                    update("高德已更新当前路线")
                }
            }
            is NavigationMapEvent.Arrived -> {
                val search = session.snapshot.search
                if (session.arrived(event.identity)) {
                    operations.finish(search?.operationId, CANCELLED)
                    mapVisible = false
                    update("高德报告已到达目的地")
                }
            }
            is NavigationMapEvent.SearchResults -> {
                val values = event.values
                if (values == null) {
                    failSearch(event.operationId, event.identity, FAILED)
                } else if (session.searchResults(event.operationId, event.identity, values)) {
                    operations.finish(event.operationId, if (values.isEmpty()) NO_RESULTS else SUCCEEDED)
                    update(if (values.isEmpty()) "这段剩余路线没有返回符合类别的地点" else null)
                }
            }
        }
    }

    private val expireProgress = Runnable { update(keepMessage = true) }

    private fun update(message: String? = null, keepMessage: Boolean = false) {
        if (closed) return
        val state = NavigationRuntimeUi(
            sdkConfigured = key.isNotBlank(), embeddedEnabled = sdkEnabled, snapshot = session.snapshot,
            message = if (keepMessage) latest.message else message, showMap = mapVisible,
            freshProgress = session.freshProgress(),
        )
        latest = state
        publish(state)
    }

    private fun onMain(onClosed: () -> Unit = {}, action: () -> Unit) {
        val run = Runnable { if (closed) onClosed() else action() }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run()
        else main.post(run)
    }

    override fun close() {
        closed = true
        operations.finishPending(UNKNOWN)
        previewJob?.cancel()
        main.removeCallbacksAndMessages(null)
        session.close()
        runCatching { sdk?.close() }
        sdk = null
        sdkEnabled = false
    }

    companion object {
        private const val PLAN_TIMEOUT_MS = 12_000L
        private val COMMANDS = setOf(
            "剩余里程", "还剩多少公里", "还要走多远", "导航还剩多远", "还剩多远",
            "剩余时间", "还要多久", "还有多久", "还要多久到", "导航还剩多久",
            "几点到", "预计几点到", "预计到达时间", "什么时候到",
            "结束导航", "停止导航", "确认结束导航", "继续导航", "恢复导航", "继续刚才的导航", "按原目的地重新规划",
            "取消沿途搜索", "放弃沿途搜索",
        )
        private val ROUTE_ORDINAL = Regex("(?:走|选|选择)第([一二三四五1-5])条(?:路线)?")
        fun distance(meters: Int) = if (meters < 1_000) "${meters}米" else
            "${String.format(java.util.Locale.ROOT, "%.1f", meters / 1000.0)}公里"
        fun duration(seconds: Int) = if (seconds < 60) "${seconds}秒" else "约${(seconds + 59) / 60}分钟"
    }
}

internal sealed interface RouteVoiceCommand {
    data class Preference(val value: RoutePreference) : RouteVoiceCommand
    data object PrepareStart : RouteVoiceCommand
}

/** Match complete commands so polite negatives do not trigger route changes or departure. */
internal fun parseRouteVoiceCommand(text: String): RouteVoiceCommand? = when (text) {
    "按此目的地开始", "按这个目的地开始" -> RouteVoiceCommand.PrepareStart
    "时间优先", "最快路线", "走最快的路线" -> RouteVoiceCommand.Preference(RoutePreference.FASTEST)
    "少收费" -> RouteVoiceCommand.Preference(RoutePreference.LESS_TOLLS)
    "少走高速", "不走高速" -> RouteVoiceCommand.Preference(RoutePreference.AVOID_HIGHWAYS)
    "高速优先" -> RouteVoiceCommand.Preference(RoutePreference.PREFER_HIGHWAYS)
    "躲避拥堵", "避开拥堵" -> RouteVoiceCommand.Preference(RoutePreference.AVOID_CONGESTION)
    else -> null
}

internal sealed interface PresentationVoiceCommand {
    data class Mute(val muted: Boolean) : PresentationVoiceCommand
    data class Volume(val percent: Int) : PresentationVoiceCommand
    data class AdjustVolume(val delta: Int) : PresentationVoiceCommand
    data class View(val action: MapViewAction) : PresentationVoiceCommand
    data object ClearHistory : PresentationVoiceCommand
}

private val navigationVolumePattern = Regex("导航音量(?:调到|设为|调成|到)?(?:百分之)?([0-9]{1,3})%?")

internal fun parsePresentationVoiceCommand(text: String): PresentationVoiceCommand? = when (text) {
    "导航静音", "关闭导航播报", "导航别说话" -> PresentationVoiceCommand.Mute(true)
    "恢复导航播报", "打开导航播报", "恢复导航语音", "导航取消静音" -> PresentationVoiceCommand.Mute(false)
    "导航声音小一点", "导航音量小一点" -> PresentationVoiceCommand.AdjustVolume(-10)
    "导航声音大一点", "导航音量大一点" -> PresentationVoiceCommand.AdjustVolume(10)
    "放大地图" -> PresentationVoiceCommand.View(MapViewAction.ZOOM_IN)
    "缩小地图" -> PresentationVoiceCommand.View(MapViewAction.ZOOM_OUT)
    "查看全程", "全程总览", "看看全程" -> PresentationVoiceCommand.View(MapViewAction.OVERVIEW)
    "切换路况图" -> PresentationVoiceCommand.View(MapViewAction.TRAFFIC)
    "显示路况", "显示路况图", "打开路况" -> PresentationVoiceCommand.View(MapViewAction.TRAFFIC_ON)
    "关闭路况", "关闭路况图", "关掉路况" -> PresentationVoiceCommand.View(MapViewAction.TRAFFIC_OFF)
    "切换卫星图" -> PresentationVoiceCommand.View(MapViewAction.SATELLITE)
    "显示卫星图", "打开卫星图" -> PresentationVoiceCommand.View(MapViewAction.SATELLITE_ON)
    "关闭卫星图", "关掉卫星图", "显示普通地图" -> PresentationVoiceCommand.View(MapViewAction.SATELLITE_OFF)
    "清除导航记录", "清除搜索记录", "清除导航搜索记录", "删除导航搜索记录" ->
        PresentationVoiceCommand.ClearHistory
    else -> navigationVolumePattern.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()
        ?.let { PresentationVoiceCommand.Volume(it) }
}

internal fun acceptsNavigationControl(snapshot: NavigationRouteSnapshot, identity: MapTripIdentity): Boolean =
    snapshot.active?.identity == identity && snapshot.stopping == null

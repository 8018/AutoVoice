package com.autovoice.app.navigation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.amap.api.maps.AMap
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.model.LatLng
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviIndependentRouteListener
import com.amap.api.navi.AMapNaviListener
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.AMapNaviViewOptions
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.enums.TransportType
import com.amap.api.navi.model.*
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.routepoisearch.RoutePOISearch
import com.amap.api.services.routepoisearch.RoutePOISearch.RoutePOISearchType
import com.amap.api.services.routepoisearch.RoutePOISearchQuery
import com.autovoice.voicebusiness.navigation.*

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class AmapNavigationMap(
    private val context: Context,
    private val navi: AMapNavi,
    private val onEvent: (NavigationMapEvent) -> Unit,
) : AndroidNavigationMap, AMapNaviListener {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = AmapGuidanceAudio(context)
    private var closed = false
    private var available = true
    private var planId: String? = null
    private var group: AMapNaviPathGroup? = null
    private var starting: MapStart? = null
    private var active: MapTripIdentity? = null
    private val retiredPaths = mutableSetOf<String>()
    private var currentObservation: Observation? = null
    private var view: AMapNaviView? = null
    private var viewResumed = false
    private var viewLoaded = false
    private var normalMapType: Int? = null
    private var stopping = false

    init {
        navi.setUseInnerVoice(false)
        navi.addAMapNaviListener(this)
    }

    override fun plan(request: MapPlanRequest): Boolean {
        if (closed || !available || active != null || starting != null) return false
        group = null
        planId = request.id
        val origin = request.origin?.let { poi(it, gps = true) }
        if (request.origin != null && origin == null) return false
        val destination = poi(request.trip.destination, request.destinationIsGps) ?: return false
        val waypoints = request.trip.waypoints.map { poi(it) ?: return false }
        return navi.independentCalculateRoute(origin, destination, waypoints, strategy(request.preference),
            TransportType.Drive, object : AMapNaviIndependentRouteListener {
                override fun onIndependentCalculateSuccess(value: AMapNaviPathGroup?) = dispatch {
                    if (planId != request.id || group != null || starting != null || active != null) return@dispatch
                    val routes = value?.let { paths ->
                        (0 until paths.pathCount).mapNotNull { index ->
                            paths.getPath(index)?.takeIf { it.pathid > 0 }?.let { path ->
                                MapRoute(path.pathid.toString(), path.allLength.takeIf { it > 0 },
                                    path.allTime.takeIf { it > 0 }, path.tollCost.takeIf { it >= 0 }?.toDouble())
                            }
                        }.distinctBy { it.id }
                    }.orEmpty()
                    if (routes.isEmpty()) {
                        planId = null
                        onEvent(NavigationMapEvent.PlanFailed(request.id))
                    } else {
                        group = value
                        onEvent(NavigationMapEvent.Routes(request.id, routes))
                    }
                }

                override fun onIndependentCalculateFail(result: AMapCalcRouteResult?) = dispatch {
                    if (planId != request.id || group != null) return@dispatch
                    planId = null
                    onEvent(NavigationMapEvent.PlanFailed(request.id))
                }

                override fun onIndependentCalculateInvoke(requestId: Int) = Unit
            })
    }

    override fun start(request: MapStart): Boolean {
        if (closed || !available || planId != request.planId || active != null || starting != null) return false
        val paths = group ?: return false
        val index = (0 until paths.pathCount).firstOrNull { paths.getPath(it)?.pathid?.toString() == request.pathId }
            ?: return false
        if (paths.mainPathIndex != index && !paths.selectRouteWithIndex(index)) return false
        if (paths.mainPath?.pathid?.toString() != request.pathId) return false
        starting = request
        if (navi.startNaviWithPath(NaviType.GPS, paths)) return true
        starting = null
        return false
    }

    override fun stop(operationId: String, identity: MapTripIdentity): Boolean {
        if (closed || active != identity || stopping) return false
        navi.stopNavi()
        audio.stop()
        stopping = true
        return true
    }

    override fun search(operationId: String, identity: MapTripIdentity, category: MapAlongCategory): Boolean {
        if (closed || stopping || active != identity) return false
        val observation = currentObservation?.takeIf {
            it.pathId == identity.pathId && SystemClock.elapsedRealtime() - it.timestamp in 0..30_000
        } ?: return false
        val path = navi.naviPath?.takeIf { it.pathid.toString() == identity.pathId } ?: return false
        val remainder = remainingPolyline(path, observation) ?: return false
        val query = RoutePOISearchQuery(remainder, category.searchType(), 500)
        val search = RoutePOISearch(context, query)
        search.setPoiSearchListener { result, code ->
            dispatch {
                if (active != identity || stopping) return@dispatch
                val stops = if (code == AMapException.CODE_AMAP_SUCCESS && result != null) {
                    result.routePois.orEmpty().mapNotNull { item ->
                        val point = item.point ?: return@mapNotNull null
                        val name = item.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        runCatching { MapAlongStop(NavigationTarget(name, point.latitude, point.longitude)) }.getOrNull()
                    }.take(5)
                } else null
                onEvent(NavigationMapEvent.SearchResults(operationId, identity, stops))
            }
        }
        search.searchRoutePOIAsyn()
        return true
    }

    private fun remainingPolyline(path: AMapNaviPath, observation: Observation): List<LatLonPoint>? {
        val steps = path.steps ?: return null
        val step = steps.getOrNull(observation.step) ?: return null
        val links = step.links ?: return null
        val coordinates = links.getOrNull(observation.link)?.coords ?: return null
        if (observation.point !in coordinates.indices) return null
        val remaining = coordinates.drop(observation.point) +
            links.drop(observation.link + 1).flatMap { it.coords.orEmpty() } +
            steps.drop(observation.step + 1).flatMap { it.coords.orEmpty() }
        return remaining.map { LatLonPoint(it.latitude, it.longitude) }.distinctAdjacent().takeIf { it.size >= 2 }
    }

    private fun List<LatLonPoint>.distinctAdjacent(): List<LatLonPoint> = filterIndexed { i, point ->
        i == 0 || point.latitude != this[i - 1].latitude || point.longitude != this[i - 1].longitude
    }

    override fun setGuidanceMuted(muted: Boolean) = active != null && !stopping && audio.mute(muted)

    override fun setGuidanceVolume(volume: Float) = active != null && !stopping && audio.volume(volume)

    override fun createView(context: Context): View? {
        if (closed || view != null) return null
        val options = AMapNaviViewOptions().apply {
            setLayoutVisible(true)
            setAutoChangeZoom(false)
            setBroadcastModeEnabled(false)
        }
        return AMapNaviView(context, options).also { current ->
            current.onCreate(null)
            current.setOnMapLoadedListener {
                if (view === current) {
                    normalMapType = current.map.mapType
                    viewLoaded = true
                }
            }
            view = current
        }
    }

    override fun resumeView(view: View) {
        val current = this.view ?: return
        if (view === current && !viewResumed) {
            current.onResume()
            viewResumed = true
        }
    }

    override fun pauseView(view: View) {
        val current = this.view ?: return
        if (view === current && viewResumed) {
            current.onPause()
            viewResumed = false
        }
    }

    override fun releaseView(view: View) {
        val current = this.view ?: return
        if (view !== current) return
        pauseView(current)
        this.view = null
        viewLoaded = false
        normalMapType = null
        current.onDestroy()
    }

    override fun applyView(action: MapViewAction): Boolean {
        if (!viewLoaded || !viewResumed || active == null || stopping) return false
        val current = view ?: return false
        val map = current.map ?: return false
        when (action) {
            MapViewAction.ZOOM_IN -> {
                if (map.cameraPosition.zoom >= map.maxZoomLevel) return false
                current.zoomIn()
            }
            MapViewAction.ZOOM_OUT -> {
                if (map.cameraPosition.zoom <= map.minZoomLevel) return false
                current.zoomOut()
            }
            MapViewAction.OVERVIEW -> current.displayOverview()
            MapViewAction.TRAFFIC, MapViewAction.TRAFFIC_ON, MapViewAction.TRAFFIC_OFF -> {
                val requested = action.targetLayer(map.isTrafficEnabled)
                map.isTrafficEnabled = requested
                return map.isTrafficEnabled == requested
            }
            MapViewAction.SATELLITE, MapViewAction.SATELLITE_ON, MapViewAction.SATELLITE_OFF -> {
                val enabled = action.targetLayer(map.mapType == AMap.MAP_TYPE_SATELLITE)
                val requested = if (enabled) AMap.MAP_TYPE_SATELLITE else normalMapType ?: AMap.MAP_TYPE_NAVI
                map.mapType = requested
                return map.mapType == requested
            }
        }
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        view?.let { runCatching { releaseView(it) } }
        navi.removeAMapNaviListener(this)
        if (starting != null || active != null) navi.stopNavi()
        audio.close()
        group = null
        AMapNavi.destroy()
    }

    override fun onInitNaviFailure() = dispatch {
        available = false
        starting?.let { onEvent(NavigationMapEvent.StartFailed(it)) }
        if (starting == null && group == null) planId?.let { onEvent(NavigationMapEvent.PlanFailed(it)) }
        starting = null
        planId = null
    }

    override fun onInitNaviSuccess() = Unit

    override fun onStartNavi(type: Int) = dispatch {
        val request = starting ?: return@dispatch
        if (type != NaviType.GPS || navi.naviPath?.pathid?.toString() != request.pathId) return@dispatch
        confirmStart(request)
    }

    private fun confirmStart(request: MapStart) {
        if (starting != request) return
        active = MapTripIdentity(request.tripId, 1, request.pathId)
        starting = null
        planId = null
        currentObservation = null
        retiredPaths.clear()
        stopping = false
        onEvent(NavigationMapEvent.Started(request))
    }

    override fun onNaviInfoUpdate(info: NaviInfo?) {
        if (info == null || info.pathId <= 0) return
        val observation = Observation(
            info.pathId.toString(), SystemClock.elapsedRealtime(), info.curStep, info.curLink, info.curPoint,
            info.pathRetainDistance.takeIf { it >= 0 }, info.pathRetainTime.takeIf { it >= 0 },
        )
        dispatch {
            val actualPath = navi.naviPath?.pathid?.toString()
            if (observation.pathId != actualPath || stopping) return@dispatch
            // Some versions report the path only on the first identity-bearing progress callback.
            starting?.takeIf { it.pathId == actualPath && AMapNavi.isNaviStarted() }?.let(::confirmStart)
            val current = active ?: return@dispatch
            if (observation.timestamp <= (currentObservation?.timestamp ?: Long.MIN_VALUE)) return@dispatch
            if (observation.pathId != current.pathId) {
                if (observation.pathId in retiredPaths) return@dispatch
                retiredPaths += current.pathId
                active = current.copy(revision = current.revision + 1, pathId = observation.pathId)
                onEvent(NavigationMapEvent.RouteChanged(current, observation.pathId))
            }
            currentObservation = observation
            onEvent(NavigationMapEvent.Progress(MapProgress(
                checkNotNull(active), observation.timestamp, observation.meters, observation.seconds,
            )))
        }
    }

    override fun onArriveDestination() = dispatch {
        val current = active ?: return@dispatch
        if (navi.naviPath?.pathid?.toString() != current.pathId) return@dispatch
        active = null
        currentObservation = null
        audio.stop()
        stopping = false
        onEvent(NavigationMapEvent.Arrived(current))
    }

    override fun onGetNavigationText(type: Int, text: String?) = dispatch {
        if (active != null && !stopping && text != null) audio.speak(text)
    }

    // AMap emits both overloads on some engines. Use only the typed callback.
    override fun onGetNavigationText(text: String?) = Unit

    private fun poi(target: NavigationTarget, gps: Boolean = false): NaviPoi? {
        val raw = LatLng(target.latitude, target.longitude)
        val coordinate = if (gps) CoordinateConverter(context).from(CoordinateConverter.CoordType.GPS)
            .coord(raw).convert() ?: return null else raw
        return NaviPoi(target.name, coordinate, null)
    }

    private fun dispatch(action: () -> Unit) {
        handler.post { if (!closed) action() }
    }

    private fun strategy(preference: RoutePreference): Int = when (preference) {
        RoutePreference.DEFAULT -> PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT
        RoutePreference.FASTEST -> PathPlanningStrategy.DRIVING_MULTIPLE_SHORTEST_TIME_DISTANCE
        RoutePreference.LESS_TOLLS -> PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_AVOID_COST
        RoutePreference.AVOID_HIGHWAYS -> PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_AVOID_HIGHSPEED
        RoutePreference.PREFER_HIGHWAYS -> PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_PRIORITY_HIGHSPEED
        RoutePreference.AVOID_CONGESTION -> PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_AVOID_CONGESTION
    }

    private fun MapAlongCategory.searchType(): RoutePOISearchType = when (this) {
        MapAlongCategory.TOILET -> RoutePOISearchType.TypeToilet
        MapAlongCategory.SERVICE_AREA -> RoutePOISearchType.TypeServiceArea
        MapAlongCategory.CHARGING -> RoutePOISearchType.TypeChargeStation
        MapAlongCategory.GAS -> RoutePOISearchType.TypeGasStation
        MapAlongCategory.FOOD -> RoutePOISearchType.TypeFood
    }

    private data class Observation(
        val pathId: String, val timestamp: Long, val step: Int, val link: Int, val point: Int,
        val meters: Int?, val seconds: Int?,
    )

    override fun onTrafficStatusUpdate() = Unit
    override fun onLocationChange(location: AMapNaviLocation?) = Unit
    override fun onEndEmulatorNavi() = Unit
    override fun onCalculateRouteFailure(code: Int) = Unit
    override fun onReCalculateRouteForYaw() = Unit
    override fun onReCalculateRouteForTrafficJam() = Unit
    override fun onArrivedWayPoint(index: Int) = Unit
    override fun onGpsOpenStatus(open: Boolean) = Unit
    override fun updateCameraInfo(info: Array<out AMapNaviCameraInfo>?) = Unit
    override fun updateIntervalCameraInfo(start: AMapNaviCameraInfo?, end: AMapNaviCameraInfo?, type: Int) = Unit
    override fun onServiceAreaUpdate(info: Array<out AMapServiceAreaInfo>?) = Unit
    override fun showCross(cross: AMapNaviCross?) = Unit
    override fun hideCross() = Unit
    override fun showModeCross(cross: AMapModelCross?) = Unit
    override fun hideModeCross() = Unit
    override fun showLaneInfo(info: Array<out AMapLaneInfo>?, background: ByteArray?, front: ByteArray?) = Unit
    override fun showLaneInfo(info: AMapLaneInfo?) = Unit
    override fun hideLaneInfo() = Unit
    override fun onCalculateRouteSuccess(routeIds: IntArray?) = Unit
    override fun notifyParallelRoad(type: Int) = Unit
    override fun OnUpdateTrafficFacility(info: Array<out AMapNaviTrafficFacilityInfo>?) = Unit
    override fun OnUpdateTrafficFacility(info: AMapNaviTrafficFacilityInfo?) = Unit
    override fun updateAimlessModeStatistics(info: AimLessModeStat?) = Unit
    override fun updateAimlessModeCongestionInfo(info: AimLessModeCongestionInfo?) = Unit
    override fun onPlayRing(type: Int) = Unit
    override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) = Unit
    override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) = Unit
    override fun onNaviRouteNotify(data: AMapNaviRouteNotifyData?) = Unit
    override fun onGpsSignalWeak(weak: Boolean) = Unit
}

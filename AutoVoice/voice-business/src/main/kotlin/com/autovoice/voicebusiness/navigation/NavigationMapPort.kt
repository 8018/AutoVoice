package com.autovoice.voicebusiness.navigation

enum class RoutePreference(val label: String) {
    DEFAULT("推荐"), FASTEST("时间优先"), LESS_TOLLS("少收费"),
    AVOID_HIGHWAYS("少走高速"), PREFER_HIGHWAYS("高速优先"), AVOID_CONGESTION("躲避拥堵"),
}

data class MapPlanRequest(
    val id: String,
    val trip: NavigationTrip,
    val origin: NavigationTarget?,
    val preference: RoutePreference,
    val destinationIsGps: Boolean = false,
)

data class MapRoute(val id: String, val meters: Int?, val seconds: Int?, val tollYuan: Double?)
data class MapStart(val tripId: String, val planId: String, val pathId: String)
data class MapTripIdentity(val tripId: String, val revision: Long, val pathId: String)
data class MapProgress(val identity: MapTripIdentity, val observedAtMs: Long, val meters: Int?, val seconds: Int?)
data class MapAlongStop(val target: NavigationTarget, val extraMeters: Int? = null, val extraSeconds: Int? = null)
enum class MapAlongCategory(val label: String) {
    TOILET("洗手间"), SERVICE_AREA("服务区"), CHARGING("充电站"), GAS("加油站"), FOOD("餐饮"),
}
enum class MapViewAction {
    ZOOM_IN, ZOOM_OUT, OVERVIEW, TRAFFIC, TRAFFIC_ON, TRAFFIC_OFF, SATELLITE, SATELLITE_ON, SATELLITE_OFF;

    /** Explicit display requests are idempotent. Toggles intentionally reverse the current layer. */
    fun targetLayer(current: Boolean): Boolean = when (this) {
        TRAFFIC_ON, SATELLITE_ON -> true
        TRAFFIC_OFF, SATELLITE_OFF -> false
        TRAFFIC, SATELLITE -> !current
        else -> error("This map action does not target a layer")
    }
}

sealed interface NavigationMapEvent {
    data class Routes(val operationId: String, val values: List<MapRoute>) : NavigationMapEvent
    data class PlanFailed(val operationId: String) : NavigationMapEvent
    data class Started(val request: MapStart) : NavigationMapEvent
    data class StartFailed(val request: MapStart) : NavigationMapEvent
    data class Progress(val value: MapProgress) : NavigationMapEvent
    data class RouteChanged(val previous: MapTripIdentity, val pathId: String) : NavigationMapEvent
    data class Arrived(val identity: MapTripIdentity) : NavigationMapEvent
    data class SearchResults(
        val operationId: String,
        val identity: MapTripIdentity,
        val values: List<MapAlongStop>?,
    ) : NavigationMapEvent
}

/** Calls must be serialized on the provider's owner thread. Boolean returns describe request acceptance. */
interface NavigationMapPort : AutoCloseable {
    fun plan(request: MapPlanRequest): Boolean
    fun start(request: MapStart): Boolean
    fun stop(operationId: String, identity: MapTripIdentity): Boolean
    fun search(operationId: String, identity: MapTripIdentity, category: MapAlongCategory): Boolean
    fun setGuidanceMuted(muted: Boolean): Boolean
    fun setGuidanceVolume(volume: Float): Boolean
    fun applyView(action: MapViewAction): Boolean
}

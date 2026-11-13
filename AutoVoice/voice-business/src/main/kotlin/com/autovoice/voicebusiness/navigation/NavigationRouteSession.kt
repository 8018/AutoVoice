package com.autovoice.voicebusiness.navigation

data class MapPlan(
    val request: MapPlanRequest,
    val routes: List<MapRoute>? = null,
    val selectable: Boolean = true,
)
data class MapActiveTrip(val identity: MapTripIdentity, val trip: NavigationTrip)
data class MapSearch(
    val operationId: String,
    val identity: MapTripIdentity,
    val results: List<MapAlongStop>? = null,
)
data class NavigationRouteSnapshot(
    val plan: MapPlan? = null,
    val starting: MapStart? = null,
    val active: MapActiveTrip? = null,
    val progress: MapProgress? = null,
    val stopping: String? = null,
    val search: MapSearch? = null,
    val lastTrip: NavigationTrip? = null,
)

/** State changes are serialized by the app. Every asynchronous result checks its original scope. */
class NavigationRouteSession(private val elapsedMs: () -> Long) {
    var snapshot = NavigationRouteSnapshot()
        private set
    private var closed = false

    fun beginPlan(request: MapPlanRequest, selectable: Boolean = true): Boolean {
        if (closed || snapshot.active != null || snapshot.starting != null || snapshot.plan?.request?.id == request.id) return false
        snapshot = snapshot.copy(plan = MapPlan(
            request.copy(trip = request.trip.copy(waypoints = request.trip.waypoints.toList())),
            selectable = selectable,
        ))
        return true
    }

    fun routes(operationId: String, routes: List<MapRoute>): Boolean {
        val plan = pendingPlan(operationId) ?: return false
        if (routes.isEmpty() || routes.map { it.id }.distinct().size != routes.size) return false
        snapshot = snapshot.copy(plan = plan.copy(routes = routes.map { route ->
            route.copy(meters = route.meters?.takeIf { it > 0 }, seconds = route.seconds?.takeIf { it > 0 },
                tollYuan = route.tollYuan?.takeIf { it.isFinite() && it >= 0 })
        }))
        return true
    }

    fun planFailed(operationId: String): Boolean {
        pendingPlan(operationId) ?: return false
        snapshot = snapshot.copy(plan = null)
        return true
    }

    private fun pendingPlan(operationId: String) =
        snapshot.plan?.takeIf { it.request.id == operationId && it.routes == null }

    fun dismissPlan(planId: String): Boolean {
        if (snapshot.starting != null || snapshot.plan?.request?.id != planId) return false
        snapshot = snapshot.copy(plan = null)
        return true
    }

    fun reserveStart(tripId: String, planId: String, pathId: String): MapStart? {
        if (snapshot.starting != null || snapshot.active != null) return null
        val plan = snapshot.plan?.takeIf { it.selectable && it.request.id == planId } ?: return null
        if (plan.routes?.none { it.id == pathId } != false) return null
        return MapStart(tripId, planId, pathId).also { snapshot = snapshot.copy(starting = it) }
    }

    fun started(request: MapStart): Boolean {
        if (snapshot.starting != request) return false
        val trip = snapshot.plan?.request?.trip ?: return false
        val identity = MapTripIdentity(request.tripId, 1, request.pathId)
        snapshot = snapshot.copy(plan = null, starting = null, active = MapActiveTrip(identity, trip), lastTrip = trip)
        return true
    }

    fun startFailed(request: MapStart): Boolean {
        if (snapshot.starting != request) return false
        snapshot = snapshot.copy(starting = null)
        return true
    }

    fun progress(value: MapProgress): Boolean {
        if (value.identity != snapshot.active?.identity || snapshot.stopping != null ||
            value.observedAtMs <= (snapshot.progress?.observedAtMs ?: Long.MIN_VALUE) ||
            value.observedAtMs > elapsedMs()) return false
        snapshot = snapshot.copy(progress = value.copy(meters = value.meters?.takeIf { it >= 0 },
            seconds = value.seconds?.takeIf { it >= 0 }))
        return true
    }

    fun freshProgress(): MapProgress? =
        snapshot.progress?.takeIf { snapshot.stopping == null && elapsedMs() - it.observedAtMs in 0..30_000 }

    fun changeRoute(previous: MapTripIdentity, pathId: String): Boolean {
        val active = snapshot.active?.takeIf { it.identity == previous } ?: return false
        if (pathId == previous.pathId || pathId.isBlank()) return false
        snapshot = snapshot.copy(
            active = active.copy(identity = previous.copy(revision = previous.revision + 1, pathId = pathId)),
            progress = null, search = null,
        )
        return true
    }

    fun reserveStop(operationId: String): MapTripIdentity? {
        val identity = snapshot.active?.identity ?: return null
        if (snapshot.stopping != null) return null
        snapshot = snapshot.copy(stopping = operationId)
        return identity
    }

    fun stopFailed(operationId: String, identity: MapTripIdentity): Boolean {
        if (snapshot.stopping != operationId || snapshot.active?.identity != identity) return false
        snapshot = snapshot.copy(stopping = null)
        return true
    }

    fun stopped(operationId: String, identity: MapTripIdentity): Boolean {
        if (snapshot.stopping != operationId) return false
        return arrived(identity)
    }

    fun arrived(identity: MapTripIdentity): Boolean {
        if (snapshot.active?.identity != identity) return false
        snapshot = snapshot.copy(active = null, progress = null, stopping = null, search = null)
        return true
    }

    fun beginSearch(operationId: String): MapTripIdentity? {
        val identity = snapshot.active?.identity ?: return null
        if (snapshot.stopping != null) return null
        snapshot = snapshot.copy(search = MapSearch(operationId, identity))
        return identity
    }

    fun searchResults(operationId: String, identity: MapTripIdentity, values: List<MapAlongStop>): Boolean {
        val search = snapshot.search?.takeIf {
            it.operationId == operationId && it.identity == identity && it.results == null
        } ?: return false
        snapshot = snapshot.copy(search = search.copy(results = values.toList()))
        return true
    }

    fun cancelSearch(): Boolean {
        if (snapshot.search == null) return false
        snapshot = snapshot.copy(search = null)
        return true
    }

    fun leaveTracking(): Boolean {
        if (snapshot.stopping == null && snapshot.starting == null) return false
        val previous = snapshot.active?.trip ?: snapshot.plan?.request?.trip ?: snapshot.lastTrip
        snapshot = NavigationRouteSnapshot(lastTrip = previous)
        return true
    }

    fun close() {
        closed = true
        snapshot = NavigationRouteSnapshot()
    }
}

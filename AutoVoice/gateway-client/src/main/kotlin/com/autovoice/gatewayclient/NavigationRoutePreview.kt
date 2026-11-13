package com.autovoice.gatewayclient

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

const val NAVIGATION_ROUTE_PREVIEW_CAPABILITY = "navigation_route_preview"

data class NavigationRouteCoordinate(
    val latitude: Double,
    val longitude: Double,
    val coordinateSystem: String,
) {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        require(coordinateSystem == "WGS84" || coordinateSystem == "GCJ02")
    }
}

data class NavigationRouteEstimate(
    val id: String,
    val distanceMeters: Long?,
    val durationSeconds: Long?,
    val tollYuan: Double?,
)

data class NavigationRoutePreviewResult(
    val operationId: String,
    val status: String,
    val routeSetId: String? = null,
    val plannedAtMs: Long? = null,
    val source: String? = null,
    val origin: NavigationRouteCoordinate? = null,
    val destination: NavigationRouteCoordinate? = null,
    val waypoints: List<NavigationRouteCoordinate> = emptyList(),
    val preference: String? = null,
    val routes: List<NavigationRouteEstimate> = emptyList(),
)

private val routePreviewJson = Gson()

/** A read-only request scoped to the connected session and the caller's operation identity. */
suspend fun GatewayClient.previewNavigationRoutes(
    operationId: String,
    origin: NavigationRouteCoordinate?,
    destination: NavigationRouteCoordinate,
    waypoints: List<NavigationRouteCoordinate> = emptyList(),
    preference: String = "DEFAULT",
): NavigationRoutePreviewResult = coroutineScope {
    require(operationId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
    require(waypoints.size <= 4)
    if (origin == null) return@coroutineScope NavigationRoutePreviewResult(operationId, "ORIGIN_REQUIRED")
    if (!supportsCapability(NAVIGATION_ROUTE_PREVIEW_CAPABILITY)) {
        return@coroutineScope NavigationRoutePreviewResult(operationId, "UNAVAILABLE")
    }
    val sessionId = currentSessionId() ?: throw GatewayException("not connected")
    val response = async(start = CoroutineStart.UNDISPATCHED) {
        messages.first { message ->
            message.type == "navigation_route_preview_result" &&
                message.payload.get("operationId")?.takeIf { it.isJsonPrimitive }?.asString == operationId
        }
    }
    try {
        send(
            NAVIGATION_ROUTE_PREVIEW_CAPABILITY,
            mapOf(
                "sessionId" to sessionId,
                "operationId" to operationId,
                "origin" to origin,
                "destination" to destination,
                "waypoints" to waypoints,
                "preference" to preference,
            ),
        )
        val message = withTimeoutOrNull(8_000) { response.await() }
            ?: throw GatewayException("route preview timed out")
        parseRoutePreview(message.payload, operationId)
    } finally {
        response.cancel()
    }
}

private fun parseRoutePreview(payload: JsonObject, operationId: String): NavigationRoutePreviewResult {
    try {
        val result = routePreviewJson.fromJson(payload, NavigationRoutePreviewResult::class.java)
        require(result.status in setOf("OK", "ORIGIN_REQUIRED", "INVALID_REQUEST", "UNAVAILABLE", "NO_ROUTE", "BUSY"))
        if (result.status != "OK") return NavigationRoutePreviewResult(operationId, result.status)

        require(!result.routeSetId.isNullOrBlank() && result.source == "AMAP_WEB")
        require(result.plannedAtMs != null && result.plannedAtMs > 0)
        require(result.origin?.coordinateSystem == "GCJ02" && result.destination?.coordinateSystem == "GCJ02")
        val points = listOf(result.origin, result.destination) + result.waypoints
        require(result.waypoints.size <= 4)
        require(points.all {
            it != null && it.coordinateSystem == "GCJ02" &&
                it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        })
        require(result.routes.size in 1..5)
        require(result.routes.map { it.id }.toSet().size == result.routes.size)
        require(result.routes.all {
            it.id.startsWith("${result.routeSetId}:") &&
                (it.distanceMeters == null || it.distanceMeters >= 0) &&
                (it.durationSeconds == null || it.durationSeconds >= 0) &&
                (it.tollYuan == null || (it.tollYuan.isFinite() && it.tollYuan >= 0))
        })
        return result
    } catch (error: RuntimeException) {
        throw GatewayException("invalid route preview response")
    }
}

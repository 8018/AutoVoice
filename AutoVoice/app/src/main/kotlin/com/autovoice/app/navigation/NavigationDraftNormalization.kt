package com.autovoice.app.navigation

import com.autovoice.gatewayclient.NavigationRouteCoordinate
import com.autovoice.gatewayclient.NavigationRoutePreviewResult
import com.autovoice.voicebusiness.navigation.NavigationCoordinateSystem.GCJ02
import com.autovoice.voicebusiness.navigation.NavigationCoordinateSystem.WGS84
import com.autovoice.voicebusiness.navigation.NavigationNormalizationResult
import com.autovoice.voicebusiness.navigation.NavigationTarget
import com.autovoice.voicebusiness.navigation.NavigationTrip

/** Reuses the official Web conversion. Only coordinates, never Web route IDs, cross into launch. */
internal fun NavigationRoutePreviewResult.normalizedTrip(
    expectedId: String,
    original: NavigationTrip,
): NavigationNormalizationResult {
    val unavailable = NavigationNormalizationResult.Failure.UNAVAILABLE
    if (operationId != expectedId) return unavailable
    when (status) {
        "ORIGIN_REQUIRED" -> return NavigationNormalizationResult.Failure.MISSING_LOCATION
        "NO_ROUTE" -> return NavigationNormalizationResult.Failure.NO_ROUTE
        "OK" -> Unit
        else -> return unavailable
    }
    if (source != "AMAP_WEB" || waypoints.size != original.waypoints.size) return unavailable

    val convertedDestination = original.destination.withConvertedPoint(destination) ?: return unavailable
    val convertedWaypoints = original.waypoints.zip(waypoints).map { (stop, point) ->
        stop.withConvertedPoint(point) ?: return unavailable
    }
    return NavigationNormalizationResult.Converted(NavigationTrip(convertedDestination, convertedWaypoints))
}

private fun NavigationTarget.withConvertedPoint(point: NavigationRouteCoordinate?): NavigationTarget? {
    if (point == null || point.coordinateSystem != GCJ02.name ||
        point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0
    ) return null
    if (coordinateSystem == WGS84) return copy(
        latitude = point.latitude, longitude = point.longitude, coordinateSystem = GCJ02,
    )
    return takeIf { point.latitude == latitude && point.longitude == longitude }
}

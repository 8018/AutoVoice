package com.autovoice.app

import com.autovoice.app.navigation.normalizedTrip
import com.autovoice.gatewayclient.NavigationRouteCoordinate
import com.autovoice.gatewayclient.NavigationRoutePreviewResult
import com.autovoice.voicebusiness.navigation.NavigationCoordinateSystem.WGS84
import com.autovoice.voicebusiness.navigation.NavigationNormalizationResult
import com.autovoice.voicebusiness.navigation.NavigationTarget
import com.autovoice.voicebusiness.navigation.NavigationTrip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NavigationDraftNormalizationTest {
    private val original = NavigationTrip(
        NavigationTarget("机场", 30.31, 104.4, WGS84),
        listOf(NavigationTarget("学校", 30.6, 104.0), NavigationTarget("博物馆", 30.66, 104.09, WGS84)),
    )
    private val response = NavigationRoutePreviewResult(
        operationId = "current",
        status = "OK",
        source = "AMAP_WEB",
        destination = NavigationRouteCoordinate(30.312, 104.415, "GCJ02"),
        waypoints = listOf(
            NavigationRouteCoordinate(30.6, 104.0, "GCJ02"),
            NavigationRouteCoordinate(30.662, 104.095, "GCJ02"),
        ),
    )

    @Test
    fun conversion_keeps_names_and_order() {
        val expected = NavigationTrip(
            NavigationTarget("机场", 30.312, 104.415),
            listOf(NavigationTarget("学校", 30.6, 104.0), NavigationTarget("博物馆", 30.662, 104.095)),
        )
        assertEquals(NavigationNormalizationResult.Converted(expected), response.normalizedTrip("current", original))
    }

    @Test
    fun stale_incomplete_or_non_gcj_response_is_rejected() {
        val badResponses = listOf(
            response.copy(operationId = "old"),
            response.copy(waypoints = response.waypoints.dropLast(1)),
            response.copy(waypoints = response.waypoints.reversed()),
            response.copy(destination = NavigationRouteCoordinate(30.31, 104.4, "WGS84")),
        )
        for (bad in badResponses) {
            assertEquals(NavigationNormalizationResult.Failure.UNAVAILABLE, bad.normalizedTrip("current", original))
        }
    }

    @Test
    fun provider_failures_do_not_produce_a_trip() {
        val statuses = mapOf(
            "NO_ROUTE" to NavigationNormalizationResult.Failure.NO_ROUTE,
            "ORIGIN_REQUIRED" to NavigationNormalizationResult.Failure.MISSING_LOCATION,
            "UNAVAILABLE" to NavigationNormalizationResult.Failure.UNAVAILABLE,
            "BUSY" to NavigationNormalizationResult.Failure.UNAVAILABLE,
        )
        for ((status, expected) in statuses) {
            assertEquals(expected, response.copy(status = status).normalizedTrip("current", original))
        }
    }
}

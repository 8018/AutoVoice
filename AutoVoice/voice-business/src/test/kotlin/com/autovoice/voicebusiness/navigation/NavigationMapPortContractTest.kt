package com.autovoice.voicebusiness.navigation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NavigationMapPortContractTest {
    private val trip = NavigationTrip(NavigationTarget("目的地", 31.2, 121.5))
    private val request = MapPlanRequest("p1", trip, null, RoutePreference.DEFAULT)
    private val option = MapRoute("42", 1200, 90, null)
    private var now = 1_000L
    private val state = NavigationRouteSession { now }

    @Test
    fun `a replacement plan rejects earlier results and old route selection`() {
        assertTrue(state.beginPlan(request))
        assertTrue(state.beginPlan(request.copy(id = "p2")))
        assertFalse(state.routes("p1", listOf(option)))
        assertTrue(state.routes("p2", listOf(option)))
        assertFalse(state.routes("p2", listOf(option.copy(id = "43"))))
        assertNull(state.reserveStart("s1", "p1", "42"))
        assertEquals("42", state.reserveStart("s2", "p2", "42")?.pathId)
        assertNull(state.reserveStart("s3", "p2", "42"))
    }

    @Test
    fun `progress requires a matching confirmed start and gets stale`() {
        start()
        val identity = state.snapshot.active!!.identity
        assertFalse(state.progress(MapProgress(identity.copy(pathId = "99"), 1_000, 100, 10)))
        assertTrue(state.progress(MapProgress(identity, 1_000, 950, 80)))
        assertFalse(state.progress(MapProgress(identity, 999, 1_000, 89)))
        assertFalse(state.progress(MapProgress(identity, 1_000, 1_000, 89)))
        assertEquals(950, state.freshProgress()?.meters)
        now += 30_001
        assertNull(state.freshProgress())
    }

    @Test
    fun `route change retires old observations and cancels old search`() {
        start()
        val original = state.snapshot.active!!.identity
        assertEquals(original, state.beginSearch("search"))
        assertTrue(state.changeRoute(original, "99"))
        assertFalse(state.progress(MapProgress(original, 1_000, 100, 10)))
        assertFalse(state.searchResults("search", original, emptyList()))
        val current = state.snapshot.active!!.identity
        assertEquals("99", current.pathId)
        assertEquals(original.revision + 1, current.revision)
        assertTrue(state.progress(MapProgress(current, 1_000, 100, 10)))
    }

    @Test
    fun `cancelling search retains the active route and discards late results`() {
        start()
        val original = state.snapshot.active!!.identity
        state.beginSearch("search")
        assertTrue(state.cancelSearch())
        assertFalse(state.searchResults("search", original, listOf(MapAlongStop(trip.destination))))
        assertEquals(original, state.snapshot.active?.identity)
    }

    @Test
    fun `stop submission and failures never confirm termination`() {
        start()
        val identity = state.snapshot.active!!.identity
        assertEquals(identity, state.reserveStop("stop1"))
        assertNull(state.reserveStop("duplicate"))
        assertTrue(state.stopFailed("stop1", identity))
        assertEquals(identity, state.snapshot.active?.identity)
        assertEquals(identity, state.reserveStop("stop2"))
        assertFalse(state.stopFailed("stop1", identity))
        assertNull(state.freshProgress())
        assertTrue(state.arrived(identity))
        assertFalse(state.arrived(identity))
        assertNull(state.snapshot.active)
        assertEquals(trip, state.snapshot.lastTrip)
    }

    @Test
    fun `failed start retains plan while its late acknowledgement is ignored`() {
        state.beginPlan(request)
        state.routes("p1", listOf(option))
        val first = state.reserveStart("s1", "p1", "42")!!
        assertTrue(state.startFailed(first))
        val second = state.reserveStart("s2", "p1", "42")!!
        assertFalse(state.started(first))
        assertTrue(state.started(second))
        assertEquals("s2", state.snapshot.active?.identity?.tripId)
    }

    @Test
    fun `confirmed stop and close invalidate callbacks`() {
        start()
        val identity = state.snapshot.active!!.identity
        state.reserveStop("stop")
        assertFalse(state.stopped("wrong", identity))
        assertTrue(state.stopped("stop", identity))
        assertTrue(state.beginPlan(request.copy(id = "p2")))
        state.close()
        assertFalse(state.routes("p2", listOf(option)))
        assertFalse(state.beginPlan(request.copy(id = "p3")))
        assertFalse(state.arrived(identity))
    }

    @Test
    fun `leaving an unconfirmed stop retires the trip and allows a new plan`() {
        start()
        val identity = state.snapshot.active!!.identity
        state.beginSearch("search")
        state.reserveStop("stop")
        assertTrue(state.cancelSearch())
        assertTrue(state.leaveTracking())
        assertNull(state.snapshot.active)
        assertEquals(trip, state.snapshot.lastTrip)
        assertFalse(state.arrived(identity))
        assertTrue(state.beginPlan(request.copy(id = "another-plan")))
    }

    @Test
    fun `timed out plan ignores late success and leaves room to retry`() {
        assertTrue(state.beginPlan(request))
        assertTrue(state.planFailed("p1"))
        assertFalse(state.routes("p1", listOf(option)))
        assertNull(state.snapshot.plan)
        assertTrue(state.beginPlan(request.copy(id = "retry")))
    }

    @Test
    fun `invalid provider measurements are exposed as unknown`() {
        state.beginPlan(request)
        state.routes("p1", listOf(MapRoute("42", -1, -1, Double.NaN)))
        assertEquals(MapRoute("42", null, null, null), state.snapshot.plan?.routes?.single())
        val selection = state.reserveStart("trip", "p1", "42")!!
        state.started(selection)
        val identity = state.snapshot.active!!.identity
        assertTrue(state.progress(MapProgress(identity, now, -1, -1)))
        assertNull(state.freshProgress()?.meters)
        assertNull(state.freshProgress()?.seconds)
    }

    @Test
    fun `read-only estimates cannot be started even with matching route ID`() {
        assertTrue(state.beginPlan(request, selectable = false))
        assertTrue(state.routes("p1", listOf(option)))
        assertNull(state.reserveStart("trip", "p1", "42"))
    }

    @Test
    fun `explicit layer commands keep the requested state when repeated`() {
        assertTrue(MapViewAction.TRAFFIC_ON.targetLayer(MapViewAction.TRAFFIC_ON.targetLayer(false)))
        assertFalse(MapViewAction.TRAFFIC_OFF.targetLayer(MapViewAction.TRAFFIC_OFF.targetLayer(true)))
        assertTrue(MapViewAction.SATELLITE_ON.targetLayer(MapViewAction.SATELLITE_ON.targetLayer(false)))
        assertFalse(MapViewAction.SATELLITE_OFF.targetLayer(MapViewAction.SATELLITE_OFF.targetLayer(true)))
        assertFalse(MapViewAction.SATELLITE.targetLayer(MapViewAction.SATELLITE.targetLayer(false)))
    }

    private fun start() {
        assertTrue(state.beginPlan(request))
        assertTrue(state.routes("p1", listOf(option)))
        val selection = state.reserveStart("trip", "p1", "42")!!
        assertTrue(state.started(selection))
    }
}

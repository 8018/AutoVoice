package com.autovoice.app.navigation

import com.autovoice.voicebusiness.navigation.RoutePreference
import com.autovoice.voicebusiness.navigation.MapViewAction
import com.autovoice.voicebusiness.navigation.MapActiveTrip
import com.autovoice.voicebusiness.navigation.MapTripIdentity
import com.autovoice.voicebusiness.navigation.NavigationRouteSnapshot
import com.autovoice.voicebusiness.navigation.NavigationTarget
import com.autovoice.voicebusiness.navigation.NavigationTrip
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NavigationRouteVoiceCommandTest {
    @Test
    fun `spoken route preferences match complete commands`() {
        assertEquals(RouteVoiceCommand.Preference(RoutePreference.AVOID_HIGHWAYS), parseRouteVoiceCommand("不走高速"))
        assertEquals(RouteVoiceCommand.Preference(RoutePreference.AVOID_CONGESTION), parseRouteVoiceCommand("避开拥堵"))
        assertEquals(RouteVoiceCommand.Preference(RoutePreference.FASTEST), parseRouteVoiceCommand("最快路线"))
        assertEquals(RouteVoiceCommand.Preference(RoutePreference.FASTEST), parseRouteVoiceCommand("走最快的路线"))
    }

    @Test
    fun `confirmation prepares a route and negated phrases are ignored`() {
        assertEquals(RouteVoiceCommand.PrepareStart, parseRouteVoiceCommand("按此目的地开始"))
        assertNull(parseRouteVoiceCommand("不要按此目的地开始"))
        assertNull(parseRouteVoiceCommand("先别按这个目的地开始"))
        assertNull(parseRouteVoiceCommand("不用避开拥堵"))
        assertNull(parseRouteVoiceCommand("别走最快的路线"))
    }

    @Test
    fun `presentation checklist commands resolve to scoped controls`() {
        assertEquals(PresentationVoiceCommand.Mute(true), parsePresentationVoiceCommand("导航别说话"))
        assertEquals(PresentationVoiceCommand.Mute(false), parsePresentationVoiceCommand("恢复导航语音"))
        assertEquals(PresentationVoiceCommand.AdjustVolume(-10), parsePresentationVoiceCommand("导航声音小一点"))
        assertEquals(PresentationVoiceCommand.AdjustVolume(10), parsePresentationVoiceCommand("导航声音大一点"))
        assertEquals(PresentationVoiceCommand.Volume(40), parsePresentationVoiceCommand("导航音量调到百分之40"))
        val views = mapOf("放大地图" to MapViewAction.ZOOM_IN, "缩小地图" to MapViewAction.ZOOM_OUT,
            "看看全程" to MapViewAction.OVERVIEW, "显示路况" to MapViewAction.TRAFFIC_ON,
            "打开路况" to MapViewAction.TRAFFIC_ON, "关掉路况" to MapViewAction.TRAFFIC_OFF,
            "打开卫星图" to MapViewAction.SATELLITE_ON)
        for ((phrase, action) in views) {
            assertEquals(PresentationVoiceCommand.View(action), parsePresentationVoiceCommand(phrase))
        }
        assertEquals(PresentationVoiceCommand.ClearHistory, parsePresentationVoiceCommand("清除导航搜索记录"))
    }

    @Test
    fun `negative presentation commands and generic media volume are excluded`() {
        for (phrase in listOf("不要打开路况", "别关掉路况", "先别打开卫星图",
            "不要清除导航搜索记录", "不要把导航声音调大", "媒体声音小一点")) {
            assertNull(parsePresentationVoiceCommand(phrase), phrase)
        }
    }

    @Test
    fun `a queued control loses authority after the trip or route is replaced`() {
        val identity = MapTripIdentity("trip", 1, "path")
        val active = MapActiveTrip(identity, NavigationTrip(NavigationTarget("机场", 30.3, 104.4)))
        val state = NavigationRouteSnapshot(active = active)
        assertTrue(acceptsNavigationControl(state, identity))
        assertFalse(acceptsNavigationControl(state.copy(stopping = "stop"), identity))
        assertFalse(acceptsNavigationControl(state.copy(active = null), identity))
        assertFalse(acceptsNavigationControl(state.copy(
            active = active.copy(identity = identity.copy(tripId = "replacement")),
        ), identity))
        assertFalse(acceptsNavigationControl(state.copy(
            active = active.copy(identity = identity.copy(revision = 2, pathId = "reroute")),
        ), identity))
    }
}

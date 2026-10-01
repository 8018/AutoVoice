package com.autovoice.voicebusiness.navigation

import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.SlotValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Domain behavior runs without Android, a gateway, or an App ViewModel. */
class NavigationBusinessBoundaryTest {
    @Test
    fun `single destination is opened and handoff is recorded`() {
        val opened = mutableListOf<String>()
        val executor = NavigationExecutor(opener = { uri -> opened += uri; true })

        assertTrue(executor.execute(navigate("成都天府国际机场", 30.31, 104.44), "turn-1"))
        assertEquals(NavigationHandoff.ACCEPTED, executor.session.snapshot.handoff)
        assertTrue(opened.single().startsWith("androidamap://navi?"))
        assertTrue(opened.single().contains("lat=30.31"))
        assertTrue(opened.single().contains("lon=104.44"))
    }

    @Test
    fun `multi-stop route opens planning page without starting guidance`() {
        val opened = mutableListOf<String>()
        val executor = NavigationExecutor(opener = { uri -> opened += uri; true })
        val request = navigate("成都天府国际机场", 30.31, 104.44).copy(
            slots = navigate("成都天府国际机场", 30.31, 104.44).slots +
                (NavigationExecutor.SLOT_WAYPOINTS to SlotValue.StringValue(
                    """[{"poiname":"春熙路","lat":30.66,"lon":104.08}]""",
                )),
        )

        assertTrue(executor.execute(request, "turn-2"))
        assertTrue(opened.single().startsWith("amapuri://route/plan?"))
        assertTrue(opened.single().contains("vian="))
    }

    @Test
    fun `selection is claimed only once and an old task cannot reopen`() {
        val session = NavigationSession()
        val candidate = NavigationExecutor.NavigationCandidate("机场", 30.3, 104.4, candidateId = "a")
        session.offer(listOf(candidate), selectionId = "selection-1", originTurnId = "turn-1")
        val old = session.activeIdentity()!!

        assertEquals(candidate, session.claimSelection("selection-1", "a", old))
        assertEquals(null, session.claimSelection("selection-1", "a", old))
        session.offer(listOf(candidate), selectionId = "selection-2", originTurnId = "turn-2")
        assertFalse(session.abortSelection(expected = old))
        assertEquals("selection-2", session.snapshot.selectionId)
    }

    private fun navigate(name: String, lat: Double, lon: Double) = Intent(
        schemaVersion = "1.0",
        domain = NavigationExecutor.DOMAIN_NAVIGATION,
        intent = NavigationExecutor.INTENT_NAVIGATE,
        slots = mapOf(
            NavigationExecutor.SLOT_POINAME to SlotValue.StringValue(name),
            NavigationExecutor.SLOT_LAT to SlotValue.Number(lat),
            NavigationExecutor.SLOT_LON to SlotValue.Number(lon),
        ),
        confidence = 1.0,
        source = "test",
    )
}

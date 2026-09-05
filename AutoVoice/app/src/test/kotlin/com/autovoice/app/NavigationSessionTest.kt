package com.autovoice.app

import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.SlotValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NavigationSessionTest {
    @Test fun `shared airport selection produces exact amap destination URI`() {
        val fixture = javaClass.getResourceAsStream("/navigation-selection-scenario.json")!!.bufferedReader().use {
            com.google.gson.JsonParser.parseReader(it).asJsonObject
        }
        val listId = fixture["selectionId"].asString
        val candidates = fixture["candidates"].asJsonArray
        val selected = candidates.single { it.asJsonObject["candidateId"].asString == fixture["expectedCandidateId"].asString }.asJsonObject
        val uris = mutableListOf<String>()
        val executor = NavigationExecutor { uris.add(it); true }
        val offer = choose(candidates.toString())
        assertTrue(executor.execute(offer.copy(slots = offer.slots + ("selectionId" to SlotValue.StringValue(listId)))))
        assertTrue(uris.isEmpty())
        assertTrue(executor.execute(intent("navigate", mapOf(
            "selectionId" to SlotValue.StringValue(listId),
            "candidateId" to SlotValue.StringValue(selected["candidateId"].asString),
            "poiname" to SlotValue.StringValue(selected["poiname"].asString),
            "lat" to SlotValue.Number(selected["lat"].asDouble),
            "lon" to SlotValue.Number(selected["lon"].asDouble),
        ))))
        assertEquals(listOf(fixture["expectedUri"].asString), uris)
        assertTrue(executor.session.snapshot.candidates.isEmpty())
    }
    @Test fun `only the displayed candidate identity can launch exactly once`() {
        var launches = 0
        val executor = NavigationExecutor { launches++; true }
        val offered = choose("""[{"candidateId":"airport-1","poiname":"机场","lat":30.3,"lon":104.4}]""")
            .let { it.copy(slots = it.slots + ("selectionId" to SlotValue.StringValue("list-new"))) }
        assertTrue(executor.execute(offered))
        fun selection(list: String, candidate: String) = navigate().let { it.copy(slots = it.slots + mapOf(
            "selectionId" to SlotValue.StringValue(list), "candidateId" to SlotValue.StringValue(candidate))) }
        assertFalse(executor.execute(selection("list-old", "airport-1")))
        assertFalse(executor.execute(selection("list-new", "airport-2")))
        assertFalse(executor.execute(navigate()))
        val tampered = selection("list-new", "airport-1").let {
            it.copy(slots = it.slots + ("lat" to SlotValue.Number(30.0)))
        }
        assertFalse(executor.execute(tampered))
        assertTrue(executor.execute(selection("list-new", "airport-1")))
        assertFalse(executor.execute(selection("list-new", "airport-1")))
        assertEquals(1, launches)
    }

    @Test fun `expired selection rejects delayed execution`() {
        val executor = NavigationExecutor { fail("expired selection must not launch") }
        val offer = choose("""[{"candidateId":"a","poiname":"机场","lat":30.3,"lon":104.4}]""")
        executor.execute(offer.copy(slots = offer.slots + ("selectionId" to SlotValue.StringValue("s"))))
        executor.session.expire(executor.session.snapshot.candidateVersion)
        val action = navigate()
        assertFalse(executor.execute(action.copy(slots = action.slots + mapOf(
            "selectionId" to SlotValue.StringValue("s"), "candidateId" to SlotValue.StringValue("a")))))
    }
    private fun intent(name: String, slots: Map<String, SlotValue> = emptyMap()) = Intent(
        schemaVersion = "1.0", domain = "navigation", intent = name, slots = slots,
        confidence = 1.0, source = "test",
    )
    private fun choose(json: String = """[{"poiname":"机场","lat":30.3,"lon":104.4}]""") =
        intent("choose_destination", mapOf("candidates" to SlotValue.StringValue(json)))
    private fun navigate(waypoints: String? = null) = intent("navigate", buildMap {
        put("poiname", SlotValue.StringValue("机场"))
        put("lat", SlotValue.Number(30.3))
        put("lon", SlotValue.Number(104.4))
        if (waypoints != null) put("waypoints", SlotValue.StringValue(waypoints))
    })

    @Test fun `expired old selection cannot dismiss replacement`() {
        val executor = NavigationExecutor { true }
        executor.execute(choose())
        val old = executor.session.snapshot.candidateVersion
        executor.execute(choose())
        executor.session.expire(old)
        assertEquals(1, executor.session.snapshot.candidates.size)
        executor.session.expire(executor.session.snapshot.candidateVersion)
        assertTrue(executor.session.snapshot.candidates.isEmpty())
    }

    @Test fun `candidates never launch and cancellation never launches`() {
        var opened = false
        val executor = NavigationExecutor { opened = true; true }
        assertTrue(executor.execute(choose()))
        assertTrue(executor.execute(intent("cancel_navigation")))
        assertFalse(opened)
        assertTrue(executor.session.snapshot.candidates.isEmpty())
    }

    @Test fun `confirmation clears list before launch and tracks ordered stops`() {
        val session = NavigationSession()
        val executor = NavigationExecutor(session = session) {
            assertEquals(NavigationHandoff.OPENING, session.snapshot.handoff)
            assertTrue(session.snapshot.candidates.isEmpty())
            true
        }
        executor.execute(choose())
        assertTrue(executor.execute(navigate("""[{"poiname":"A","lat":30.5,"lon":104.0},{"poiname":"B","lat":30.4,"lon":104.1}]""")))
        assertEquals(listOf("A", "B"), session.snapshot.trip!!.waypoints.map { it.name })
        assertEquals("机场", session.snapshot.trip!!.destination.name)
        assertEquals(NavigationHandoff.ACCEPTED, session.snapshot.handoff)
        assertFalse(executor.execute(intent("cancel_navigation")))
        assertEquals(NavigationHandoff.ACCEPTED, session.snapshot.handoff)
    }

    @Test fun `launch exception records failed handoff instead of throwing`() {
        val executor = NavigationExecutor { throw IllegalStateException("No Activity") }
        assertFalse(executor.execute(navigate()))
        assertEquals(NavigationHandoff.FAILED, executor.session.snapshot.handoff)
    }

    @Test fun `malformed candidate list is rejected without renumbering or losing previous list`() {
        val executor = NavigationExecutor { fail("must not launch") }
        executor.execute(choose())
        val previous = executor.session.snapshot
        listOf("null", "[]", "{}", "[null]", """[{"poiname":"bad","lat":999,"lon":104}]""",
            """[{"poiname":"bad","lon":104},{"poiname":"good","lat":30,"lon":104}]""").forEach {
            assertFalse(executor.execute(choose(it)))
            assertEquals(previous, executor.session.snapshot)
        }
        assertFalse(executor.execute(navigate("""[{"poiname":"bad","lon":104}]""")))
        assertEquals(previous, executor.session.snapshot)
    }

    @Test fun `navigation state survives executor recreation with same owner`() {
        val session = NavigationSession()
        NavigationExecutor(session = session) { true }.execute(navigate())
        val second = NavigationExecutor(session = session) { true }
        assertEquals(NavigationHandoff.ACCEPTED, second.session.snapshot.handoff)
        second.execute(choose())
        assertTrue(second.execute(intent("cancel_navigation")))
        assertEquals(NavigationHandoff.ACCEPTED, second.session.snapshot.handoff)
    }

    @Test fun `vehicle context leaves unknown fields empty and accepts test injection`() {
        assertNull(VehicleContext().position)
        assertNull(VehicleContext().socPercent)
        val provider = VehicleContextProvider { VehicleContext(VehiclePosition(30.6, 104.0), 20.0) }
        assertEquals(20.0, provider.snapshot().socPercent)
        assertThrows(IllegalArgumentException::class.java) { VehiclePosition(Double.NaN, 104.0) }
        assertThrows(IllegalArgumentException::class.java) { VehicleContext(socPercent = 101.0) }
    }
}

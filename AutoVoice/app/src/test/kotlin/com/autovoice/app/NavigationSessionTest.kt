package com.autovoice.app

import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.SlotValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NavigationSessionTest {
    @Test fun `modern selection requires identity and atomic claim checks expected task`() {
        var launches = 0
        val executor = NavigationExecutor { launches++; true }
        val offer = choose("""[{"candidateId":"a","poiname":"机场","lat":30.3,"lon":104.4}]""")
            .let { it.copy(slots = it.slots + mapOf("selectionId" to SlotValue.StringValue("s"),
                "taskDialogVersion" to SlotValue.Number(1.0))) }
        assertTrue(executor.execute(offer))
        val old = executor.session.activeIdentity()!!
        val selection = navigate().let { it.copy(slots = it.slots + mapOf(
            "selectionId" to SlotValue.StringValue("s"), "candidateId" to SlotValue.StringValue("a"))) }
        assertFalse(executor.execute(selection))
        assertTrue(executor.execute(offer))
        assertNull(executor.session.claimSelection("s", "a", old))
        assertFalse(executor.session.cancelSelection("s", old))
        assertFalse(executor.session.abortSelection(expected = old))
        assertTrue(executor.execute(selection.copy(slots = selection.slots + identity(executor.session, "select"))))
        assertEquals(1, launches)
        assertFalse(executor.execute(navigate().let { it.copy(slots = it.slots +
            ("navigationOperation" to SlotValue.StringValue("select"))) }))
    }

    @Test fun `late handoff result cannot complete replacement task`() {
        val session = NavigationSession()
        val candidates = listOf(NavigationExecutor.NavigationCandidate("airport", 30.0, 104.0, candidateId = "a"))
        session.offer(candidates, "s", "t")
        session.claimSelection("s", "a")
        val token = session.beginHandoff(NavigationTrip(NavigationTarget("airport", 30.0, 104.0)))
        session.offer(candidates, "new", "t2")
        session.finishHandoff(token, true)
        assertEquals("new", session.snapshot.selectionId)
        assertEquals(com.autovoice.voicecore.dialog.TaskStatus.WAITING_INPUT, session.snapshot.taskStatus)
    }

    private fun identity(session: NavigationSession, operation: String) = session.snapshot.let {
        mapOf("navigationOperation" to SlotValue.StringValue(operation),
            "taskId" to SlotValue.StringValue(it.taskId!!),
            "taskRevision" to SlotValue.Number(it.candidateVersion.toDouble()),
            "interactionId" to SlotValue.StringValue(it.interactionId!!))
    }

    @Test fun `explicit fresh navigation replaces a modern list but stale result cannot replace new task`() {
        val launched = mutableListOf<String>()
        val executor = NavigationExecutor { launched += it; true }
        val offered = choose("""[{"candidateId":"a","poiname":"机场","lat":30.3,"lon":104.4}]""").let { it.copy(slots = it.slots + mapOf(
            "selectionId" to SlotValue.StringValue("list"), "taskDialogVersion" to SlotValue.Number(1.0))) }
        executor.execute(offered)
        val old = identity(executor.session, "start_new")
        executor.execute(offered)
        assertFalse(executor.execute(navigate().let { it.copy(slots = it.slots + old) }))
        assertFalse(executor.execute(navigate()))
        val action = navigate().let { it.copy(slots = it.slots + identity(executor.session, "start_new")) }
        assertTrue(executor.execute(action))
        assertTrue(executor.session.snapshot.candidates.isEmpty())
        assertEquals(1, launched.size)
        assertFalse(executor.execute(action))
    }

    @Test fun `dormant clears waiting task but connection loss cannot revoke claimed handoff`() {
        val session = NavigationSession(interactionIdProvider = { "i" })
        val candidates = listOf(NavigationExecutor.NavigationCandidate("airport", 30.0, 104.0, candidateId = "a"))
        session.offer(candidates, "s", "t")
        session.onDialogueState(com.autovoice.voicecore.dialog.DialogueSnapshot())
        assertTrue(session.snapshot.candidates.isEmpty())
        session.offer(candidates, "s2", "t2")
        assertNotNull(session.claimSelection("s2", "a"))
        assertFalse(session.abortSelection())
        session.onDialogueState(com.autovoice.voicecore.dialog.DialogueSnapshot())
        assertEquals(com.autovoice.voicecore.dialog.TaskStatus.EXECUTING, session.snapshot.taskStatus)
    }

    @Test fun `missing context only ends matching list and late error preserves replacement`() {
        val session = NavigationSession()
        val candidates = listOf(NavigationExecutor.NavigationCandidate("airport", 30.0, 104.0, candidateId = "a"))
        session.offer(candidates, "s", "t")
        val old = session.snapshot.let { NavigationTaskContextRef(it.taskId!!, it.candidateVersion, it.interactionId!!, "s") }
        session.offer(candidates, "s2", "t2")
        assertFalse(session.contextMissing(old))
        val ref = session.snapshot.let { NavigationTaskContextRef(it.taskId!!, it.candidateVersion, it.interactionId!!, "s2") }
        val listener = NavigationContextListener { session.contextMissing(it) }
        val payload = com.google.gson.JsonObject().apply {
            addProperty("taskId", ref.taskId); addProperty("taskRevision", ref.revision)
            addProperty("interactionId", ref.interactionId); addProperty("selectionId", ref.selectionId)
            addProperty("status", "ACCEPTED")
        }
        listener.onMessage(com.autovoice.voicecore.GatewayMessage("navigation_context_result", payload))
        assertEquals("s2", session.snapshot.selectionId)
        payload.addProperty("status", "CONTEXT_MISSING")
        listener.onMessage(com.autovoice.voicecore.GatewayMessage("navigation_context_result", payload))
        assertTrue(session.snapshot.candidates.isEmpty())
    }

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

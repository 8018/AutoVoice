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
    fun `selecting a preview candidate never falls back to opening a map`() {
        val requested = mutableListOf<RoutePreference>()
        var opens = 0
        val manager = AppDialogueManager({ "i" }, {}, { opens++; true }, mapRequest = { _, id, _, preference ->
            if (preference != null) requested += preference
            NavigationActionResult(id, NavigationActionStatus.PENDING, "正在查询路线")
        })
        val command = Intent(
            schemaVersion = "1.0",
            domain = "navigation",
            intent = "choose_destination",
            slots = mapOf(
                "routePreviewPreference" to SlotValue.StringValue("LESS_TOLLS"),
                "selectionId" to SlotValue.StringValue("selection"),
                "candidates" to SlotValue.StringValue(
                    """[{"poiname":"机场","lat":30.31,"lon":104.44,"candidateId":"airport","matchSummary":"分类匹配"}]""",
                ),
            ),
            confidence = 1.0,
            source = "navigation.dialog",
        )
        assertTrue(manager.navigationExecutor.execute(command, "offer"))
        val candidate = manager.navigationSession.snapshot.candidates.single()
        assertEquals("分类匹配", candidate.matchSummary)
        val wrongPurpose = navigate("机场", 30.31, 104.44).let { action ->
            action.copy(slots = action.slots + mapOf(
                "selectionId" to SlotValue.StringValue("selection"),
                "candidateId" to SlotValue.StringValue("airport"),
            ))
        }
        assertEquals(NavigationActionError.STALE_TASK,
            manager.navigationExecutor.executeWithResult(wrongPurpose, "late").error)
        assertEquals(NavigationActionStatus.PENDING, manager.selectCandidate(candidate, "choice")?.status)
        assertEquals(listOf(RoutePreference.LESS_TOLLS), requested)
        assertEquals(0, opens)
        assertEquals(NavigationHandoff.NONE, manager.navigationSession.snapshot.handoff)
    }

    @Test
    fun `single destination is opened and handoff is recorded`() {
        val opened = mutableListOf<String>()
        val executor = NavigationExecutor(opener = { uri -> opened += uri; true })

        assertTrue(executor.execute(navigate("成都天府国际机场", 30.31, 104.44), "turn-1"))
        assertEquals(NavigationHandoff.ACCEPTED, executor.session.snapshot.handoff)
        assertTrue(opened.single().startsWith("androidamap://navi?"))
        assertTrue(opened.single().contains("lat=30.31"))
        assertTrue(opened.single().contains("lon=104.44"))
        assertTrue(opened.single().endsWith("&dev=0"))
    }

    @Test
    fun `legacy multi-stop reply awaits explicit confirmation before opening a map`() {
        val opened = mutableListOf<String>()
        val executor = NavigationExecutor(opener = { uri -> opened += uri; true })
        val request = navigate("成都天府国际机场", 30.31, 104.44).copy(
            slots = navigate("成都天府国际机场", 30.31, 104.44).slots +
                (NavigationExecutor.SLOT_WAYPOINTS to SlotValue.StringValue(
                    """[{"poiname":"春熙路","lat":30.66,"lon":104.08}]""",
                )),
        )

        assertTrue(executor.execute(request, "turn-2"))
        assertTrue(opened.isEmpty())
        assertEquals(NavigationHandoff.NONE, executor.session.snapshot.handoff)
        val revision = executor.session.snapshot.draft!!.revision
        assertEquals(NavigationActionStatus.ACCEPTED, executor.confirmDraft(revision)?.status)
        assertTrue(opened.single().startsWith("amapuri://route/plan?"))
        assertTrue(opened.single().contains("vian=1"))
        assertEquals(null, executor.confirmDraft(revision))
    }

    @Test
    fun `multi-stop candidate groups enter a draft and preserve the ambiguous middle stop`() {
        val opened = mutableListOf<String>()
        val executor = NavigationExecutor { uri -> opened += uri; true }
        val intent = draft("""
            [
              {"query":"学校","candidates":[{"poiname":"学校","lat":30.6,"lon":104.0}]},
              {"query":"超市","candidates":[{"poiname":"东门超市","lat":30.5,"lon":104.1},{"poiname":"西门超市","lat":30.4,"lon":104.2}]},
              {"query":"机场","candidates":[{"poiname":"机场","lat":30.3,"lon":104.4}]}
            ]
        """.trimIndent())

        val result = executor.executeWithResult(intent, "trip-turn")

        assertEquals(NavigationActionStatus.PENDING, result.status)
        val ambiguous = executor.session.snapshot
        assertEquals(listOf("东门超市", "西门超市"), ambiguous.candidates.map { it.poiname })
        val revision = ambiguous.draft!!.revision
        assertEquals(NavigationActionStatus.PENDING, executor.confirmDraft(revision)?.status)
        assertTrue(opened.isEmpty())
        val ready = executor.session.selectDraftStop(revision, ambiguous.candidates[1].candidateId)!!
        assertEquals(listOf("学校", "西门超市", "机场"), ready.stops.map { it.target?.name })
        assertEquals(null, executor.confirmDraft(revision))
        assertEquals(NavigationActionStatus.ACCEPTED, executor.confirmDraft(ready.revision)?.status)
        assertTrue(opened.single().contains("vialons=104.0|104.2"))
        assertTrue(opened.single().contains("dlon=104.4"))
    }

    @Test
    fun `missing middle stop invalidates prior draft and never opens a partial route`() {
        var opens = 0
        val executor = NavigationExecutor { opens++; true }
        val first = draft("""
            [{"query":"学校","candidates":[{"poiname":"学校","lat":30.6,"lon":104.0}]},
             {"query":"机场","candidates":[{"poiname":"机场","lat":30.3,"lon":104.4}]}]
        """.trimIndent())
        assertTrue(executor.execute(first, "previous-trip"))
        val revision = executor.session.snapshot.draft!!.revision

        val failure = executor.executeWithResult(draft("""
            [{"query":"学校","candidates":[{"poiname":"学校","lat":30.6,"lon":104.0}]},
             {"query":"超市","candidates":[]},
             {"query":"机场","candidates":[{"poiname":"机场","lat":30.3,"lon":104.4}]}]
        """.trimIndent()), "new-trip")

        assertEquals(NavigationActionStatus.FAILED, failure.status)
        assertTrue(failure.message.contains("第2站超市"))
        assertEquals(null, executor.confirmDraft(revision))
        assertEquals(0, opens)
    }

    @Test
    fun `home must be available locally and current at confirmation`() {
        var home: SavedNavigationPlace? = null
        var opens = 0
        val executor = NavigationExecutor(savedPlace = { home }) { opens++; true }
        val request = draft("""
            [{"query":"学校","candidates":[{"poiname":"学校","lat":30.6,"lon":104.0}]},
             {"query":"家","savedPlace":"home"}]
        """.trimIndent())

        assertTrue(executor.executeWithResult(request, "turn").message.contains("还没有设置家"))
        home = SavedNavigationPlace(NavigationTarget("家庭地址", 30.3, 104.4))
        assertTrue(executor.execute(request, "next-turn"))
        val draft = executor.session.snapshot.draft!!
        assertEquals(SavedPlaceKind.HOME.label, draft.stops.last().target?.name)
        home = null
        assertEquals(NavigationActionStatus.FAILED, executor.confirmDraft(draft.revision)?.status)
        assertEquals(0, opens)
    }

    @Test
    fun `gps home and work are staged with a map point in spoken order`() {
        val executor = NavigationExecutor(savedPlace = { kind ->
            SavedNavigationPlace(NavigationTarget(kind.label, 30.3, 104.4), isGpsCoordinate = true)
        }) { error("Unconfirmed trip opened") }

        val result = executor.executeWithResult(mixedDraft(), "mixed-turn")

        assertEquals(NavigationActionStatus.PENDING, result.status)
        assertEquals(listOf("家庭地址", "学校", "公司地址"), executor.session.snapshot.draft!!.stops.map { it.target?.name })
        assertEquals(NavigationHandoff.NONE, executor.session.snapshot.handoff)
    }

    @Test
    fun `confirmed mixed trip hands off converted stops exactly once`() {
        val fixture = MixedDraftFixture()
        val revision = fixture.stageAndConfirm()
        val original = fixture.requests.single().trip
        assertEquals(NavigationCoordinateSystem.WGS84, original.destination.coordinateSystem)
        assertEquals(listOf(NavigationCoordinateSystem.WGS84, NavigationCoordinateSystem.GCJ02),
            original.waypoints.map { it.coordinateSystem })
        assertEquals(null, fixture.executor.confirmDraft(revision))
        assertTrue(fixture.opened.isEmpty())

        val result = fixture.complete(revision)

        assertEquals(NavigationActionStatus.ACCEPTED, result?.status)
        assertEquals(NavigationHandoff.ACCEPTED, fixture.executor.session.snapshot.handoff)
        val uri = fixture.opened.single()
        assertTrue(uri.startsWith("amapuri://route/plan?"))
        assertTrue(uri.contains("&vian=2&vialons=104.405|104.0&vialats=30.302|30.6&"))
        assertTrue(uri.contains("&dlat=30.312&dlon=104.415&"))
        assertTrue(uri.endsWith("&dev=0"))
        assertEquals(null, fixture.complete(revision))
        assertEquals(1, fixture.opened.size)
    }

    @Test
    fun `normalization failures never open a partial route`() {
        for (failure in NavigationNormalizationResult.Failure.entries) {
            val fixture = MixedDraftFixture()
            val revision = fixture.stageAndConfirm()
            val result = fixture.executor.completeDraftNormalization(revision, failure)
            assertEquals(NavigationActionError.COORDINATE_CONVERSION_FAILED, result?.error)
            assertEquals(failure.message, result?.userFeedback)
            assertEquals(null, fixture.executor.session.snapshot.draft)
            assertEquals(null, fixture.complete(revision))
            assertTrue(fixture.opened.isEmpty())
        }
    }

    @Test
    fun `changed map point or wrong stop order is rejected`() {
        val fixture = MixedDraftFixture()
        val revision = fixture.stageAndConfirm()
        val changed = fixture.converted.copy(waypoints = fixture.converted.waypoints.reversed())
        val result = fixture.executor.completeDraftNormalization(revision, NavigationNormalizationResult.Converted(changed))

        assertEquals(NavigationActionError.COORDINATE_CONVERSION_FAILED, result?.error)
        assertTrue(fixture.opened.isEmpty())
    }

    @Test
    fun `cancelled normalization cannot open a map`() {
        val fixture = MixedDraftFixture()
        val revision = fixture.stageAndConfirm()
        assertTrue(fixture.executor.session.dismissDraft(revision))

        assertEquals(null, fixture.complete(revision))
        assertTrue(fixture.opened.isEmpty())
    }

    @Test
    fun `late normalization cannot launch or close a replacement draft`() {
        val fixture = MixedDraftFixture()
        val first = fixture.stageAndConfirm()
        val second = fixture.stageAndConfirm()

        assertEquals(null, fixture.complete(first))
        assertEquals(second, fixture.executor.session.snapshot.draft?.revision)
        assertTrue(fixture.opened.isEmpty())
        assertEquals(NavigationActionStatus.ACCEPTED, fixture.complete(second)?.status)
    }

    @Test
    fun `a saved address changed during normalization prevents handoff`() {
        val fixture = MixedDraftFixture()
        val revision = fixture.stageAndConfirm()
        fixture.places[SavedPlaceKind.HOME] = SavedNavigationPlace(NavigationTarget("更新地址", 30.51, 104.10), true)

        val result = fixture.complete(revision)

        assertEquals(NavigationActionStatus.FAILED, result?.status)
        assertTrue(result?.message.orEmpty().contains("家庭地址已更改"))
        assertTrue(fixture.opened.isEmpty())
    }

    @Test
    fun `embedded planning receives the converted trip without opening the external URI`() {
        val planned = mutableListOf<NavigationTrip>()
        val fixture = MixedDraftFixture { trip, id, gps, preference ->
            assertFalse(gps)
            assertEquals(null, preference)
            planned += trip
            NavigationActionResult(id, NavigationActionStatus.PENDING, "正在查询路线")
        }
        val revision = fixture.stageAndConfirm()

        assertTrue(planned.isEmpty())
        assertEquals(NavigationActionStatus.PENDING, fixture.complete(revision)?.status)
        assertEquals(listOf(fixture.converted), planned)
        assertTrue(fixture.opened.isEmpty())
        assertEquals(NavigationHandoff.NONE, fixture.executor.session.snapshot.handoff)
    }

    @Test
    fun `mode or transport reset aborts a confirmed mixed trip`() {
        val fixture = MixedDraftFixture()
        val revision = fixture.stageAndConfirm()
        assertTrue(fixture.executor.abortPendingTask())

        assertEquals(null, fixture.complete(revision))
        assertTrue(fixture.opened.isEmpty())
    }

    @Test
    fun `mixed preview sends the original preference to the map path without opening the URI`() {
        val requested = mutableListOf<Pair<NavigationTrip, RoutePreference?>>()
        val fixture = MixedDraftFixture { trip, id, gps, preference ->
            assertFalse(gps)
            requested += trip to preference
            NavigationActionResult(id, NavigationActionStatus.PENDING, "正在查询路线")
        }
        val revision = fixture.stageAndConfirm(RoutePreference.LESS_TOLLS)

        assertEquals(RoutePreference.LESS_TOLLS, fixture.requests.single().preference)
        assertEquals(NavigationActionStatus.PENDING, fixture.complete(revision)?.status)
        assertEquals(listOf(fixture.converted to RoutePreference.LESS_TOLLS), requested)
        assertTrue(fixture.opened.isEmpty())
    }

    @Test
    fun `synchronous callback cannot return a pending reply after final failure`() {
        lateinit var executor: NavigationExecutor
        var terminal: NavigationActionResult? = null
        executor = NavigationExecutor(
            savedPlace = { SavedNavigationPlace(NavigationTarget("地址", 30.3, 104.4), true) },
            normalizeTrip = { request ->
                terminal = executor.completeDraftNormalization(request.revision, NavigationNormalizationResult.Failure.MISSING_LOCATION)
            },
        ) { error("Failed draft opened") }
        assertTrue(executor.execute(mixedDraft(), "mixed-turn"))

        val confirmation = executor.confirmDraft(executor.session.snapshot.draft!!.revision)

        assertEquals(NavigationActionStatus.FAILED, terminal?.status)
        assertEquals(null, confirmation)
    }

    private inner class MixedDraftFixture(
        mapRequest: ((NavigationTrip, String, Boolean, RoutePreference?) -> NavigationActionResult?)? = null,
    ) {
        val opened = mutableListOf<String>()
        val requests = mutableListOf<NavigationNormalizationRequest>()
        val places = mutableMapOf(
            SavedPlaceKind.HOME to SavedNavigationPlace(NavigationTarget("地址", 30.30, 104.4), true),
            SavedPlaceKind.WORK to SavedNavigationPlace(NavigationTarget("地址", 30.31, 104.41), true),
        )
        val executor = NavigationExecutor(savedPlace = places::get, mapRequest = mapRequest, normalizeTrip = { requests += it }) {
            opened += it
            true
        }
        val converted = NavigationTrip(
            NavigationTarget("公司地址", 30.312, 104.415),
            listOf(NavigationTarget("家庭地址", 30.302, 104.405), NavigationTarget("学校", 30.6, 104.0)),
        )

        fun stageAndConfirm(preference: RoutePreference? = null): Long {
            val draft = mixedDraft().let { intent ->
                if (preference == null) intent
                else intent.copy(slots = intent.slots + ("routePreviewPreference" to SlotValue.StringValue(preference.name)))
            }
            assertTrue(executor.execute(draft, "mixed-${requests.size}"))
            val revision = executor.session.snapshot.draft!!.revision
            assertEquals(NavigationActionStatus.PENDING, executor.confirmDraft(revision)?.status)
            assertTrue(executor.session.snapshot.normalizingDraft)
            return revision
        }

        fun complete(revision: Long) = executor.completeDraftNormalization(
            revision, NavigationNormalizationResult.Converted(converted),
        )
    }

    private fun mixedDraft() = draft("""
        [{"query":"家","savedPlace":"home"},
         {"query":"学校","candidates":[{"poiname":"学校","lat":30.6,"lon":104.0}]},
         {"query":"公司","savedPlace":"work"}]
    """.trimIndent())

    @Test
    fun `dismissed route cannot be opened by an old confirmation`() {
        var opens = 0
        val executor = NavigationExecutor { opens++; true }
        val request = draft("""
            [{"query":"学校","candidates":[{"poiname":"学校","lat":30.6,"lon":104.0}]},
             {"query":"机场","candidates":[{"poiname":"机场","lat":30.3,"lon":104.4}]}]
        """.trimIndent())
        assertTrue(executor.execute(request, "trip-turn"))
        val revision = executor.session.snapshot.draft!!.revision

        assertTrue(executor.session.dismissDraft(revision))
        assertEquals(null, executor.confirmDraft(revision))
        assertEquals(0, opens)
    }

    private fun draft(stops: String) = Intent(
        schemaVersion = "1.0", domain = "navigation", intent = "draft_trip",
        slots = mapOf("stops" to SlotValue.StringValue(stops)), confidence = 1.0, source = "test",
    )

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

    @Test
    fun `cancel without a pending task cannot stop external guidance`() {
        var opens = 0
        val executor = NavigationExecutor { opens++; true }
        executor.execute(navigate("机场", 30.3, 104.4), "launch-turn")
        val cancel = navigate("机场", 30.3, 104.4).copy(intent = "cancel_navigation", slots = emptyMap())

        val result = executor.executeWithResult(cancel, "cancel-turn")

        assertEquals(NavigationActionStatus.UNSUPPORTED, result.status)
        assertTrue(result.userFeedback.orEmpty().contains("高德地图"))
        assertEquals(NavigationHandoff.ACCEPTED, executor.session.snapshot.handoff)
        assertEquals(1, opens)
    }

    @Test
    fun `uri capability does not include active route controls`() {
        val executor = NavigationExecutor { error("Unsupported operation must not launch a map") }
        val request = navigate("机场", 30.3, 104.4).copy(intent = "end_guidance", slots = emptyMap())

        assertFalse(executor.capabilities.supports("end_guidance"))
        assertEquals(NavigationActionStatus.UNSUPPORTED, executor.executeWithResult(request, "turn").status)
    }

    @Test
    fun `selection operation identity survives a replay after the task ends`() {
        var opens = 0
        val executor = NavigationExecutor { opens++; true }
        executor.session.offer(
            listOf(NavigationExecutor.NavigationCandidate("机场", 30.3, 104.4, candidateId = "candidate")),
            "selection",
            "search-turn",
            requiresTaskIdentity = true,
        )
        val task = executor.session.activeIdentity()!!
        val request = navigate("机场", 30.3, 104.4).let { it.copy(slots = it.slots + mapOf(
            "navigationOperation" to SlotValue.StringValue("select"),
            "taskId" to SlotValue.StringValue(task.taskId),
            "taskRevision" to SlotValue.Number(task.revision.toDouble()),
            "interactionId" to SlotValue.StringValue(task.interactionId),
            "selectionId" to SlotValue.StringValue("selection"),
            "candidateId" to SlotValue.StringValue("candidate"),
        )) }

        val first = executor.executeWithResult(request, "confirm-turn")
        val replay = executor.executeWithResult(request, "replayed-turn")

        assertEquals(NavigationActionStatus.ACCEPTED, first.status)
        assertEquals(first.operationId, replay.operationId)
        assertEquals(NavigationActionError.STALE_TASK, replay.error)
        assertEquals(null, replay.userFeedback)
        assertEquals(1, opens)
    }

    @Test
    fun `gps coordinates are rejected for route planning without conversion`() {
        var opens = 0
        val executor = NavigationExecutor { opens++; true }
        val request = navigate("机场", 30.3, 104.4).let { it.copy(slots = it.slots +
            ("waypoints" to SlotValue.StringValue("""[{"poiname":"春熙路","lat":30.66,"lon":104.08}]""")),
        ) }

        val result = executor.executeWithResult(request, "turn", isGpsCoordinate = true)

        assertEquals(NavigationActionError.INVALID_REQUEST, result.error)
        assertEquals(0, opens)
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

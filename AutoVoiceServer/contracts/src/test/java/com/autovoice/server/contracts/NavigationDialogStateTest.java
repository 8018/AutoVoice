package com.autovoice.server.contracts;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NavigationDialogStateTest {
    @org.junit.jupiter.api.Test
    void sharedAirportScenarioResolvesOrdinalAndNameToSameCoordinates() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var input = getClass().getResourceAsStream("/navigation-selection-scenario.json")) {
            var scenario = json.readTree(input);
            var expected = scenario.path("candidates").get(1);
            for (var answer : scenario.path("answers")) {
                var state = state();
                var offered = state.remember(CTX, chooseReply("机场", scenario.path("candidates").toString()));
                var id = offered.intent().slots().get("selectionId").value();
                var result = state.resolve(CTX.withAttr("navigationSelectionId", id), answer.asText()).orElseThrow();
                assertEquals(expected.path("poiname").asText(), result.intent().slots().get("poiname").value());
                assertEquals(expected.path("lat").asDouble(), result.intent().slots().get("lat").value());
                assertEquals(expected.path("lon").asDouble(), result.intent().slots().get("lon").value());
                var offeredCandidates = json.readTree((String) offered.intent().slots().get("candidates").value());
                assertEquals(offeredCandidates.get(1).path("candidateId").asText(), result.intent().slots().get("candidateId").value());
            }
        }
    }
    private static final SessionContext CTX = new SessionContext("session-1", "zh-CN", Map.of());
    private static final String CANDIDATES = """
            [{"poiname":"万达广场（东店）","lat":30.1,"lon":120.1,"address":"中山路1号"},
             {"poiname":"万达广场（西店）","lat":30.2,"lon":120.2,"address":"人民路8号"}]
            """;

    @Test
    void resolvesOrdinalWithoutCallingAModelAgain() {
        NavigationDialogState state = state();
        state.remember(CTX, chooseReply());

        Reply reply = state.resolve(CTX, "选第二个").orElseThrow();

        assertEquals("navigate", reply.intent().intent());
        assertEquals("万达广场（西店）", reply.intent().slots().get("poiname").value());
        assertEquals(30.2, reply.intent().slots().get("lat").value());
        assertFalse(state.hasPending(CTX));
    }

    @Test
    void resolvesClassifierStyleOrdinalWithoutDiPrefix() {
        NavigationDialogState state = state();
        state.remember(CTX, chooseReply());

        Reply reply = state.resolve(CTX, "一个。").orElseThrow();

        assertEquals("navigate", reply.intent().intent());
        assertEquals("万达广场（东店）", reply.intent().slots().get("poiname").value());
        assertFalse(state.hasPending(CTX));
    }

    @Test
    void resolvesUniqueAddressNameAndKeepsUnrelatedSpeechAvailable() {
        NavigationDialogState state = state();
        state.remember(CTX, chooseReply());

        assertTrue(state.resolve(CTX, "打开空调").isEmpty());
        assertTrue(state.hasPending(CTX));

        Reply reply = state.resolve(CTX, "人民路八号那个").orElseThrow();
        assertEquals("万达广场（西店）", reply.intent().slots().get("poiname").value());
    }

    @Test
    void rejectsOutOfRangeAndSupportsCancel() {
        NavigationDialogState state = state();
        state.remember(CTX, chooseReply());
        assertEquals("没有第3个，请重新选择", state.resolve(CTX, "第三个").orElseThrow().text());
        assertTrue(state.hasPending(CTX));
        Reply cancel = state.resolve(CTX, "算了").orElseThrow();
        assertEquals("cancel_navigation", cancel.intent().intent());
        assertEquals("已取消导航", cancel.speakText());
        assertFalse(state.hasPending(CTX));
    }

    @Test
    void newNavigationRequestClearsOldDialogAndFallsThroughForFreshSearch() {
        NavigationDialogState state = state();
        state.remember(CTX, chooseReply());

        assertTrue(state.resolve(CTX, "导航去万达广场").isEmpty());
        assertFalse(state.hasPending(CTX));
    }

    @Test
    void explicitUniqueCandidateNameStillSelectsCurrentDialog() {
        NavigationDialogState state = state();
        state.remember(CTX, chooseReply());

        Reply reply = state.resolve(CTX, "导航去万达广场西店").orElseThrow();

        assertEquals("navigate", reply.intent().intent());
        assertEquals("万达广场（西店）", reply.intent().slots().get("poiname").value());
    }

    @Test
    void exactAirportNameWinsOverTerminalNamesContainingIt() {
        NavigationDialogState state = state();
        String airports = """
                [{"poiname":"成都双流国际机场-T1航站楼","lat":30.57,"lon":103.95},
                 {"poiname":"成都双流国际机场-T2航站楼","lat":30.58,"lon":103.96},
                 {"poiname":"成都双流国际机场","lat":30.57,"lon":103.95}]
                """;
        state.remember(CTX, chooseReply("机场", airports));

        Reply reply = state.resolve(CTX, "成都双流国际机场。").orElseThrow();

        assertEquals("navigate", reply.intent().intent());
        assertEquals("成都双流国际机场", reply.intent().slots().get("poiname").value());
    }

    private static NavigationDialogState state() {
        return new NavigationDialogState(
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC), 120_000);
    }

    @org.junit.jupiter.api.Test
    void displayedListIdentitySurvivesSelectionAndRejectsReplacedList() {
        NavigationDialogState state = state();
        Reply first = state.remember(CTX, chooseReply());
        String firstId = (String) first.intent().slots().get("selectionId").value();
        Reply second = state.remember(CTX, chooseReply());
        String secondId = (String) second.intent().slots().get("selectionId").value();
        org.junit.jupiter.api.Assertions.assertNotEquals(firstId, secondId);
        Reply stale = state.resolve(CTX.withAttr("navigationSelectionId", firstId), "第一个").orElseThrow();
        assertEquals("text", stale.kind());
        org.junit.jupiter.api.Assertions.assertTrue(state.hasPending(CTX));
        Reply selected = state.resolve(CTX.withAttr("navigationSelectionId", secondId), "第一个").orElseThrow();
        assertEquals("navigate", selected.intent().intent());
        assertEquals(secondId, selected.intent().slots().get("selectionId").value());
        org.junit.jupiter.api.Assertions.assertFalse(((String) selected.intent().slots().get("candidateId").value()).isBlank());
        assertEquals("text", state.resolve(CTX.withAttr("navigationSelectionId", secondId), "第一个").orElseThrow().kind());
    }

    @org.junit.jupiter.api.Test
    void dismissedOrReconnectedSelectionCannotNavigate() {
        NavigationDialogState state = state();
        Reply offer = state.remember(CTX, chooseReply());
        String id = (String) offer.intent().slots().get("selectionId").value();
        assertEquals("text", state.resolve(CTX.withAttr("navigationSelectionId", ""), "第一个").orElseThrow().kind());
        SessionContext reconnected = new SessionContext("new-connection", "zh-CN", Map.of("navigationSelectionId", id));
        assertEquals("text", state.resolve(reconnected, "第二个").orElseThrow().kind());
        org.junit.jupiter.api.Assertions.assertTrue(state.hasPending(CTX));
    }

    private static Reply chooseReply() {
        return chooseReply("万达广场", CANDIDATES);
    }

    private static Reply chooseReply(String query, String candidates) {
        Intent intent = Intent.of("1.0", "navigation", "choose_destination", Map.of(
                "query", SlotValue.stringValue(query),
                "candidates", SlotValue.stringValue(candidates)
        ), 1.0, "test", null);
        return Reply.ofAction(intent, "请选择");
    }
}

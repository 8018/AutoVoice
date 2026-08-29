package com.autovoice.server.contracts;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NavigationDialogStateTest {
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

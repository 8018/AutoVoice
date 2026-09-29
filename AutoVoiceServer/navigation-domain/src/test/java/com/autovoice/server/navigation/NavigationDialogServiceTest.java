package com.autovoice.server.navigation;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.SlotValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NavigationDialogServiceTest {
    private static final SessionContext CTX = new SessionContext("session-1", "zh-CN", Map.of());
    private static final String CANDIDATES = """
            [{"poiname":"万达广场（东店）","lat":30.1,"lon":120.1,"address":"中山路1号"},
             {"poiname":"万达广场（西店）","lat":30.2,"lon":120.2,"address":"人民路8号"}]
            """;

    @Test
    void sharedAirportScenarioResolvesOrdinalAndNameToSameCoordinates() throws Exception {
        var json = new ObjectMapper();
        try (var input = getClass().getResourceAsStream("/navigation-selection-scenario.json")) {
            assertNotNull(input);
            var scenario = json.readTree(input);
            var expected = scenario.path("candidates").get(1);
            for (var answer : scenario.path("answers")) {
                var dialog = dialog();
                var offered = dialog.remember(CTX,
                        chooseReply("机场", scenario.path("candidates").toString()));
                var id = offered.intent().slots().get("selectionId").value();
                var result = dialog.resolve(
                        CTX.withAttr("navigationSelectionId", id), answer.asText()).orElseThrow();
                assertEquals(expected.path("poiname").asText(),
                        result.intent().slots().get("poiname").value());
                assertEquals(expected.path("lat").asDouble(),
                        result.intent().slots().get("lat").value());
                assertEquals(expected.path("lon").asDouble(),
                        result.intent().slots().get("lon").value());
                var shown = json.readTree(
                        (String) offered.intent().slots().get("candidates").value());
                assertEquals(shown.get(1).path("candidateId").asText(),
                        result.intent().slots().get("candidateId").value());
            }
        }
    }

    @Test
    void resolvesOrdinalAndClassifierStyleWithoutCallingModelAgain() {
        var dialog = dialog();
        Reply offer = dialog.remember(CTX, chooseReply());
        String id = (String) offer.intent().slots().get("selectionId").value();
        Reply second = dialog.resolve(modern(id), "选第二个").orElseThrow();
        assertEquals("万达广场（西店）", second.intent().slots().get("poiname").value());
        assertTrue(dialog.hasPending(CTX));

        offer = dialog.remember(CTX, chooseReply());
        id = (String) offer.intent().slots().get("selectionId").value();
        Reply first = dialog.resolve(modern(id), "一个。").orElseThrow();
        assertEquals("万达广场（东店）", first.intent().slots().get("poiname").value());
    }

    @Test
    void resolvesUniqueAddressAndKeepsUnrelatedSpeechAvailable() {
        var dialog = dialog();
        dialog.remember(CTX, chooseReply());
        assertTrue(dialog.resolve(CTX, "打开空调").isEmpty());
        assertTrue(dialog.hasPending(CTX));
        Reply reply = dialog.resolve(CTX, "人民路八号那个").orElseThrow();
        assertEquals("万达广场（西店）", reply.intent().slots().get("poiname").value());
    }

    @Test
    void rejectsOutOfRangeAndSupportsCancel() {
        var dialog = dialog();
        Reply offer = dialog.remember(CTX, chooseReply());
        String id = (String) offer.intent().slots().get("selectionId").value();
        SessionContext modern = modern(id);
        assertEquals("没有第3个，请重新选择", dialog.resolve(modern, "第三个").orElseThrow().text());
        assertTrue(dialog.hasPending(CTX));
        Reply cancel = dialog.resolve(modern, "算了").orElseThrow();
        assertEquals("cancel_navigation", cancel.intent().intent());
        assertEquals("已取消导航", cancel.speakText());
        assertTrue(dialog.hasPending(CTX));
        dialog.adopt(CTX, "");
        assertFalse(dialog.hasPending(CTX));
    }

    @Test
    void newNavigationRequestFallsThroughWithoutConsumingOldDialogBeforeAdoption() {
        var dialog = dialog();
        Reply offer = dialog.remember(CTX, chooseReply());
        String id = (String) offer.intent().slots().get("selectionId").value();
        assertTrue(dialog.resolve(modern(id), "导航去万达广场").isEmpty());
        assertTrue(dialog.hasPending(CTX));
    }

    @Test
    void exactNameWinsOverLongerNamesContainingIt() {
        var dialog = dialog();
        String airports = """
                [{"poiname":"成都双流国际机场-T1航站楼","lat":30.57,"lon":103.95},
                 {"poiname":"成都双流国际机场-T2航站楼","lat":30.58,"lon":103.96},
                 {"poiname":"成都双流国际机场","lat":30.57,"lon":103.95}]
                """;
        dialog.remember(CTX, chooseReply("机场", airports));
        Reply reply = dialog.resolve(CTX, "成都双流国际机场。").orElseThrow();
        assertEquals("成都双流国际机场", reply.intent().slots().get("poiname").value());
    }

    @Test
    void replacementRejectsStaleListAndSelectionProposalRemainsUntilClientClosesContext() {
        var dialog = dialog();
        Reply first = dialog.remember(CTX, chooseReply());
        String firstId = (String) first.intent().slots().get("selectionId").value();
        Reply second = dialog.remember(CTX, chooseReply());
        String secondId = (String) second.intent().slots().get("selectionId").value();
        assertNotEquals(firstId, secondId);
        assertEquals("text", dialog.resolve(
                modern(firstId), "第一个").orElseThrow().kind());
        assertTrue(dialog.hasPending(CTX));
        Reply selected = dialog.resolve(
                modern(secondId), "第一个").orElseThrow();
        assertEquals("navigate", selected.intent().intent());
        assertEquals(secondId, selected.intent().slots().get("selectionId").value());
        assertFalse(((String) selected.intent().slots().get("candidateId").value()).isBlank());
        assertEquals("navigate", dialog.resolve(
                modern(secondId), "第一个").orElseThrow().intent().intent());
        dialog.adopt(CTX, "");
        assertEquals("text", dialog.resolve(
                CTX.withAttr("navigationSelectionId", secondId), "第一个").orElseThrow().kind());
    }

    @Test
    void reconnectWithSameLogicalSessionKeepsListButAnotherSessionCannotUseIt() {
        var dialog = dialog();
        Reply offer = dialog.remember(CTX, chooseReply());
        String id = (String) offer.intent().slots().get("selectionId").value();
        SessionContext reconnected = new SessionContext(
                "session-1", "zh-CN", Map.of("navigationSelectionId", id, "connectionId", "new"));
        assertEquals("navigate", dialog.resolve(reconnected, "第二个").orElseThrow().intent().intent());

        offer = dialog.remember(CTX, chooseReply());
        id = (String) offer.intent().slots().get("selectionId").value();
        SessionContext other = new SessionContext(
                "session-2", "zh-CN", Map.of("navigationSelectionId", id));
        assertEquals("text", dialog.resolve(other, "第一个").orElseThrow().kind());
        assertTrue(dialog.hasPending(CTX));
    }

    @Test
    void modernDismissedListCannotNavigateButLegacyClientWithoutFieldStillCan() {
        var dialog = dialog();
        dialog.remember(CTX, chooseReply());
        assertEquals("text", dialog.resolve(
                CTX.withAttr("navigationSelectionId", ""), "第一个").orElseThrow().kind());
        assertTrue(dialog.hasPending(CTX));
        assertEquals("navigate", dialog.resolve(CTX, "第一个").orElseThrow().intent().intent());
    }

    @Test
    void expiredChoiceIsRemovedAndModernOrdinalGetsRecoveryMessage() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-27T00:00:00Z"));
        var dialog = new NavigationDialogService(clock, 1_000);
        Reply offer = dialog.remember(CTX, chooseReply());
        String id = (String) offer.intent().slots().get("selectionId").value();
        clock.advanceMillis(1_001);
        assertFalse(dialog.hasPending(CTX));
        Reply expired = dialog.resolve(
                CTX.withAttr("navigationSelectionId", id), "第一个").orElseThrow();
        assertEquals("地点选择已失效，请重新搜索", expired.text());
    }

    @Test
    void concurrentSelectionMayProduceDuplicateProposalsForClientDmToClaimOnce() throws Exception {
        var dialog = dialog();
        Reply offer = dialog.remember(CTX, chooseReply());
        String id = (String) offer.intent().slots().get("selectionId").value();
        SessionContext selected = modern(id);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return dialog.resolve(selected, "第一个").orElseThrow();
            });
            var second = executor.submit(() -> {
                start.await();
                return dialog.resolve(selected, "第一个").orElseThrow();
            });
            start.countDown();
            Reply a = first.get(2, TimeUnit.SECONDS);
            Reply b = second.get(2, TimeUnit.SECONDS);
            long actions = java.util.stream.Stream.of(a, b)
                    .filter(reply -> "action".equals(reply.kind()))
                    .filter(reply -> "navigate".equals(reply.intent().intent()))
                    .count();
            assertEquals(2, actions);
        }
    }

    @Test
    void invalidCandidatePayloadReturnsSafeTextAndStoresNothing() {
        var dialog = dialog();
        Reply reply = dialog.remember(CTX,
                chooseReply("bad", "[{\"poiname\":\"x\",\"lat\":91,\"lon\":120}]"));
        assertEquals("地点结果无效，请重新搜索", reply.text());
        assertFalse(dialog.hasPending(CTX));
    }

    // ---------- D05a:prepare/commit 分离 ----------

    @Test
    void prepareEnrichesButDoesNotWriteSharedState() {
        var dialog = dialog();
        Reply prepared = dialog.prepare(CTX, chooseReply());
        assertTrue(prepared.intent().slots().containsKey("selectionId"));
        assertFalse(dialog.hasPending(CTX), "prepare 不得写入共享候选状态");
    }

    @Test
    void commitWritesPreparedCandidatesAndKeepsSameIds() throws Exception {
        var dialog = dialog();
        Reply prepared = dialog.prepare(CTX, chooseReply());
        dialog.commit(CTX, prepared);
        assertTrue(dialog.hasPending(CTX), "commit 后候选应生效");
        // 选择解析必须与客户端所见列表(已下发回复)使用同一 candidateId/selectionId
        var id = prepared.intent().slots().get("selectionId").value();
        dialog.adopt(CTX, String.valueOf(id));
        Reply selected = dialog.resolve(CTX.withAttr("navigationSelectionId", id), "第一个")
                .orElseThrow();
        var shown = new ObjectMapper().readTree(
                (String) prepared.intent().slots().get("candidates").value());
        assertEquals(shown.get(0).path("candidateId").asText(),
                selected.intent().slots().get("candidateId").value());
    }

    @Test
    void commitIsIdempotentForSamePreparedReply() {
        var dialog = dialog();
        Reply prepared = dialog.prepare(CTX, chooseReply());
        dialog.commit(CTX, prepared);
        dialog.commit(CTX, prepared);
        assertTrue(dialog.hasPending(CTX));
        var id = prepared.intent().slots().get("selectionId").value();
        dialog.adopt(CTX, String.valueOf(id));
        assertTrue(dialog.resolve(CTX.withAttr("navigationSelectionId", id), "第一个").isPresent(),
                "重复提交不得破坏候选可用性");
    }

    @Test
    void unadoptedReplacementDoesNotOverwriteActiveList() {
        var dialog = dialog();
        Reply active = dialog.remember(CTX, chooseReply());
        String activeId = (String) active.intent().slots().get("selectionId").value();

        Reply next = dialog.prepare(CTX, chooseReply("另一个地点", CANDIDATES));
        String nextId = (String) next.intent().slots().get("selectionId").value();
        dialog.commit(CTX, next);

        Reply selectedOld = dialog.resolve(
                CTX.withAttr("navigationSelectionId", activeId), "第一个").orElseThrow();
        assertEquals("navigate", selectedOld.intent().intent(),
                "尚未显示的新列表不得覆盖当前可选择列表");

        dialog.commit(CTX, next);
        dialog.adopt(CTX, nextId);
        Reply selectedNext = dialog.resolve(
                CTX.withAttr("navigationSelectionId", nextId), "第一个").orElseThrow();
        assertEquals("navigate", selectedNext.intent().intent());
    }

    @Test
    void commitIgnoresUnpreparedOrForeignReplies() {
        var dialog = dialog();
        dialog.commit(CTX, Reply.ofText("普通回复"));
        dialog.commit(CTX, chooseReply()); // 未经 prepare,无 selectionId
        assertFalse(dialog.hasPending(CTX), "未携带准备标记的回复不得写入候选");
    }

    @Test
    void rememberKeepsCompositeSemanticsForCompatibility() {
        var dialog = dialog();
        Reply remembered = dialog.remember(CTX, chooseReply());
        assertTrue(remembered.intent().slots().containsKey("selectionId"));
        assertTrue(dialog.hasPending(CTX), "remember 兼容语义 = prepare + commit");
    }

    private static NavigationDialogService dialog() {
        return new NavigationDialogService(
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC), 120_000);
    }

    private static SessionContext modern(String selectionId) {
        return CTX.withAttr("navigationSelectionId", selectionId)
                .withAttr("taskDialogVersion", 1)
                .withAttr("navigationTaskId", "navigation-task-test");
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

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }
        private void advanceMillis(long millis) { instant = instant.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}

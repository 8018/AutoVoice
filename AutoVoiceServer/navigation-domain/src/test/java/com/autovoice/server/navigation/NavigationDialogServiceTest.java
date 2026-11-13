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
    void exactSavedPlaceCommandsSkipModelAndKeepCurrentSelectionIntact() {
        var dialog = dialog();
        dialog.remember(CTX, chooseReply());
        for (String command : java.util.List.of("回家", "带我回家", "去公司", "把这里设为家",
                "公司换到这里了", "删除我的家庭地址")) {
            Reply reply = dialog.complete(CTX, command, () -> fail("must not send saved place to POI tools")).join();
            assertEquals("text", reply.kind());
            assertTrue(dialog.hasPending(CTX));
        }
        assertEquals("regular path", dialog.complete(CTX, "找我家公司附近的停车场", () ->
                java.util.concurrent.CompletableFuture.completedFuture(Reply.ofText("regular path"))).join().text());
    }

    @Test
    void cityCorrectionUsesOriginalSearchAndReplacesSelectionIdentityOnlyAfterAdoption() throws Exception {
        var json = new ObjectMapper();
        var calls = new java.util.ArrayList<String>();
        var dialog = new NavigationDialogService(arguments -> {
            calls.add(arguments);
            return java.util.concurrent.CompletableFuture.completedFuture(searchReply("上海", """
                    [{"poiname":"上海万达广场","lat":31.2,"lon":121.4}]
                    """));
        });
        var initial = dialog.remember(CTX, searchReply("北京", CANDIDATES));
        String oldId = (String) initial.intent().slots().get("selectionId").value();
        var oldContext = modern(oldId).withAttr("latitude", 39.9).withAttr("longitude", 116.3);
        var replacement = dialog.complete(oldContext, "不是北京，是上海", () -> fail("unexpected model call")).join();
        assertEquals(1, calls.size());
        var request = json.readTree(calls.getFirst());
        assertEquals("上海", request.path("city").asText());
        assertEquals("万达广场", request.path("destinations").get(0).asText());
        String newId = (String) replacement.intent().slots().get("selectionId").value();
        assertNotEquals(oldId, newId);
        assertTrue(dialog.hasAdopted(CTX, oldId));
        dialog.commit(oldContext, replacement);
        assertTrue(dialog.adoptExact(CTX, newId));
        assertNull(dialog.resolve(oldContext, "第二个").orElseThrow().intent());
        assertEquals("上海万达广场", dialog.resolve(modern(newId), "第一个").orElseThrow()
                .intent().slots().get("poiname").value());
    }

    @Test
    void placeQualifiersPreserveChineseNumeralsDuringResearch() throws Exception {
        var requests = new java.util.ArrayList<String>();
        var dialog = new NavigationDialogService(arguments -> {
            requests.add(arguments);
            return java.util.concurrent.CompletableFuture.completedFuture(Reply.ofText("没有匹配地点。"));
        });
        var offered = dialog.remember(CTX, searchReply("北京", CANDIDATES));
        var context = modern((String) offered.intent().slots().get("selectionId").value());
        for (String qualifier : java.util.List.of("不是北京，是三亚", "三河市", "在二七区")) {
            dialog.complete(context, qualifier, () -> fail("unexpected model call")).join();
        }

        var json = new ObjectMapper();
        assertEquals(3, requests.size());
        assertEquals("三亚", json.readTree(requests.get(0)).path("city").asText());
        assertEquals("三河市", json.readTree(requests.get(1)).path("city").asText());
        assertEquals("二七区", json.readTree(requests.get(2)).path("district").asText());
    }

    @Test
    void nextBatchCarriesAllPreviouslyDisplayedCandidatesAndReportsExhaustion() throws Exception {
        var json = new ObjectMapper();
        var calls = new java.util.ArrayList<String>();
        var dialog = new NavigationDialogService(arguments -> {
            calls.add(arguments);
            return java.util.concurrent.CompletableFuture.completedFuture(calls.size() == 1
                    ? searchReply("杭州", """
                            [{"poiname":"万达广场（南店）","lat":30.3,"lon":120.3}]
                            """)
                    : Reply.ofText("没有找到更多候选。"));
        });
        var initial = dialog.remember(CTX, searchReply("杭州", CANDIDATES));
        var first = modern((String) initial.intent().slots().get("selectionId").value());
        var next = dialog.complete(first, "都不是，换一批", () -> fail("unexpected model call")).join();
        dialog.commit(first, next);
        var newId = (String) next.intent().slots().get("selectionId").value();
        assertTrue(dialog.adoptExact(first, newId));
        var exhausted = dialog.complete(modern(newId), "再换一批", () -> fail("unexpected model call")).join();
        assertEquals("没有找到更多候选。", exhausted.text());
        assertEquals(2, json.readTree(calls.get(0)).path("excluded").size());
        assertEquals(3, json.readTree(calls.get(1)).path("excluded").size());
    }

    @Test
    void retainedPlaceConditionsSurviveBatchAndCityCorrection() throws Exception {
        var json = new ObjectMapper();
        var calls = new java.util.ArrayList<String>();
        var dialog = new NavigationDialogService(arguments -> {
            calls.add(arguments);
            return java.util.concurrent.CompletableFuture.completedFuture(Reply.ofText("没有更多地点"));
        });
        var offer = chooseReply("咖啡厅", CANDIDATES);
        var intent = offer.intent();
        var initial = dialog.remember(CTX, Reply.ofAction(Intent.of("1.0", intent.domain(), intent.intent(),
                intent.slots(), 1.0, "navigation.resolve", """
                {"search":{"query":"咖啡厅","city":"杭州","constraints":[{"brand":"目标品牌","category":"咖啡厅"}]}}
                """), offer.speakText()));
        var context = modern((String) initial.intent().slots().get("selectionId").value());
        dialog.complete(context, "换一批", () -> fail("unexpected model call")).join();
        dialog.complete(context, "不是杭州，是上海", () -> fail("unexpected model call")).join();
        assertEquals(2, calls.size());
        for (String arguments : calls) {
            var constraints = json.readTree(arguments).path("constraints").path(0);
            assertEquals("目标品牌", constraints.path("brand").asText());
            assertEquals("咖啡厅", constraints.path("category").asText());
        }
        assertEquals("上海", json.readTree(calls.get(1)).path("city").asText());
    }

    @Test
    void lateBatchAfterCloseCannotProduceReplacementAction() {
        var search = new java.util.concurrent.CompletableFuture<Reply>();
        var dialog = new NavigationDialogService(arguments -> search);
        var initial = dialog.remember(CTX, searchReply("杭州", CANDIDATES));
        String oldId = (String) initial.intent().slots().get("selectionId").value();
        var context = modern(oldId);
        var future = dialog.complete(context, "换一批", () -> fail("unexpected model call"));
        dialog.closeExact(context, oldId);
        search.complete(searchReply("杭州", CANDIDATES));
        assertNull(future.join().intent());
        assertFalse(dialog.hasPending(context));
    }

    private static Reply searchReply(String city, String candidates) {
        var offer = chooseReply("万达广场", candidates);
        var intent = offer.intent();
        return Reply.ofAction(Intent.of("1.0", intent.domain(), intent.intent(), intent.slots(), 1.0,
                "navigation.resolve", "{\"search\":{\"query\":\"万达广场\",\"city\":\"" + city + "\"}}"), offer.speakText());
    }

    @Test
    void routeQuestionMarksCandidatesEvenWhenModelOmitsPreviewPreference() {
        var service = dialog();
        var offer = service.complete(CTX, "去机场要多久？", () ->
                java.util.concurrent.CompletableFuture.completedFuture(chooseReply())).join();
        assertEquals("DEFAULT", offer.intent().slots().get("routePreviewPreference").value());
    }

    @Test
    void routeQuestionRejectsDirectNavigationAndCurrentTripQuestionNeedsLiveEvidence() {
        var service = dialog();
        var navigate = Intent.of("1.0", "navigation", "navigate", Map.of(), 1, "model", null);
        var query = service.complete(CTX, "只算一下去机场的路线，不用导航", () ->
                java.util.concurrent.CompletableFuture.completedFuture(Reply.ofAction(navigate, "开始导航"))).join();
        assertNull(query.intent());
        assertFalse(query.text().contains("开始导航"));

        var current = service.complete(CTX, "还有多久到机场？", () ->
                fail("remaining trip questions cannot use departure planning")).join();
        assertNull(current.intent());
        assertTrue(current.text().contains("实时"));
    }

    @Test
    void routePreferenceSurvivesCityCorrectionEvenWhenSecondSearchOmitsIt() throws Exception {
        var requests = new java.util.ArrayList<String>();
        var service = new NavigationDialogService(args -> {
            requests.add(args);
            return java.util.concurrent.CompletableFuture.completedFuture(searchReply("上海", CANDIDATES));
        });
        var offer = service.complete(CTX, "预览去万达广场的路线，不走高速", () ->
                java.util.concurrent.CompletableFuture.completedFuture(searchReply("北京", CANDIDATES))).join();
        service.commit(CTX, offer);
        String selection = (String) offer.intent().slots().get("selectionId").value();
        service.adoptExact(CTX, selection);
        var replacement = service.complete(modern(selection), "不是北京，是上海", () ->
                fail("city correction must use the original lookup")).join();
        assertEquals("AVOID_HIGHWAYS", new ObjectMapper().readTree(requests.getFirst())
                .path("routePreviewPreference").asText());
        assertEquals("AVOID_HIGHWAYS", replacement.intent().slots().get("routePreviewPreference").value());
    }

    @Test
    void selectingDestinationForRouteEstimateDoesNotStartNavigation() {
        var service = dialog();
        var base = chooseReply("机场", CANDIDATES).intent();
        var slots = new java.util.LinkedHashMap<>(base.slots());
        slots.put("routePreviewPreference", SlotValue.stringValue("AVOID_HIGHWAYS"));
        var intent = Intent.of("1.0", base.domain(), base.intent(), slots, 1.0,
                "navigation.resolve", "{\"search\":{\"query\":\"机场\"}}");
        var offer = service.remember(CTX, Reply.ofAction(intent, "选目的地"));
        var context = modern((String) offer.intent().slots().get("selectionId").value());
        var reply = service.resolve(context, "第二个").orElseThrow();
        assertEquals("preview_routes", reply.intent().intent());
        assertEquals("AVOID_HIGHWAYS", reply.intent().slots().get("routePreviewPreference").value());
        assertFalse(reply.speakText().contains("开始导航"));
    }

    @Test
    void exhaustedBatchProvidesRecoveryWithoutPickingOldCandidate() {
        var dialog = dialog();
        var offered = dialog.remember(CTX, chooseReply());
        var context = modern((String) offered.intent().slots().get("selectionId").value());
        var reply = dialog.complete(context, "都不是，换一批", () -> fail("must not ask the model to choose")).join();
        assertNull(reply.intent());
        assertTrue(reply.text().contains("更多候选"));
    }

    @Test
    void negatedOrdinalDoesNotSelectOrCancel() {
        var dialog = dialog();
        var offered = dialog.remember(CTX, chooseReply());
        var context = modern((String) offered.intent().slots().get("selectionId").value());
        var reply = dialog.resolve(context, "不要去第二个").orElseThrow();
        assertNull(reply.intent());
        assertTrue(dialog.hasPending(context));
    }

    @Test
    void selectsUniqueDistrictDescription() {
        var dialog = dialog();
        var offered = dialog.remember(CTX, chooseReply("机场", """
                [{"poiname":"虹桥机场","address":"上海市闵行区","lat":31.2,"lon":121.3},
                 {"poiname":"浦东机场","address":"上海市浦东新区","lat":31.1,"lon":121.8}]
                """));
        var context = modern((String) offered.intent().slots().get("selectionId").value());
        assertEquals("浦东机场", dialog.resolve(context, "浦东的那个").orElseThrow()
                .intent().slots().get("poiname").value());
    }

    @Test
    void replacementWithNegationDoesNotCancelOrSelectOldList() {
        var dialog = dialog();
        var offered = dialog.remember(CTX, chooseReply());
        var context = modern((String) offered.intent().slots().get("selectionId").value());
        assertTrue(dialog.resolve(context, "不去了，去车站").isEmpty());
        assertTrue(dialog.hasPending(context));
    }

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
        assertEquals("已取消地点搜索", cancel.speakText());
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

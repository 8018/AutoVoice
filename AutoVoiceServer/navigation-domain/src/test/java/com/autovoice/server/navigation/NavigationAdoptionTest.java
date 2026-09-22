package com.autovoice.server.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** D05b 采用确认:客户端会话层显式采用/撤销候选列表,服务端据此决定语音选择是否激活。 */
class NavigationAdoptionTest {
    @Test
    void closingOldListPreservesPendingReplacementInEitherOrder() {
        for (boolean closeFirst : new boolean[]{true, false}) {
            SessionContext old = preparedContext();
            String oldId = (String) old.attrs().get("navigationSelectionId");
            assertTrue(dialog.adoptExact(old, oldId));
            SessionContext next = preparedContext();
            String nextId = (String) next.attrs().get("navigationSelectionId");
            if (closeFirst) dialog.closeExact(old, oldId);
            assertTrue(dialog.adoptExact(next, nextId));
            if (!closeFirst) dialog.closeExact(old, oldId);
            assertTrue(dialog.hasAdopted(next, nextId));
            assertTrue(dialog.adoptExact(next, nextId));
            org.junit.jupiter.api.Assertions.assertFalse(dialog.adoptExact(old, oldId));
            assertEquals("navigate", dialog.resolve(next, "第一个").orElseThrow().intent().intent());
        }
    }

    @Test
    void taskSelectionCarriesExactIdentityAndModelCannotForgeSelectionOperation() {
        SessionContext ctx = preparedContext().withAttr("taskDialogVersion", 1)
                .withAttr("navigationTaskId", "task-2").withAttr("navigationTaskRevision", 2L)
                .withAttr("navigationInteractionId", "interaction");
        assertTrue(dialog.adoptExact(ctx, (String) ctx.attrs().get("navigationSelectionId")));
        Reply selected = dialog.resolve(ctx, "第一个").orElseThrow();
        assertEquals("select", selected.intent().slots().get("navigationOperation").value());
        assertEquals("task-2", selected.intent().slots().get("taskId").value());
        assertEquals("interaction", selected.intent().slots().get("interactionId").value());
        assertEquals(2.0, selected.intent().slots().get("taskRevision").value());
        // Even the source string navigation.dialog is not authority for a model result.
        Reply fresh = dialog.prepareModel(ctx, selected);
        assertEquals("start_new", fresh.intent().slots().get("navigationOperation").value());
        org.junit.jupiter.api.Assertions.assertFalse(fresh.intent().slots().containsKey("candidateId"));
        org.junit.jupiter.api.Assertions.assertFalse(fresh.intent().slots().containsKey("selectionId"));
        assertEquals("task-2", fresh.intent().slots().get("taskId").value());
    }


    private static final String CANDIDATES = """
            [{"poiname":"机场","lat":30.1,"lon":120.1,"address":"机场路1号"}]
            """;

    private final NavigationDialogService dialog = new NavigationDialogService(
            Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC), 120_000);

    private SessionContext preparedContext() {
        Reply prepared = dialog.prepare(new SessionContext("s1", "zh-CN", Map.of()),
                Reply.ofAction(com.autovoice.server.contracts.Intent.of("1.0", "navigation",
                        "choose_destination",
                        Map.of("candidates", com.autovoice.server.contracts.SlotValue.stringValue(CANDIDATES)),
                        1.0, "llm", null), "请选择"));
        dialog.commit(new SessionContext("s1", "zh-CN", Map.of()), prepared);
        String selectionId = String.valueOf(prepared.intent().slots().get("selectionId").value());
        return new SessionContext("s1", "zh-CN",
                Map.of("navigationSelectionId", selectionId));
    }

    @Test
    void adoptionIsIdempotentAndKeepsSelectionActive() {
        SessionContext ctx = preparedContext();
        String selectionId = (String) ctx.attrs().get("navigationSelectionId");

        dialog.adopt(ctx, selectionId);
        dialog.adopt(ctx, selectionId); // 重复确认幂等

        Optional<Reply> selected = dialog.resolve(ctx, "第一个");
        assertTrue(selected.isPresent(), "已采用列表应保持可选");
        assertEquals("navigate", selected.get().intent().intent());
    }

    @Test
    void blankAdoptionDeactivatesListForModernClient() {
        SessionContext ctx = preparedContext();
        dialog.adopt(ctx, ""); // 客户端关闭/未显示列表

        Optional<Reply> selected = dialog.resolve(ctx, "第一个");
        assertTrue(selected.isPresent(), "未采用的列表不得被序号激活(现代客户端)");
        assertEquals("地点选择已失效，请重新搜索", selected.get().text());
    }

    @Test
    void dismissedListCannotBeReactivatedByLateConfirmation() {
        SessionContext ctx = preparedContext();
        String selectionId = (String) ctx.attrs().get("navigationSelectionId");
        dialog.adopt(ctx, "");
        dialog.adopt(ctx, selectionId);

        Reply result = dialog.resolve(ctx, "第一个").orElseThrow();
        assertEquals("地点选择已失效，请重新搜索", result.text());
    }

    @Test
    void staleAdoptionForForeignSelectionIsIgnored() {
        SessionContext ctx = preparedContext();
        dialog.adopt(ctx, "some-other-selection");

        Reply result = dialog.resolve(ctx, "第一个").orElseThrow();
        assertEquals("地点选择已失效，请重新搜索", result.text());
    }

    @Test
    void legacyClientWithoutAdoptionKeepsDefaultActiveBehavior() {
        SessionContext ctx = preparedContext();
        // 旧客户端不发 adopt 字段:提交后的候选仍可兼容使用。
        assertTrue(dialog.resolve(ctx, "第一个").isPresent());
    }
}

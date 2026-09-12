package com.autovoice.server.actionledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autovoice.server.contracts.ActionPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** D07a 独立 SQLite 业务账本:持久化动作身份、幂等登记、审计查询。 */
class SqliteActionLedgerTest {

    @TempDir
    Path dir;

    private ActionPlan plan(String actionId) {
        return new ActionPlan(actionId, "sess-1", "turn-1", "navigation", "navigate",
                "导航到机场", 1234L);
    }

    @Test
    void recordDispatchPersistsAndIsIdempotent() throws Exception {
        Path db = dir.resolve("ledger.db");
        try (SqliteActionLedger ledger = new SqliteActionLedger(db.toString())) {
            assertTrue(ledger.recordDispatch(plan("a1")), "首次登记应为新写入");
            assertFalse(ledger.recordDispatch(plan("a1")), "同 actionId 重复登记应幂等");
            var found = ledger.findByActionId("a1").orElseThrow();
            assertEquals("sess-1", found.sessionId());
            assertEquals("navigate", found.intent());
            assertTrue(ledger.findByActionId("missing").isEmpty());
        }
        // 持久化:重开库仍可查
        try (SqliteActionLedger ledger = new SqliteActionLedger(db.toString())) {
            assertTrue(ledger.findByActionId("a1").isPresent(), "动作身份必须落盘,重启后仍可查");
        }
    }

    @Test
    void auditRecordsIssuanceIndependentlyOfLatestState() throws Exception {
        Path db = dir.resolve("ledger-audit.db");
        try (SqliteActionLedger ledger = new SqliteActionLedger(db.toString())) {
            ActionPlan plan = plan("audit-1");
            ledger.recordDispatch(plan);
            ledger.recordDispatch(plan); // 重复签发不重复审计(幂等)

            assertEquals(1, ledger.auditCount("audit-1"),
                    "签发事件应独立留档,重复签发不重复记录");
        }
    }

    @Test
    void supportWindowIsPersistedAndQueryable() throws Exception {
        Path db = dir.resolve("ledger-window.db");
        long now = 1_000_000L;
        var clock = new com.autovoice.server.contracts.testing.TestClock(now);
        try (SqliteActionLedger ledger = new SqliteActionLedger(db.toString(), clock)) {
            ActionPlan plan = new ActionPlan("win-1", "s", "u", "navigation", "navigate",
                    "summary", now, now + 60_000);
            ledger.recordDispatch(plan);

            ActionPlan loaded = ledger.findByActionId("win-1").orElseThrow();
            assertEquals(now + 60_000, loaded.expiresAtMs(), "支持窗口必须落盘(重启后仍可判定)");
            assertTrue(loaded.withinSupportWindow(now + 59_999));
            assertFalse(loaded.withinSupportWindow(now + 60_001),
                    "超过支持窗口的请求必须可被判定为过期(不得当新动作执行)");
        }
    }

    @Test
    void separateLedgerFileFromTelemetry() {
        // 独立业务库:调用方指定独立路径,不复用遥测库文件
        Path db = dir.resolve("action-business.db");
        try (SqliteActionLedger ledger = new SqliteActionLedger(db.toString())) {
            ledger.recordDispatch(plan("a2"));
        }
        assertTrue(Files.exists(db), "独立业务库文件应存在");
    }
}

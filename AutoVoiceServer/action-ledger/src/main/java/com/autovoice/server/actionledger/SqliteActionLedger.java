package com.autovoice.server.actionledger;

import com.autovoice.server.contracts.ActionLedger;
import com.autovoice.server.contracts.ActionPlan;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * D07a 独立 SQLite 业务账本(独立库文件,不与遥测库混用;使用 SQLite 默认日志模式,
 * 不自研 WAL)。首期:签发登记(持久化动作身份)与内部审计查询;不开放客户端查询接口。
 */
public final class SqliteActionLedger implements ActionLedger {

    private final String dbPath;
    private final java.util.function.LongSupplier clock;

    public SqliteActionLedger(String dbPath) {
        this(dbPath, System::currentTimeMillis);
    }

    public SqliteActionLedger(String dbPath, java.util.function.LongSupplier clock) {
        this.clock = clock;
        this.dbPath = dbPath == null || dbPath.isBlank() ? "./action-ledger.db" : dbPath;
        try (Connection c = connect()) {
            c.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS action_plans (
                        action_id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        utterance_id TEXT NOT NULL,
                        domain TEXT NOT NULL,
                        intent_name TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        expires_at_ms INTEGER NOT NULL DEFAULT 0
                    )""");
            // D14c 审计记录:append-only,独立于动作最新状态(不能只留最新状态)
            c.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS action_audit (
                        seq INTEGER PRIMARY KEY AUTOINCREMENT,
                        action_id TEXT NOT NULL,
                        event TEXT NOT NULL,
                        detail TEXT NOT NULL,
                        recorded_at_ms INTEGER NOT NULL
                    )""");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open action ledger at " + dbPath, e);
        }
    }

    @Override
    public boolean recordDispatch(ActionPlan plan) {
        if (plan.actionId().isBlank()) {
            return false;
        }
        try (Connection c = connect()) {
            try (PreparedStatement insert = c.prepareStatement("""
                    INSERT OR IGNORE INTO action_plans
                    (action_id, session_id, utterance_id, domain, intent_name, summary,
                     created_at_ms, expires_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
                insert.setString(1, plan.actionId());
                insert.setString(2, plan.sessionId());
                insert.setString(3, plan.utteranceId());
                insert.setString(4, plan.domain());
                insert.setString(5, plan.intent());
                insert.setString(6, plan.summary());
                insert.setLong(7, plan.createdAtMs());
                insert.setLong(8, plan.expiresAtMs());
                boolean inserted = insert.executeUpdate() == 1; // INSERT OR IGNORE:0 = 已存在(幂等)
                if (inserted) {
                    recordAudit(plan.actionId(), ActionLedger.AUDIT_ISSUED,
                            "session=" + plan.sessionId() + " intent=" + plan.intent());
                }
                return inserted;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("action ledger record failed", e);
        }
    }

    @Override
    public Optional<ActionPlan> findByActionId(String actionId) {
        if (actionId == null || actionId.isBlank()) {
            return Optional.empty();
        }
        try (Connection c = connect();
             PreparedStatement query = c.prepareStatement("""
                     SELECT action_id, session_id, utterance_id, domain, intent_name, summary,
                            created_at_ms, expires_at_ms
                     FROM action_plans WHERE action_id = ?""")) {
            query.setString(1, actionId);
            try (ResultSet rs = query.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ActionPlan(
                        rs.getString("action_id"), rs.getString("session_id"),
                        rs.getString("utterance_id"), rs.getString("domain"),
                        rs.getString("intent_name"), rs.getString("summary"),
                        rs.getLong("created_at_ms"), rs.getLong("expires_at_ms")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("action ledger query failed", e);
        }
    }

    @Override
    public void recordAudit(String actionId, String event, String detail) {
        if (actionId == null || actionId.isBlank()) {
            return;
        }
        try (Connection c = connect();
             PreparedStatement insert = c.prepareStatement("""
                     INSERT INTO action_audit (action_id, event, detail, recorded_at_ms)
                     VALUES (?, ?, ?, ?)""")) {
            insert.setString(1, actionId);
            insert.setString(2, event == null ? "" : event);
            insert.setString(3, detail == null ? "" : detail);
            insert.setLong(4, clock.getAsLong());
            insert.executeUpdate();
        } catch (SQLException e) {
            // D14c:审计写失败必须显式失败——调用方据此拒绝相关写动作,而不是静默继续
            throw new IllegalStateException("action audit write failed", e);
        }
    }

    /** 审计记录条数(诊断/测试用)。 */
    public int auditCount(String actionId) {
        try (Connection c = connect();
             PreparedStatement query = c.prepareStatement(
                     "SELECT COUNT(*) FROM action_audit WHERE action_id = ?")) {
            query.setString(1, actionId);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("action audit query failed", e);
        }
    }

    @Override
    public void close() {
        // 连接按次开关,无长连接持有
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }
}

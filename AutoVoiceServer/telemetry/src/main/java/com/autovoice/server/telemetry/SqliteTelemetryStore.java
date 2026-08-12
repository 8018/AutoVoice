package com.autovoice.server.telemetry;

import com.autovoice.server.contracts.telemetry.TelemetryEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SQLite 存储封装（JDBC 短连接）：rounds（每轮聚合行）+ events（阶段明细行）。
 * 写操作由 {@link TelemetryService} 单写线程串行调用；读可直连（PRAGMA busy_timeout=5000
 * 兜底并发写等待）。schema 见 CREATE TABLE（与 spec 一致，events.payload_json 为 JSON 文本）。
 */
public class SqliteTelemetryStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final String dbPath;

    public SqliteTelemetryStore(String dbPath) {
        this.dbPath = dbPath;
    }

    /** 建库（含父目录）+ 建表 + 索引；构造后调用一次。 */
    public void init() {
        Path p = Path.of(dbPath);
        Path parent = p.toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException("cannot create db dir: " + parent, e);
            }
        }
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS rounds (
                      utterance_id TEXT PRIMARY KEY,
                      session_id TEXT, device_id TEXT, source TEXT,
                      start_ms INTEGER, end_ms INTEGER,
                      local_decision TEXT, cloud_decision TEXT, final_decision TEXT,
                      asr_local TEXT, asr_cloud TEXT, llm_reply TEXT,
                      execute_result TEXT, tts_text TEXT, tts_cache_hit INTEGER,
                      playback_result TEXT, audio_path TEXT,
                      created_ms INTEGER
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS events (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      utterance_id TEXT NOT NULL,
                      stage TEXT NOT NULL, ts_ms INTEGER NOT NULL,
                      level TEXT NOT NULL, payload_json TEXT
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_events_utt ON events(utterance_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_rounds_created ON rounds(created_ms)");
        } catch (SQLException e) {
            throw new IllegalStateException("telemetry schema init failed: " + dbPath, e);
        }
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    /**
     * 合并写入聚合行：提供的字段集合插入（首见）或更新（已存在），未提供的列保持不动
     * （对应 brief 的 INSERT ... ON CONFLICT(utterance_id) DO UPDATE）。fields 为空时仅
     * 保证行存在（INSERT OR IGNORE）。
     */
    public void upsertRound(String utteranceId, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT OR IGNORE INTO rounds (utterance_id) VALUES (?)")) {
                ps.setString(1, utteranceId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("upsertRound(empty) failed: " + utteranceId, e);
            }
            return;
        }
        List<String> cols = new ArrayList<>(fields.keySet());
        String sql = "INSERT INTO rounds (utterance_id, " + String.join(", ", cols) + ") VALUES (?"
                + ", ?".repeat(cols.size()) + ") ON CONFLICT(utterance_id) DO UPDATE SET "
                + cols.stream().map(k -> k + "=excluded." + k).collect(Collectors.joining(", "));
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, utteranceId);
            for (String k : cols) {
                ps.setObject(i++, fields.get(k));
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("upsertRound failed: " + utteranceId, e);
        }
    }

    public void insertEvent(String utteranceId, TelemetryEvent e) {
        String payloadJson = null;
        if (e.payload() != null) {
            try {
                payloadJson = MAPPER.writeValueAsString(e.payload());
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("event payload serialization failed: " + utteranceId, ex);
            }
        }
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO events (utterance_id, stage, ts_ms, level, payload_json) VALUES (?,?,?,?,?)")) {
            ps.setString(1, utteranceId);
            ps.setString(2, e.stage());
            ps.setLong(3, e.tsMs());
            ps.setString(4, e.level());
            ps.setString(5, payloadJson);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("insertEvent failed: " + utteranceId, ex);
        }
    }

    /** 单轮明细：round 行不存在 → null；events 按 ts_ms 升序。 */
    public RoundDetail queryRound(String utteranceId) {
        RoundSummary summary = null;
        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM rounds WHERE utterance_id = ?")) {
                ps.setString(1, utteranceId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        summary = mapRound(rs);
                    }
                }
            }
            if (summary == null) {
                return null;
            }
            List<TelemetryEvent> events = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT stage, ts_ms, level, payload_json FROM events WHERE utterance_id = ?"
                            + " ORDER BY ts_ms, id")) {
                ps.setString(1, utteranceId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        events.add(new TelemetryEvent(rs.getString("stage"), rs.getLong("ts_ms"),
                                rs.getString("level"), parsePayload(rs.getString("payload_json"))));
                    }
                }
            }
            return new RoundDetail(summary, events);
        } catch (SQLException e) {
            throw new IllegalStateException("queryRound failed: " + utteranceId, e);
        }
    }

    /**
     * 轮次列表（设备/时间过滤）。时间轴取 start_ms（缺省回退 created_ms）：轮次实际发生的
     * 时刻对面板更有意义；聚合统计字段（event 数、末事件时刻）在 RoundSummary 固定字段集
     * 之外，面板侧可从 detail 取得。
     */
    public List<RoundSummary> queryRounds(String device, long fromMs, long toMs) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM rounds WHERE COALESCE(start_ms, created_ms, 0) >= ?"
                        + " AND COALESCE(start_ms, created_ms, 0) <= ?");
        List<Object> params = new ArrayList<>();
        params.add(fromMs);
        params.add(toMs);
        if (device != null && !device.isBlank()) {
            sql.append(" AND device_id = ?");
            params.add(device);
        }
        sql.append(" ORDER BY COALESCE(start_ms, created_ms, 0) DESC, utterance_id DESC");
        List<RoundSummary> out = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapRound(rs));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("queryRounds failed", e);
        }
    }

    /** 删 created_ms < cutoff 的 rounds+events 行，返回被删 utterance_id 供音频文件联动清理。 */
    public Set<String> deleteOlderThan(long cutoffMs) {
        Set<String> ids = new HashSet<>();
        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT utterance_id FROM rounds WHERE created_ms < ?")) {
                ps.setLong(1, cutoffMs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
            }
            if (!ids.isEmpty()) {
                c.setAutoCommit(false);
                try (PreparedStatement dr = c.prepareStatement("DELETE FROM rounds WHERE utterance_id = ?");
                     PreparedStatement de = c.prepareStatement("DELETE FROM events WHERE utterance_id = ?")) {
                    for (String id : ids) {
                        dr.setString(1, id);
                        dr.executeUpdate();
                        de.setString(1, id);
                        de.executeUpdate();
                    }
                    c.commit();
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("deleteOlderThan failed: cutoff=" + cutoffMs, e);
        }
    }

    private static RoundSummary mapRound(ResultSet rs) throws SQLException {
        int hit = rs.getInt("tts_cache_hit");
        Boolean ttsCacheHit = rs.wasNull() ? null : hit != 0;
        return new RoundSummary(rs.getString("utterance_id"), rs.getString("device_id"),
                rs.getString("source"), rs.getLong("start_ms"), rs.getLong("end_ms"),
                rs.getString("local_decision"), rs.getString("cloud_decision"),
                rs.getString("final_decision"), ttsCacheHit, rs.getString("playback_result"),
                rs.getString("audio_path"));
    }

    private static Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (IOException e) {
            return Map.of("_parse_error", json);
        }
    }
}

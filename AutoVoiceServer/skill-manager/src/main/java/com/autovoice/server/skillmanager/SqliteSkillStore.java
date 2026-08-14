package com.autovoice.server.skillmanager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** skill 表 SQLite 存储：短连接 + busy_timeout，模式同 telemetry 存储。 */
public class SqliteSkillStore {

    private final String dbPath;

    public SqliteSkillStore(String dbPath) {
        this.dbPath = dbPath;
    }

    public void init() {
        Path p = Path.of(dbPath);
        Path parent = p.toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("skill db dir create failed: " + parent, e);
            }
        }
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS skills ("
                    + "id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '',"
                    + "mcp_url TEXT NOT NULL, auth_header TEXT NOT NULL DEFAULT '', auth_value TEXT NOT NULL DEFAULT '',"
                    + "tools_json TEXT NOT NULL DEFAULT '[]', enabled INTEGER NOT NULL DEFAULT 0,"
                    + "updated_at INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS settings ("
                    + "key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("skill schema init failed: " + dbPath, e);
        }
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    /** enabledOnly=true 时仅返回 enabled=1 的记录（网关拉取路径）。 */
    public List<SkillRecord> findAll(boolean enabledOnly) {
        String sql = enabledOnly
                ? "SELECT * FROM skills WHERE enabled=1 ORDER BY id"
                : "SELECT * FROM skills ORDER BY id";
        List<SkillRecord> out = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("skill find failed", e);
        }
        return out;
    }

    public SkillRecord findById(String id) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM skills WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("skill findById failed: " + id, e);
        }
    }

    public Optional<String> getSetting(String key) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT value FROM settings WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("value")) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("setting get failed: " + key, e);
        }
    }

    public void setSetting(String key, String value) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO settings (key, value) VALUES (?,?)"
                        + " ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("setting set failed: " + key, e);
        }
    }

    public void upsert(SkillRecord r) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO skills (id, name, description, mcp_url, auth_header, auth_value,"
                        + " tools_json, enabled, updated_at) VALUES (?,?,?,?,?,?,?,?,?)"
                        + " ON CONFLICT(id) DO UPDATE SET name=excluded.name, description=excluded.description,"
                        + " mcp_url=excluded.mcp_url, auth_header=excluded.auth_header, auth_value=excluded.auth_value,"
                        + " tools_json=excluded.tools_json, enabled=excluded.enabled, updated_at=excluded.updated_at")) {
            ps.setString(1, r.id());
            ps.setString(2, r.name());
            ps.setString(3, r.description());
            ps.setString(4, r.mcpUrl());
            ps.setString(5, r.authHeader());
            ps.setString(6, r.authValue());
            ps.setString(7, r.toolsJson());
            ps.setInt(8, r.enabled() ? 1 : 0);
            ps.setLong(9, r.updatedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("skill upsert failed: " + r.id(), e);
        }
    }

    public void delete(String id) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM skills WHERE id=?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("skill delete failed: " + id, e);
        }
    }

    private static SkillRecord map(ResultSet rs) throws SQLException {
        return new SkillRecord(rs.getString("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("mcp_url"), rs.getString("auth_header"), rs.getString("auth_value"),
                rs.getString("tools_json"), rs.getInt("enabled") == 1, rs.getLong("updated_at"));
    }
}

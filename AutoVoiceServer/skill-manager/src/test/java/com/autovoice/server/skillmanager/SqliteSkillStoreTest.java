package com.autovoice.server.skillmanager;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class SqliteSkillStoreTest {

    private String tmpDb() throws Exception {
        return Files.createTempDirectory("skill-store-test").resolve("skills.db").toString();
    }

    @Test
    void crudAndEnabledFilter() throws Exception {
        SqliteSkillStore store = new SqliteSkillStore(tmpDb());
        store.init();
        SkillRecord a = new SkillRecord("amap-maps", "高德地图", "导航", "https://mcp.example.com/mcp",
                "x-api-key", "secret-1", "[]", true, 100L);
        SkillRecord b = new SkillRecord("weather", "天气", "查天气", "https://mcp2.example.com/mcp",
                "", "", "[]", false, 200L);
        store.upsert(a);
        store.upsert(b);

        assertEquals(2, store.findAll(false).size());
        List<SkillRecord> enabled = store.findAll(true);
        assertEquals(1, enabled.size());
        assertEquals("amap-maps", enabled.get(0).id());
        assertEquals("secret-1", store.findById("amap-maps").authValue()); // 库内存明文

        // upsert 覆盖：a 改为 disabled 后 enabled 列表应为空；findAll(false) 仍 2 条
        store.upsert(new SkillRecord("amap-maps", "高德地图2", "导航2", "https://mcp.example.com/mcp",
                "x-api-key", "secret-2", "[]", false, 300L));
        assertTrue(store.findAll(true).isEmpty());
        assertEquals(2, store.findAll(false).size());
        assertEquals("secret-2", store.findById("amap-maps").authValue()); // 覆盖生效
    }
}

package com.autovoice.server.skillmanager;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

@SpringBootTest(properties = {
        "autovoice.skill-manager.db-path=${java.io.tmpdir}/skill-mgr-test-${random.uuid}.db",
        "autovoice.skill-manager.admin-token=admin-secret",
        "autovoice.skill-manager.service-token=svc-secret",
        "autovoice.skill-manager.gateway-webhook-url="})
@AutoConfigureMockMvc
class SkillControllerTest {

    @Autowired MockMvc mvc;
    @Autowired SqliteSkillStore store;
    static final ObjectMapper MAPPER = new ObjectMapper();

    /** 共享 Spring 上下文/库文件：每用例前清掉 amap-maps，保证用例间独立。 */
    @BeforeEach
    void cleanDb() {
        store.delete("amap-maps");
    }

    private String login() throws Exception {
        return mvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"admin-secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("skill_admin").getValue();
    }

    private org.springframework.test.web.servlet.MvcResult createSkill() throws Exception {
        return mvc.perform(post("/api/skills")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"amap-maps\",\"name\":\"高德地图\",\"description\":\"导航\","
                                + "\"mcpUrl\":\"https://mcp.example.com/mcp\",\"authHeader\":\"x-api-key\","
                                + "\"authValue\":\"secret-1\",\"toolsJson\":\"[]\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void unauthenticatedGets401() throws Exception {
        mvc.perform(get("/api/skills")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"a\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void serviceTokenPullsEnabledWithRawSecret() throws Exception {
        createSkill();
        mvc.perform(get("/api/skills").param("enabled", "true")
                        .header("X-Skill-Service-Token", "svc-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].authValue").value("secret-1"))      // 网关拿明文
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void adminViewMasksSecret() throws Exception {
        createSkill();
        mvc.perform(get("/api/skills").cookie(new jakarta.servlet.http.Cookie("skill_admin", login())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].authValue").value("****"));         // 管理端掩码
    }

    @Test
    void updateWithBlankAuthKeepsOldValue() throws Exception {
        createSkill();
        mvc.perform(put("/api/skills/amap-maps")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"amap-maps\",\"name\":\"高德地图2\",\"description\":\"导航2\","
                                + "\"mcpUrl\":\"https://mcp.example.com/mcp\",\"authHeader\":\"x-api-key\","
                                + "\"authValue\":\"\",\"toolsJson\":\"[]\",\"enabled\":true}"))
                .andExpect(status().isOk());
        assert "secret-1".equals(store.findById("amap-maps").authValue()) : "blank 保留旧值";
    }

    @Test
    void duplicateCreateReturns409() throws Exception {
        createSkill();
        mvc.perform(post("/api/skills")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"amap-maps\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void patchEnabledToggles() throws Exception {
        createSkill();
        mvc.perform(patch("/api/skills/amap-maps/enabled")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/skills").param("enabled", "true")
                        .header("X-Skill-Service-Token", "svc-secret"))
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void deleteRemoves() throws Exception {
        createSkill();
        mvc.perform(delete("/api/skills/amap-maps")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/skills/amap-maps")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login())))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateDeletePatchMissingIdReturns404() throws Exception {
        mvc.perform(put("/api/skills/ghost")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"ghost\",\"name\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/skills/ghost")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login())))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/skills/ghost/enabled")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidIdRejected400() throws Exception {
        // id 正则 [a-zA-Z0-9._-]+：空格/叹号不在字符集内
        mvc.perform(post("/api/skills")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"bad id!\",\"name\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }
}

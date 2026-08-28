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

@SpringBootTest(properties = {
        "autovoice.skill-manager.db-path=${java.io.tmpdir}/skill-mgr-cfg-test-${random.uuid}.db",
        "autovoice.skill-manager.admin-token=admin-secret",
        "autovoice.skill-manager.service-token=svc-secret",
        "autovoice.skill-manager.gateway-webhook-url="})
@AutoConfigureMockMvc
class ConfigControllerTest {

    @Autowired MockMvc mvc;
    @Autowired SqliteSkillStore store;
    static final ObjectMapper MAPPER = new ObjectMapper();

    /** 共享 Spring 上下文/库文件：每用例前重置 system_prompt，保证用例间独立。 */
    @BeforeEach
    void cleanDb() {
        store.setSetting("system_prompt", "");
        store.setSetting("chat_system_prompt", "");
    }

    private String login() throws Exception {
        return mvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"admin-secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("skill_admin").getValue();
    }

    @Test
    void getRequiresAuth() throws Exception {
        mvc.perform(get("/api/config/system-prompt")).andExpect(status().isUnauthorized());
    }

    @Test
    void getWithServiceTokenOrAdminCookie() throws Exception {
        mvc.perform(get("/api/config/system-prompt")
                        .header("X-Skill-Service-Token", "svc-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(""));
        mvc.perform(get("/api/config/system-prompt")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(""));
    }

    @Test
    void putRequiresAdminCookieServiceTokenRejected() throws Exception {
        mvc.perform(put("/api/config/system-prompt")
                        .header("X-Skill-Service-Token", "svc-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void putAndReadBack() throws Exception {
        String cookie = login();
        mvc.perform(put("/api/config/system-prompt")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", cookie))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"你是车载语音助手，说话简短。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("你是车载语音助手，说话简短。"));
        mvc.perform(get("/api/config/system-prompt")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", cookie)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("你是车载语音助手，说话简短。"));
    }

    @Test
    void chatPromptHasSeparateReadWriteEndpoint() throws Exception {
        String cookie = login();
        mvc.perform(put("/api/config/chat-system-prompt")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", cookie))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"你是陪伴型闲聊助手\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/config/chat-system-prompt")
                        .header("X-Skill-Service-Token", "svc-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("你是陪伴型闲聊助手"));
        mvc.perform(get("/api/config/system-prompt")
                        .header("X-Skill-Service-Token", "svc-secret"))
                .andExpect(jsonPath("$.value").value(""));
    }

    @Test
    void putEmptyValueAllowed() throws Exception {
        String cookie = login();
        mvc.perform(put("/api/config/system-prompt")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", cookie))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(""));
    }

    /** 缺失/null value 字段一律 400（防 {} 或拼错键名静默清空全平台 prompt；仅 {"value":""} 是显式恢复默认）。 */
    @Test
    void putMissingValueRejected() throws Exception {
        String cookie = login();
        mvc.perform(put("/api/config/system-prompt")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", cookie))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(put("/api/config/system-prompt")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", cookie))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vaule\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(put("/api/config/system-prompt")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", cookie))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}

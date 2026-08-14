package com.autovoice.server.skillmanager;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    static final ObjectMapper MAPPER = new ObjectMapper();

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
    void putEmptyValueAllowed() throws Exception {
        String cookie = login();
        mvc.perform(put("/api/config/system-prompt")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", cookie))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(""));
    }
}

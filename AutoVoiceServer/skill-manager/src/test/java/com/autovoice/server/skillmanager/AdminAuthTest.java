package com.autovoice.server.skillmanager;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "autovoice.skill-manager.db-path=${java.io.tmpdir}/skill-mgr-auth-${random.uuid}.db",
        "autovoice.skill-manager.admin-token=admin-secret",
        "autovoice.skill-manager.service-token=svc-secret"})
@AutoConfigureMockMvc
class AdminAuthTest {

    @Autowired MockMvc mvc;

    @Test
    void wrongPasswordRejected() throws Exception {
        mvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void emptyPasswordRejected() throws Exception {
        mvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongServiceTokenRejected() throws Exception {
        mvc.perform(get("/api/skills").param("enabled", "true")
                        .header("X-Skill-Service-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void emptyServiceTokenHeaderRejected() throws Exception {
        mvc.perform(get("/api/skills").param("enabled", "true")
                        .header("X-Skill-Service-Token", ""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutClearsCookie() throws Exception {
        mvc.perform(post("/api/admin/logout"))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("skill_admin", 0));
    }
}

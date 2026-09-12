package com.autovoice.server.skillmanager;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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

    // ---------- D03b:服务端会话 ----------

    private Cookie login() throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"admin-secret\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("skill_admin");
        org.junit.jupiter.api.Assertions.assertNotNull(cookie, "登录应签发会话 cookie");
        return cookie;
    }

    @Test
    void loginIssuesHardenedSessionCookieAndAuthorizes() throws Exception {
        Cookie cookie = login();
        org.junit.jupiter.api.Assertions.assertTrue(cookie.isHttpOnly(), "cookie 必须 HttpOnly");
        org.junit.jupiter.api.Assertions.assertEquals("Strict", cookie.getAttribute("SameSite"),
                "跨站不携带,阻断 CSRF 类跨站写入");
        mvc.perform(get("/api/skills").param("enabled", "true").cookie(cookie))
                .andExpect(status().isOk());
    }

    @Test
    void revokedCookieCannotBeReplayed() throws Exception {
        Cookie cookie = login();
        mvc.perform(post("/api/admin/logout").cookie(cookie))
                .andExpect(status().isOk());
        mvc.perform(get("/api/skills").param("enabled", "true").cookie(cookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginIsRateLimitedPerSourceIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/admin/login")
                            .header("X-Forwarded-For", "10.88.88.1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"nope\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/admin/login")
                        .header("X-Forwarded-For", "10.88.88.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"admin-secret\"}"))
                .andExpect(status().isTooManyRequests());
        // 其他来源不受影响
        mvc.perform(post("/api/admin/login")
                        .header("X-Forwarded-For", "10.77.77.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
    }
}

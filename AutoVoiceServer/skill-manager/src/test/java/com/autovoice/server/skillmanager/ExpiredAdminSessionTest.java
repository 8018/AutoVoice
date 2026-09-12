package com.autovoice.server.skillmanager;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** D03b 会话服务端过期:超过 TTL 的会话 cookie 被拒绝(不依赖浏览器 maxAge)。 */
@SpringBootTest(properties = {
        "autovoice.skill-manager.db-path=${java.io.tmpdir}/skill-mgr-expired-${random.uuid}.db",
        "autovoice.skill-manager.admin-token=admin-secret",
        "autovoice.skill-manager.service-token=svc-secret",
        "autovoice.skill-manager.admin-session-ttl-ms=50"})
@AutoConfigureMockMvc
class ExpiredAdminSessionTest {

    @Autowired MockMvc mvc;

    @Test
    void expiredSessionCookieIsRejected() throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"admin-secret\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("skill_admin");

        Thread.sleep(150); // TTL 50ms,等服务端判定过期

        mvc.perform(get("/api/skills").param("enabled", "true").cookie(cookie))
                .andExpect(status().isUnauthorized());
    }
}

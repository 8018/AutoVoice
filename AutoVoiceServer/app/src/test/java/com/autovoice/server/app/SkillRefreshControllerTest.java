package com.autovoice.server.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.autovoice.server.skillmcp.McpSkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "autovoice.skill-manager.url=http://127.0.0.1:1",
        "autovoice.skill-manager.service-token=svc-secret",
        "autovoice.skill-manager.poll-ms=600000",
        "autovoice.telemetry.db-path=${java.io.tmpdir}/skill-refresh-${random.uuid}.db"})
@AutoConfigureMockMvc
class SkillRefreshControllerTest {

    @Autowired MockMvc mvc;
    @MockBean McpSkillRegistry registry;

    @Test
    void wrongTokenRejected() throws Exception {
        mvc.perform(post("/api/internal/skills/refresh")
                        .header("X-Skill-Service-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void emptyOrMissingTokenHeaderRejected() throws Exception {
        mvc.perform(post("/api/internal/skills/refresh")
                        .header("X-Skill-Service-Token", ""))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/internal/skills/refresh"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenTriggersRefresh() throws Exception {
        mvc.perform(post("/api/internal/skills/refresh")
                        .header("X-Skill-Service-Token", "svc-secret")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(registry, org.mockito.Mockito.atLeastOnce()).refreshAsync();
    }
}

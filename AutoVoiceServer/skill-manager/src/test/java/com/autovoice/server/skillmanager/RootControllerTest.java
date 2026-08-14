package com.autovoice.server.skillmanager;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "autovoice.skill-manager.db-path=${java.io.tmpdir}/skill-mgr-root-test-${random.uuid}.db",
        "autovoice.skill-manager.admin-token=admin-secret",
        "autovoice.skill-manager.service-token=svc-secret",
        "autovoice.skill-manager.gateway-webhook-url="})
@AutoConfigureMockMvc
class RootControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void rootRedirectsToPanel() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/skill-manager/index.html"));
    }

    @Test
    void bareSkillManagerPathRedirectsToPanel() throws Exception {
        mvc.perform(get("/skill-manager"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/skill-manager/index.html"));
    }

    @Test
    void skillManagerDirPathRedirectsToPanel() throws Exception {
        mvc.perform(get("/skill-manager/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/skill-manager/index.html"));
    }
}

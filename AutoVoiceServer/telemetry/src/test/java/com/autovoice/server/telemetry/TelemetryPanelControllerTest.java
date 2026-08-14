package com.autovoice.server.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 面板目录地址 → index.html 重定向契约（与 skill 平台 rootfix 同款）。 */
@WebMvcTest(TelemetryPanelController.class)
class TelemetryPanelControllerTest {

    @SpringBootApplication
    static class SliceTestConfig {
    }

    @Autowired
    MockMvc mvc;

    @Test
    void telemetryDirRedirectsToIndex() throws Exception {
        mvc.perform(get("/telemetry"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/telemetry/index.html"));
    }

    @Test
    void telemetrySlashRedirectsToIndex() throws Exception {
        mvc.perform(get("/telemetry/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/telemetry/index.html"));
    }
}

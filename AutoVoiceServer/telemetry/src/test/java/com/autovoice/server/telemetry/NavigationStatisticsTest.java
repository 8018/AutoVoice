package com.autovoice.server.telemetry;

import com.autovoice.server.contracts.telemetry.TelemetryEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NavigationStatisticsTest {
    private static final String PATH = "/api/telemetry/navigation/stats";

    @TempDir Path tmp;

    private TelemetryService service() {
        return new TelemetryService(new TelemetryProperties(true,
                tmp.resolve("telemetry.db").toString(), tmp.resolve("audio").toString(), 7));
    }

    private MockMvc mvc(TelemetryController controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new TelemetryAuthInterceptor("upload-test", "admin-test"))
                .build();
    }

    private void record(TelemetryService service, String id, String device, long time, Map<String, Object> payload) {
        service.recordDeviceRound(new TelemetryService.DeviceRoundPayload(id, "test-session", device,
                "navigation", time - 1, time,
                List.of(new TelemetryEvent("navigation_operation", time, "info", payload))));
    }

    private Map<String, Object> terminal(String operation, String result, Object duration) {
        return Map.of("operation", operation, "result", result, "durationMs", duration);
    }

    @Test
    void countsValidUniqueTerminalSamplesByDeviceAndCompletionWindow() throws Exception {
        try (var service = service(); var controller = new TelemetryController(service)) {
            record(service, "op1", "car-a", 100, terminal("start", "succeeded", 0));
            service.recordDeviceRound(new TelemetryService.DeviceRoundPayload("op1", "other-session", "car-b",
                    "navigation", 100L, 101L,
                    List.of(new TelemetryEvent("navigation_operation", 101, "warn",
                            terminal("start", "failed", 9_000)))));
            record(service, "op2", "car-a", 110, terminal("start", "accepted", 100));
            record(service, "op3", "car-a", 120, terminal("start", "failed", 300));
            record(service, "op4", "car-a", 121, terminal("start", "unknown", 400));
            record(service, "op5", "car-b", 110, terminal("start", "succeeded", 500));
            record(service, "op6", "car-a", 111, terminal("start", "unknown", 200));
            record(service, "op7", "car-a", 112, terminal("plan", "succeeded", 40));
            record(service, "op8", "car-a", 110, terminal("invalid", "succeeded", 1));
            record(service, "op9", "car-a", 110, terminal("start", "invalid", 1));
            record(service, "op10", "car-a", 110, terminal("start", "succeeded", -1));
            record(service, "op11", "car-a", 110, terminal("start", "succeeded", "15"));
            record(service, "op12", "car-a", 110, terminal("start", "succeeded", 1.5));
            record(service, "op13", "car-a", 110, terminal("start", "succeeded", Long.MAX_VALUE));
            record(service, "op14", "car-a", 99, terminal("start", "succeeded", 30));
            record(service, "op14", "car-a", 110, terminal("start", "succeeded", 30));
            record(service, "op15", "", 110, terminal("start", "succeeded", 1));
            service.record("anonymous", new TelemetryEvent("navigation_operation", 110, "info",
                    terminal("start", "succeeded", 1)));

            mvc(controller).perform(get(PATH).header("X-Telemetry-Token", "admin-test")
                            .param("device", "car-a").param("from", "100").param("to", "120"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.from").value(100))
                    .andExpect(jsonPath("$.to").value(120))
                    .andExpect(jsonPath("$.operations.length()").value(2))
                    .andExpect(jsonPath("$.operations[0].operation").value("plan"))
                    .andExpect(jsonPath("$.operations[0].total").value(1))
                    .andExpect(jsonPath("$.operations[1].operation").value("start"))
                    .andExpect(jsonPath("$.operations[1].total").value(4))
                    .andExpect(jsonPath("$.operations[1].successRate").value(0.25))
                    .andExpect(jsonPath("$.operations[1].outcomes.succeeded").value(1))
                    .andExpect(jsonPath("$.operations[1].outcomes.accepted").value(1))
                    .andExpect(jsonPath("$.operations[1].outcomes.unknown").value(1))
                    .andExpect(jsonPath("$.operations[1].outcomes.failed").value(1))
                    .andExpect(jsonPath("$.operations[1].outcomes.timeout").value(0))
                    .andExpect(jsonPath("$.operations[1].p50Ms").value(100))
                    .andExpect(jsonPath("$.operations[1].p95Ms").value(300));

            mvc(controller).perform(get(PATH).header("X-Telemetry-Token", "admin-test")
                            .param("from", "100").param("to", "120"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.operations[1].total").value(5));
        }
    }

    @Test
    void requiresAdminAccessAndAnExplicitBoundedWindow() throws Exception {
        try (var service = service(); var controller = new TelemetryController(service)) {
            var mvc = mvc(controller);
            mvc.perform(get(PATH).header("X-Telemetry-Token", "upload-test")
                            .param("from", "100").param("to", "120"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get(PATH).header("X-Telemetry-Token", "admin-test"))
                    .andExpect(status().isBadRequest());
            for (String to : List.of("99", "999999999999", "text")) {
                mvc.perform(get(PATH).header("X-Telemetry-Token", "admin-test")
                                .param("from", "100").param("to", to))
                        .andExpect(status().isBadRequest());
            }
            mvc.perform(get(PATH).header("X-Telemetry-Token", "admin-test")
                            .param("from", "100").param("to", "120"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.operations.length()").value(0));
        }
    }
}

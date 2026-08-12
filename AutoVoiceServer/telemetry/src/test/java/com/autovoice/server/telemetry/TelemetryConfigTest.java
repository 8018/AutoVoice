package com.autovoice.server.telemetry;

import com.autovoice.server.contracts.telemetry.NoopTelemetryRecorder;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配测试（review finding 5b）：
 * <ul>
 *   <li>enabled=false → telemetryRecorder=Noop，TelemetryService/TelemetryController 均不注册</li>
 *   <li>enabled（默认 true）→ telemetryRecorder 是唯一的 TelemetryService 实例（同一 bean，
 *       避免双实例导致 Controller 与插桩 recorder 各写各的）</li>
 * </ul>
 */
class TelemetryConfigTest {

    @SpringBootApplication
    static class ConfigTestApp {
    }

    @Nested
    @SpringBootTest(classes = TelemetryConfigTest.ConfigTestApp.class,
            properties = "autovoice.telemetry.enabled=false")
    class Disabled {

        @Autowired
        ApplicationContext ctx;

        @Autowired
        TelemetryRecorder recorder;

        @Test
        void recorderIsNoopAndModuleNotAssembled() {
            assertSame(NoopTelemetryRecorder.INSTANCE, recorder);
            assertTrue(ctx.getBeansOfType(TelemetryService.class).isEmpty(),
                    "enabled=false 时 TelemetryService 不应注册");
            assertTrue(ctx.getBeansOfType(TelemetryController.class).isEmpty(),
                    "enabled=false 时 TelemetryController 不应注册");
        }
    }

    @Nested
    @SpringBootTest(classes = TelemetryConfigTest.ConfigTestApp.class,
            properties = {
                    "autovoice.telemetry.db-path=${java.io.tmpdir}/autovoice-telemetry-test/t.db",
                    "autovoice.telemetry.audio-dir=${java.io.tmpdir}/autovoice-telemetry-test/audio"})
    class Enabled {

        @Autowired
        TelemetryRecorder recorder;

        @Autowired
        TelemetryService service;

        @Test
        void recorderIsTheTelemetryServiceInstance() {
            assertSame(service, recorder, "recorder 应复用同一 TelemetryService bean");
        }
    }
}

package com.autovoice.server.app;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autovoice.server.app.AppConfig.AutovoiceProperties.Gateway;
import com.autovoice.server.telemetry.TelemetryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 生产配置 fail-closed 契约(D01a):production profile 下,身份与管理边界配置缺失必须拒绝启动。
 * 演示/本地开发显式使用 demo-full profile,不受本校验约束。
 */
class ProductionConfigGuardTest {

    private static Gateway gateway(boolean authEnabled, String devices) {
        return new Gateway(authEnabled, devices, 32);
    }

    private static TelemetryProperties telemetry(String accessToken, String adminToken) {
        return new TelemetryProperties(true, "./telemetry.db", "./telemetry-audio", 7, accessToken, adminToken);
    }

    private static void run(Gateway gateway, TelemetryProperties telemetry) {
        new ProductionConfigGuard(gateway, telemetry)
            .run(new DefaultApplicationArguments());
    }

    @Test
    void productionRequiresGatewayAuthEnabled() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            run(gateway(false, "{\"demo-1\":\"t\"}"), telemetry("secret", "admin-secret")));
        assertTrue(error.getMessage().contains("auth-enabled"), error.getMessage());
    }

    @Test
    void productionRequiresNonEmptyAuthDevices() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            run(gateway(true, "{}"), telemetry("secret", "admin-secret")));
        assertTrue(error.getMessage().contains("auth-devices"), error.getMessage());
    }

    @Test
    void productionRequiresTelemetryAccessToken() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            run(gateway(true, "{\"demo-1\":\"t\"}"), telemetry("", "admin-secret")));
        assertTrue(error.getMessage().contains("access-token"), error.getMessage());
    }

    @Test
    void productionRequiresTelemetryAdminToken() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            run(gateway(true, "{\"demo-1\":\"t\"}"), telemetry("secret", "")));
        assertTrue(error.getMessage().contains("admin-token"), error.getMessage());
    }

    @Test
    void productionStartsWhenAllBoundariesConfigured() {
        assertDoesNotThrow(() ->
            run(gateway(true, "{\"demo-1\":\"t\"}"), telemetry("secret", "admin-secret")));
    }

    @Test
    void guardIsRegisteredOnlyUnderProductionProfile() {
        var production = new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=production")
            .withBean(Gateway.class, () -> gateway(true, "{\"demo-1\":\"t\"}"))
            .withBean(TelemetryProperties.class, () -> telemetry("secret", "admin-secret"))
            .withUserConfiguration(ProductionConfigGuard.class);
        production.run(context -> assertTrue(context.containsBean("productionConfigGuard")));

        var demo = new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=demo-full")
            .withBean(Gateway.class, () -> gateway(true, "{\"demo-1\":\"t\"}"))
            .withBean(TelemetryProperties.class, () -> telemetry("secret", "admin-secret"))
            .withUserConfiguration(ProductionConfigGuard.class);
        demo.run(context -> assertFalse(context.containsBean("productionConfigGuard")));
    }
}

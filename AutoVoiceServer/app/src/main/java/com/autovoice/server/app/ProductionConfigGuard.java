package com.autovoice.server.app;

import com.autovoice.server.app.AppConfig.AutovoiceProperties.Gateway;
import com.autovoice.server.telemetry.TelemetryProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产配置 fail-closed 契约(D01a):仅 production profile 激活时执行。
 *
 * <p>身份边界(gateway 鉴权 + 设备表)与管理边界(遥测访问令牌)缺失任一,拒绝启动并列出缺失项;
 * 演示/本地开发显式使用 demo-full profile,不经过本校验。</p>
 */
@Component
@Profile("production")
public class ProductionConfigGuard implements ApplicationRunner {

    private final Gateway gateway;
    private final TelemetryProperties telemetry;

    public ProductionConfigGuard(Gateway gateway, TelemetryProperties telemetry) {
        this.gateway = gateway;
        this.telemetry = telemetry;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> missing = new ArrayList<>();
        if (!gateway.authEnabled()) {
            missing.add("autovoice.gateway.auth-enabled=true (AUTOVOICE_GATEWAY_AUTH_ENABLED)");
        }
        if (gateway.authDevicesMap().isEmpty()) {
            missing.add("autovoice.gateway.auth-devices 非空设备表 (AUTOVOICE_GATEWAY_AUTH_DEVICES)");
        }
        if (telemetry.accessToken() == null || telemetry.accessToken().isBlank()) {
            missing.add("autovoice.telemetry.access-token (AUTOVOICE_TELEMETRY_ACCESS_TOKEN)");
        }
        if (telemetry.adminToken() == null || telemetry.adminToken().isBlank()) {
            missing.add("autovoice.telemetry.admin-token (AUTOVOICE_TELEMETRY_ADMIN_TOKEN)");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "production 配置校验失败,缺失: " + String.join("; ", missing));
        }
    }
}

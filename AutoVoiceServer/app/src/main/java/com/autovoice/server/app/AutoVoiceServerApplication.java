package com.autovoice.server.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AutoVoice 云端服务入口。
 *
 * <p>启动方式：{@code ./gradlew :app:bootRun --args='--spring.profiles.active=demo-full'}
 * （默认 8080 端口，WS 端点 {@code /ws}）。装配见 {@link AppConfig}，端点注册见
 * {@link WebSocketConfig}。</p>
 *
 * <p>scanBasePackages 显式含 {@code com.autovoice.server.telemetry}：默认扫描只覆盖
 * 本类所在包（{@code com.autovoice.server.app}）及其子包，telemetry 模块的
 * TelemetryService/TelemetryController/TelemetryConfig 是 @Component/@Configuration
 * 组件，不加则生产装配缺失（链路数据平台整个不生效、每日清理不调度）。</p>
 */
@SpringBootApplication(scanBasePackages = {
        "com.autovoice.server.app",
        "com.autovoice.server.telemetry"})
public class AutoVoiceServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoVoiceServerApplication.class, args);
    }
}

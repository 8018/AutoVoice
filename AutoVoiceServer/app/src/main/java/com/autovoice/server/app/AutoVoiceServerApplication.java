package com.autovoice.server.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AutoVoice 云端服务入口。
 *
 * <p>启动方式：{@code ./gradlew :app:bootRun --args='--spring.profiles.active=demo-full'}
 * （默认 8080 端口，WS 端点 {@code /ws}）。装配见 {@link AppConfig}，端点注册见
 * {@link WebSocketConfig}。</p>
 */
@SpringBootApplication
public class AutoVoiceServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoVoiceServerApplication.class, args);
    }
}
